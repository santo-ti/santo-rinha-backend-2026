package dev.santo.bootstrap

import dev.santo.fraud.FraudDetectorService
import dev.santo.server.RawHttpServer
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.*

/**
 * Two HTTP engines, selected by `SERVER_ENGINE`:
 *  - `nio` → [RawHttpServer]: a hand-rolled HTTP/1.1 server (virtual-thread-per-connection,
 *    pre-rendered responses, zero framework on the hot path) — the request-edge p99 win.
 *  - anything else (default) → Ktor CIO (the proven fallback).
 *
 * Either engine binds a Unix domain socket when `SERVER_SOCKET_PATH` is set (HAProxy forwards
 * over a shared unix socket, skipping the TCP/IP stack on the LB→API hop), else TCP `:8080`
 * (local dev + the native-image tracing agent). CIO unix sockets require Ktor ≥ 3.2.
 */
fun main() {
    val components = AppComponents.create()
    IndexLoader.loadAsync(components.indexState)
    val socketPath = System.getenv("SERVER_SOCKET_PATH")

    if (System.getenv("SERVER_ENGINE") == "nio") {
        val service = FraudDetectorService(components.vectorizer, components.byteVectorizer, components.indexState)
        RawHttpServer(service, components.indexState).start(socketPath)
        return
    }

    embeddedServer(
        factory = CIO,
        configure = {
            if (socketPath.isNullOrBlank()) {
                connector { port = 8080; host = "0.0.0.0" }
            } else {
                unixConnector(socketPath)
            }
        },
        module = { rootModule(components) },
    ).start(wait = true)
}
