package dev.santo.server

import dev.santo.fraud.FraudDetectorService
import dev.santo.search.IndexState
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal HTTP/1.1 server for the two contest routes, replacing the Ktor CIO engine on the hot
 * path (env `SERVER_ENGINE=nio`). Why: Ktor's coroutine-per-request pipeline adds scheduling +
 * per-request allocation that the proven top entries (santannaf) avoid with a hand-rolled server.
 * Here a virtual thread (JVM 25) handles each kept-alive connection with blocking I/O and reused
 * per-connection scratch buffers, so the steady state is allocation-free and there is no framework
 * between the socket and the [FraudDetectorService] byte path.
 *
 * Only `GET /ready` and `POST /fraud-score` exist, and `/fraud-score` has just `K+1` possible
 * bodies — every response (status line + headers + body) is pre-rendered once, so a request is:
 * parse line + headers → (vectorize body → fraud count) → write a ready byte[]. Binds a Unix
 * domain socket when `SERVER_SOCKET_PATH` is set (LB→API over UDS), else TCP `0.0.0.0:8080`.
 */
class RawHttpServer(
    private val service: FraudDetectorService,
    private val indexState: IndexState,
) {
    fun start(socketPath: String?, tcpPort: Int = 8080) = serve(open(socketPath, tcpPort))

    /** Accept loop: a virtual thread per kept-alive connection. Blocks until the channel closes. */
    internal fun serve(server: ServerSocketChannel) {
        while (true) {
            val channel = server.accept()
            // Disable Nagle: without it, our small pre-rendered responses sit ~40ms waiting to
            // coalesce with delayed ACKs — the cause of the 73ms p99 in v1.13.0. No-op on UNIX.
            try { channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true) } catch (_: Throwable) {}
            Thread.startVirtualThread { handleConnection(channel) }
        }
    }

    internal fun open(socketPath: String?, tcpPort: Int): ServerSocketChannel =
        if (socketPath.isNullOrBlank()) {
            ServerSocketChannel.open().apply { bind(InetSocketAddress("0.0.0.0", tcpPort), BACKLOG) }
        } else {
            Files.deleteIfExists(Path.of(socketPath)) // clear a stale socket so bind succeeds
            ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                .apply { bind(UnixDomainSocketAddress.of(socketPath), BACKLOG) }
        }

    private fun handleConnection(channel: java.nio.channels.SocketChannel) {
        try {
            channel.use {
                val input = Channels.newInputStream(it)
                val output = Channels.newOutputStream(it)
                val conn = Connection(input, output)
                while (conn.handleOne()) { /* keep-alive: serve until the client closes */ }
            }
        } catch (_: Throwable) {
            // Connection-level failure (reset, malformed framing): drop it. A request-level error
            // is handled inside handleOne and still answers 200 — an HTTP 5xx weighs 5x a miss.
        }
    }

    /** Per-connection state: reused scratch so steady-state request handling allocates nothing. */
    private inner class Connection(private val input: InputStream, private val output: OutputStream) {
        private val header = ByteArray(8 * 1024)
        private var body = ByteArray(16 * 1024)

        /** Serves one request; returns false to close the connection (EOF or `Connection: close`). */
        fun handleOne(): Boolean {
            val headEnd = readHead() ?: return false
            val request = parseHead(headEnd)
            if (request.isPost && request.contentLength > 0) {
                readBody(request) // a framing/truncation error throws here → connection is dropped
                // A vectorize error must NOT surface as 5xx (weighs 5x a miss): fall back to count 0.
                val count = try { service.fraudCountOf(body, request.contentLength) } catch (_: Throwable) { 0 }
                output.write(Responses.fraud(count))
            } else if (request.isReady) {
                output.write(if (indexState.isReady) Responses.READY_200 else Responses.READY_503)
            } else {
                output.write(Responses.fraud(0)) // unknown route → safe, never 5xx
            }
            output.flush()
            return request.keepAlive
        }

        /** Reads up to the blank line terminating the request head; returns its end offset or null. */
        private fun readHead(): Int? {
            var n = 0
            while (true) {
                val read = input.read(header, n, header.size - n)
                if (read < 0) return null
                n += read
                val end = indexOfHeadEnd(header, n)
                if (end >= 0) { pending = n; return end }
                if (n == header.size) return null // header too large
            }
        }

        // Bytes already read past the head (the start of the body) live in [header]; [pending] is
        // the total valid bytes in [header], and the body begins right after the blank line.
        private var pending = 0

        private fun readBody(request: Request) {
            val cl = request.contentLength
            if (body.size < cl) body = ByteArray(Integer.highestOneBit(cl) * 2)
            val already = pending - request.bodyStart
            if (already > 0) System.arraycopy(header, request.bodyStart, body, 0, already)
            var have = already
            while (have < cl) {
                val read = input.read(body, have, cl - have)
                if (read < 0) throw IllegalStateException("truncated body")
                have += read
            }
        }

        private val request = Request()
        private fun parseHead(headEnd: Int): Request {
            val r = request.reset()
            // Request line: METHOD SP PATH SP HTTP/1.1
            r.isPost = header[0] == 'P'.code.toByte()
            // path starts after the first space
            var i = 0
            while (i < headEnd && header[i] != ' '.code.toByte()) i++
            val pathStart = i + 1
            r.isReady = !r.isPost && regionMatches(header, pathStart, "/ready")
            r.contentLength = parseContentLength(header, headEnd)
            r.keepAlive = !hasConnectionClose(header, headEnd)
            r.bodyStart = headEnd
            return r
        }
    }

    private class Request {
        var isPost = false
        var isReady = false
        var contentLength = 0
        var keepAlive = true
        var bodyStart = 0
        fun reset(): Request {
            isPost = false; isReady = false; contentLength = 0; keepAlive = true; bodyStart = 0
            return this
        }
    }

    private companion object {
        const val BACKLOG = 4096
    }
}

