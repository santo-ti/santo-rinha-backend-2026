package dev.santo

import io.ktor.server.application.Application

fun Application.rootModule(components: AppComponents = AppComponents.create()) {
    val service = FraudScoreService(components.vectorizer, components.indexState)
    configureRouting(components)
    configureFraudScore(service)
}
