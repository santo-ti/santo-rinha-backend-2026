package dev.santo.bootstrap

import dev.santo.api.configureFraudScore
import dev.santo.api.configureRouting
import dev.santo.fraud.FraudDetectorService
import io.ktor.server.application.Application

fun Application.rootModule(components: AppComponents = AppComponents.create()) {
    val service = FraudDetectorService(components.vectorizer, components.byteVectorizer, components.indexState)
    configureRouting(components)
    configureFraudScore(service)
}