// --- byte-level HTTP head helpers (ASCII, case-insensitive where HTTP requires) --------------

/** Offset just past the CRLFCRLF (or LFLF) that ends the request head in [buf][0, len), or -1. */
private fun indexOfHeadEnd(buf: ByteArray, len: Int): Int {
    var i = 3
    while (i < len) {
        if (buf[i] == '\n'.code.toByte() && buf[i - 1] == '\r'.code.toByte() &&
            buf[i - 2] == '\n'.code.toByte() && buf[i - 3] == '\r'.code.toByte()
        ) return i + 1
        i++
    }
    return -1
}

private fun regionMatches(buf: ByteArray, start: Int, s: String): Boolean {
    if (start + s.length > buf.size) return false
    for (k in s.indices) if (buf[start + k] != s[k].code.toByte()) return false
    return true
}

/** Scans the head for `Content-Length:` (case-insensitive name) and parses its value. */
private fun parseContentLength(buf: ByteArray, len: Int): Int {
    val name = "content-length:"
    var i = 0
    while (i < len) {
        if (headerNameMatches(buf, i, len, name)) {
            var p = i + name.length
            while (p < len && (buf[p] == ' '.code.toByte() || buf[p] == '\t'.code.toByte())) p++
            var v = 0
            while (p < len) {
                val c = buf[p]
                if (c < '0'.code.toByte() || c > '9'.code.toByte()) break
                v = v * 10 + (c - '0'.code.toByte())
                p++
            }
            return v
        }
        i++
    }
    return 0
}

private fun hasConnectionClose(buf: ByteArray, len: Int): Boolean {
    val name = "connection:"
    var i = 0
    while (i < len) {
        if (headerNameMatches(buf, i, len, name)) {
            var p = i + name.length
            while (p < len && (buf[p] == ' '.code.toByte() || buf[p] == '\t'.code.toByte())) p++
            return regionMatchesIgnoreCase(buf, p, "close")
        }
        i++
    }
    return false
}

/** True if [buf] at [i] is the start of a header line whose (case-insensitive) name == [lower]. */
private fun headerNameMatches(buf: ByteArray, i: Int, len: Int, lower: String): Boolean {
    if (i + lower.length > len) return false
    // must be at the start of a line
    if (i != 0 && buf[i - 1] != '\n'.code.toByte()) return false
    for (k in lower.indices) if (toLower(buf[i + k]) != lower[k].code.toByte()) return false
    return true
}

private fun regionMatchesIgnoreCase(buf: ByteArray, start: Int, lower: String): Boolean {
    if (start + lower.length > buf.size) return false
    for (k in lower.indices) if (toLower(buf[start + k]) != lower[k].code.toByte()) return false
    return true
}

private fun toLower(b: Byte): Byte =
    if (b >= 'A'.code.toByte() && b <= 'Z'.code.toByte()) (b + 32).toByte() else b

/** Pre-rendered full HTTP/1.1 responses (status line + headers + body), built once. */
private object Responses {
    val READY_200: ByteArray = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
    val READY_503: ByteArray =
        "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)

    private val fraudResponses: Array<ByteArray> =
        Array(dev.santo.fraud.K_NEIGHBORS + 1) { count -> render(dev.santo.api.FraudResponses.forCount(count)) }

    fun fraud(count: Int): ByteArray = fraudResponses[count]

    private fun render(body: ByteArray): ByteArray {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
        return head + body
    }
}
