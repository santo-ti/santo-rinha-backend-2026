package dev.santo

import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*

fun main() {
    val components = AppComponents.create()
    IndexLoader.loadAsync(components.indexState)
    embeddedServer(
        factory = CIO,
        port = 8080,
        host = "0.0.0.0",
        module = { rootModule(components) },
    ).start(wait = true)
}
