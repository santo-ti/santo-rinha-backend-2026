package dev.santo

import dev.santo.fraud.FraudDetectorService
import dev.santo.search.IndexState
import dev.santo.server.RawHttpServer
import dev.santo.vectorization.ByteVectorizer
import dev.santo.vectorization.ReferenceResources
import dev.santo.vectorization.Vectorizer
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end exercise of the hand-rolled [RawHttpServer] over a real loopback socket: it must
 * parse the request head, read the Content-Length body, route both endpoints, keep the
 * connection alive between requests, and answer 200 (never 5xx) even on malformed input.
 */
class RawHttpServerTest {

    private val constants = ReferenceResources.loadNormalization()
    private val mccRisk = ReferenceResources.loadMccRisk()

    private val payload =
        """{"id":"tx-1","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},""" +
            """"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},""" +
            """"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},""" +
            """"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}"""

    private fun server(index: dev.santo.search.VectorIndex?): Pair<RawHttpServer, Int> {
        val state = IndexState().apply { if (index != null) publish(index) }
        val service = FraudDetectorService(Vectorizer(constants, mccRisk), ByteVectorizer(constants, mccRisk), state)
        val server = RawHttpServer(service, state)
        val channel = server.open(null, 0) // ephemeral TCP port
        val port = (channel.localAddress as InetSocketAddress).port
        thread(isDaemon = true) { runCatching { server.serve(channel) } }
        return server to port
    }

    private fun post(out: java.io.OutputStream, body: String) {
        val bytes = body.toByteArray()
        out.write(
            ("POST /fraud-score HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    /** Reads one HTTP response: status line + headers, then Content-Length bytes of body. */
    private fun readResponse(input: java.io.InputStream): Pair<Int, String> {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
        val status = head.substring(9, 12).toInt()
        val cl = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(cl)
        var n = 0
        while (n < cl) n += input.read(body, n, cl - n)
        return status to String(body)
    }

    @Test
    fun `POST fraud-score returns the pre-rendered body for the fraud count`() {
        val (_, port) = server(FixedFraudIndex(3))
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port))
            post(s.getOutputStream(), payload)
            val (status, body) = readResponse(s.getInputStream())
            assertEquals(200, status)
            assertTrue(body.contains("\"fraud_score\":0.6"), "3/5 fraud -> score 0.6; got $body")
            assertTrue(body.contains("\"approved\":false"), body)
        }
    }

    @Test
    fun `keep-alive serves two requests on one connection`() {
        val (_, port) = server(FixedFraudIndex(0))
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port))
            val out = s.getOutputStream(); val input = s.getInputStream()
            post(out, payload)
            assertEquals(200, readResponse(input).first)
            post(out, payload) // second request on the same kept-alive connection
            val (status, body) = readResponse(input)
            assertEquals(200, status)
            assertTrue(body.contains("\"approved\":true"), body)
        }
    }

    @Test
    fun `GET ready reflects index state`() {
        val (_, readyPort) = server(FixedFraudIndex(0))
        val (_, notReadyPort) = server(null)
        assertEquals(200, get(readyPort))
        assertEquals(503, get(notReadyPort))
    }

    @Test
    fun `malformed body still answers 200 (never 5xx)`() {
        val (_, port) = server(FixedFraudIndex(5))
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port))
            post(s.getOutputStream(), "{ not valid json")
            assertEquals(200, readResponse(s.getInputStream()).first)
        }
    }

    private fun get(port: Int): Int = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", port))
        s.getOutputStream().write("GET /ready HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        s.getOutputStream().flush()
        readResponse(s.getInputStream()).first
    }
}
