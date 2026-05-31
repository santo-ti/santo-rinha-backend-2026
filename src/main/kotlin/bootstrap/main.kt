package dev.santo.bootstrap

import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.*

/**
 * Binds a Unix domain socket when `SERVER_SOCKET_PATH` is set (the submission: HAProxy
 * forwards over a shared unix socket, skipping the TCP/IP stack on the LB→API hop — a
 * real p99 cut), otherwise plain TCP on 8080 (local dev + the native-image tracing agent).
 * Unix sockets on the CIO server engine require Ktor ≥ 3.2.
 */
fun main() {
    val components = AppComponents.create()
    IndexLoader.loadAsync(components.indexState)
    val socketPath = System.getenv("SERVER_SOCKET_PATH")
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
