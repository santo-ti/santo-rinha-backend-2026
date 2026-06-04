package dev.santo.bootstrap

import dev.santo.fraud.FraudDetectorService
import dev.santo.server.NioReactorServer
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.*

/**
 * HTTP engine selected by `SERVER_ENGINE`:
 *  - `reactor` → [NioReactorServer]: single-thread non-blocking NIO reactor (no framework on the
 *    hot path) — the fast entries' design, to beat Ktor CIO's ~17ms serving floor.
 *  - anything else (default) → Ktor CIO (the proven fallback).
 *
 * Either binds a Unix socket when `SERVER_SOCKET_PATH` is set, else TCP `:8080`.
 */
fun main() {
    val components = AppComponents.create()
    IndexLoader.loadAsync(components.indexState)
    val socketPath = System.getenv("SERVER_SOCKET_PATH")

    if (System.getenv("SERVER_ENGINE") == "reactor") {
        val service = FraudDetectorService(components.vectorizer, components.byteVectorizer, components.indexState)
        NioReactorServer(service, components.indexState).start(socketPath)
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
