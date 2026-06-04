package dev.santo.server

import dev.santo.fraud.FraudDetectorService
import dev.santo.search.IndexState
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Single-thread non-blocking NIO reactor for the two contest routes (env `SERVER_ENGINE=reactor`).
 * This is the design the fast entries use (santannaf ~5.6ms, lucasmontano ~0.4ms): one [Selector]
 * thread, non-blocking channels, per-connection state machines. It replaces the virtual-thread
 * server (which thrashed on GraalVM native at 70ms) and the Ktor CIO engine (whose per-request
 * scheduling under the 0.45-CPU quota floored us at ~17ms).
 *
 * One thread does everything — accept, read, parse, search, write — so there are no locks, no
 * context switches and no thread oversubscription. The search is microseconds, so running it
 * inline never stalls the loop. Per connection: a growable read array accumulates bytes until a
 * full request (head + Content-Length body) is present; the request is answered with a pre-rendered
 * full HTTP response appended to an output array; partial writes finish under OP_WRITE. Keep-alive
 * connections stay registered for the next request. Binds TCP `:8080` or a Unix socket
 * (`SERVER_SOCKET_PATH`). No allocation on the steady-state request path beyond response bytes.
 */
class NioReactorServer(
    private val service: FraudDetectorService,
    private val indexState: IndexState,
) {
    fun start(socketPath: String?, tcpPort: Int = 8080) = serve(open(socketPath, tcpPort))

    /** Runs the selector loop on the current thread until interrupted/closed. */
    internal fun serve(server: ServerSocketChannel) {
        val selector = Selector.open()
        server.configureBlocking(false)
        server.register(selector, SelectionKey.OP_ACCEPT)
        while (true) {
            selector.select()
            val keys = selector.selectedKeys()
            val it = keys.iterator()
            while (it.hasNext()) {
                val key = it.next()
                it.remove()
                if (!key.isValid) continue
                try {
                    if (key.isAcceptable) {
                        onAccept(server, selector)
                    } else {
                        if (key.isReadable) onRead(key)
                        if (key.isValid && key.isWritable) flush(key)
                    }
                } catch (_: Throwable) {
                    closeKey(key)
                }
            }
        }
    }

    internal fun open(socketPath: String?, tcpPort: Int): ServerSocketChannel =
        if (socketPath.isNullOrBlank()) {
            ServerSocketChannel.open().apply { bind(InetSocketAddress("0.0.0.0", tcpPort), BACKLOG) }
        } else {
            val path = Path.of(socketPath)
            Files.deleteIfExists(path) // clear a stale socket from a previous run so bind() succeeds
            ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                .apply { bind(UnixDomainSocketAddress.of(socketPath), BACKLOG) }
                // The LB runs in a SEPARATE container and must connect to this socket. The
                // default umask can leave it owner-only; widen to 0666 so HAProxy reaches it
                // regardless of the user it runs as (the operational detail the Ktor UDS attempt
                // missed — it failed the contest health check with "No status", see #7683).
                .also { runCatching { Files.setPosixFilePermissions(path, SOCKET_PERMS) } }
        }

    private fun onAccept(server: ServerSocketChannel, selector: Selector) {
        while (true) {
            val ch = server.accept() ?: return
            ch.configureBlocking(false)
            try { ch.setOption(StandardSocketOptions.TCP_NODELAY, true) } catch (_: Throwable) {}
            ch.register(selector, SelectionKey.OP_READ, Conn())
        }
    }

    private fun onRead(key: SelectionKey) {
        val ch = key.channel() as SocketChannel
        val conn = key.attachment() as Conn
        if (conn.rlen == conn.rb.size) conn.growRead()
        val n = ch.read(ByteBuffer.wrap(conn.rb, conn.rlen, conn.rb.size - conn.rlen))
        if (n < 0) { closeKey(key); return }
        if (n == 0) return
        conn.rlen += n
        process(conn)
        flush(key)
    }

    /** Parse and answer every complete request currently buffered, then compact the read array. */
    private fun process(conn: Conn) {
        val rb = conn.rb
        var start = 0
        while (true) {
            val headEnd = indexOfHeadEnd(rb, start, conn.rlen)
            if (headEnd < 0) break // head incomplete — wait for more bytes
            if (rb[start] == 'P'.code.toByte()) { // POST /fraud-score
                val cl = parseContentLength(rb, start, headEnd)
                if (conn.rlen - headEnd < cl) break // body incomplete — wait
                val count = try { service.fraudCountOf(rb, headEnd, headEnd + cl) } catch (_: Throwable) { 0 }
                conn.append(Responses.fraud(count))
                start = headEnd + cl
            } else { // GET /ready (or anything else → safe)
                val resp = when {
                    !pathIsReady(rb, start) -> Responses.fraud(0)
                    indexState.isReady -> Responses.READY_200
                    else -> Responses.READY_503
                }
                conn.append(resp)
                start = headEnd
            }
        }
        if (start > 0) {
            System.arraycopy(rb, start, rb, 0, conn.rlen - start)
            conn.rlen -= start
        }
    }

    /** Write as much of the pending output as the socket accepts; arm OP_WRITE if it didn't drain. */
    private fun flush(key: SelectionKey) {
        val ch = key.channel() as SocketChannel
        val conn = key.attachment() as Conn
        while (conn.opos < conn.olen) {
            val n = ch.write(ByteBuffer.wrap(conn.ob, conn.opos, conn.olen - conn.opos))
            if (n == 0) break // socket send buffer full — finish later under OP_WRITE
            conn.opos += n
        }
        if (conn.opos >= conn.olen) {
            conn.opos = 0; conn.olen = 0
            key.interestOps(SelectionKey.OP_READ)
        } else {
            key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
        }
    }

    private fun closeKey(key: SelectionKey) {
        try { key.channel().close() } catch (_: Throwable) {}
        key.cancel()
    }

    /** Per-connection state: growable read + output arrays, reused across keep-alive requests. */
    private class Conn {
        var rb = ByteArray(8 * 1024); var rlen = 0
        var ob = ByteArray(8 * 1024); var olen = 0; var opos = 0

        fun growRead() { if (rb.size < MAX_BUF) rb = rb.copyOf(rb.size * 2) }

        fun append(resp: ByteArray) {
            if (olen + resp.size > ob.size) {
                var cap = ob.size * 2
                while (cap < olen + resp.size) cap *= 2
                ob = ob.copyOf(cap)
            }
            System.arraycopy(resp, 0, ob, olen, resp.size)
            olen += resp.size
        }
    }

    private companion object {
        const val BACKLOG = 4096
        const val MAX_BUF = 256 * 1024
        // rw-rw-rw-: the cross-container LB must be able to connect to the bound UDS.
        val SOCKET_PERMS = PosixFilePermissions.fromString("rw-rw-rw-")
    }
}

