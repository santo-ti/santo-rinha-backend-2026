package dev.santo.bootstrap

import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.*

/**
 * Binds a Unix domain socket when `SERVER_SOCKET_PATH` is set, otherwise plain TCP on 8080
 * (local dev + the native-image tracing agent). Unix sockets on CIO require Ktor ≥ 3.2.
 *
 * CIO thread-pool sizes are env-tunable (`CIO_CONN_GROUP`/`CIO_WORKER_GROUP`/`CIO_CALL_GROUP`).
 * By default Ktor sizes them from `availableProcessors()`, which under a NanoCpus quota (no
 * cpuset) reports the HOST core count — so on a many-core host the engine spawns far more
 * threads than the 0.45-CPU budget can run, and they thrash the scheduler (the prime suspect
 * for the ~17ms serving-bound p99). Pinning them small matches the pool to the CPU budget.
 */
fun main() {
    val components = AppComponents.create()
    IndexLoader.loadAsync(components.indexState)
    val socketPath = System.getenv("SERVER_SOCKET_PATH")
    embeddedServer(
        factory = CIO,
        configure = {
            System.getenv("CIO_CONN_GROUP")?.toIntOrNull()?.let { connectionGroupSize = it }
            System.getenv("CIO_WORKER_GROUP")?.toIntOrNull()?.let { workerGroupSize = it }
            System.getenv("CIO_CALL_GROUP")?.toIntOrNull()?.let { callGroupSize = it }
            if (socketPath.isNullOrBlank()) {
                connector { port = 8080; host = "0.0.0.0" }
            } else {
                unixConnector(socketPath)
            }
        },
        module = { rootModule(components) },
    ).start(wait = true)
}
