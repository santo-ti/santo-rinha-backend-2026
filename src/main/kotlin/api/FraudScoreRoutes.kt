package dev.santo.api

import dev.santo.api.dto.FraudScoreRequest
import dev.santo.api.dto.FraudScoreResponse
import dev.santo.bootstrap.AppComponents
import dev.santo.fraud.FraudDetectorService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

// Explicit, compile-time serializers (no reflection, no ContentNegotiation) — leaner
// on the hot path and friendly to GraalVM native-image.
private val json = Json { ignoreUnknownKeys = true }
private val fallbackJson = json.encodeToString(FraudScoreResponse.serializer(), FraudDetectorService.FALLBACK)

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
            val body = try {
                val request = json.decodeFromString(FraudScoreRequest.serializer(), call.receiveText())
                json.encodeToString(FraudScoreResponse.serializer(), service.evaluate(request))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Never surface a 5xx: an HTTP error weighs 5 and counts as a failure,
                // which is worse than any single detection error. Answer fast and safe.
                fallbackJson
            }
            call.respondText(body, ContentType.Application.Json)
        }
    }
}