// --- byte-level HTTP head parsing over buf[start until len] ---------------------------------

/** End offset (just past CRLFCRLF) of the request head starting at [start], or -1 if incomplete. */
private fun indexOfHeadEnd(buf: ByteArray, start: Int, len: Int): Int {
    var i = start + 3
    while (i < len) {
        if (buf[i] == '\n'.code.toByte() && buf[i - 1] == '\r'.code.toByte() &&
            buf[i - 2] == '\n'.code.toByte() && buf[i - 3] == '\r'.code.toByte()
        ) return i + 1
        i++
    }
    return -1
}

/** True if the request line at [start] targets "/ready" (the path follows the first space). */
private fun pathIsReady(buf: ByteArray, start: Int): Boolean {
    var i = start
    while (i < buf.size && buf[i] != ' '.code.toByte()) i++
    val p = i + 1
    val s = "/ready"
    if (p + s.length > buf.size) return false
    for (k in s.indices) if (buf[p + k] != s[k].code.toByte()) return false
    return true
}

/** Parses the `Content-Length` header value within the head [start, end). 0 if absent. */
private fun parseContentLength(buf: ByteArray, start: Int, end: Int): Int {
    val name = "content-length:"
    var i = start
    while (i < end) {
        if ((i == start || buf[i - 1] == '\n'.code.toByte()) && nameMatches(buf, i, end, name)) {
            var p = i + name.length
            while (p < end && (buf[p] == ' '.code.toByte() || buf[p] == '\t'.code.toByte())) p++
            var v = 0
            while (p < end) {
                val c = buf[p]
                if (c < '0'.code.toByte() || c > '9'.code.toByte()) break
                v = v * 10 + (c - '0'.code.toByte()); p++
            }
            return v
        }
        i++
    }
    return 0
}

private fun nameMatches(buf: ByteArray, i: Int, end: Int, lower: String): Boolean {
    if (i + lower.length > end) return false
    for (k in lower.indices) {
        var b = buf[i + k]
        if (b >= 'A'.code.toByte() && b <= 'Z'.code.toByte()) b = (b + 32).toByte()
        if (b != lower[k].code.toByte()) return false
    }
    return true
}

/** Pre-rendered full HTTP/1.1 responses (status line + headers + body), built once. */
private object Responses {
    val READY_200: ByteArray = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
    val READY_503: ByteArray =
        "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)

    private val fraudResponses: Array<ByteArray> =
        Array(dev.santo.fraud.K_NEIGHBORS + 1) { count ->
            val body = dev.santo.api.FraudResponses.forCount(count)
            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n")
                .toByteArray(Charsets.US_ASCII) + body
        }

    fun fraud(count: Int): ByteArray = fraudResponses[count]
}
