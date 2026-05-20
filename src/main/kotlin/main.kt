package dev.santo

import io.ktor.server.engine.*
import io.ktor.server.application.*

fun main(args: Array<String>) {
    embeddedServer(
        factory = io.ktor.server.cio.CIO,
        port = 8080,
        host = "0.0.0.0",
        module = Application::rootModule
    ).start(wait = true)
}
