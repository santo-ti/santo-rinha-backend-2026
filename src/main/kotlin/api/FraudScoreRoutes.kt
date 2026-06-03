package dev.santo.api

import dev.santo.bootstrap.AppComponents
import dev.santo.fraud.FraudDetectorService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException

fun Application.configureRouting(components: AppComponents) {
    routing {
        get("/ready") {
            val status = if (components.indexState.isReady) {
                HttpStatusCode.OK
            } else {
                HttpStatusCode.ServiceUnavailable
            }
            call.respond(status)
        }
    }
}

fun Application.configureFraudScore(service: FraudDetectorService) {
    routing {
        post("/fraud-score") {
            val body: ByteArray = try {
                // Raw bytes → byte-scan vectorizer (no String/DTO/ContentNegotiation allocation).
                val bytes = call.receive<ByteArray>()
                FraudResponses.forCount(service.fraudCountOf(bytes, bytes.size))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Never surface a 5xx: an HTTP error weighs 5 and counts as a failure,
                // which is worse than any single detection error. Answer fast and safe.
                FraudResponses.FALLBACK
            }
            call.respondBytes(body, ContentType.Application.Json)
        }
    }
}
