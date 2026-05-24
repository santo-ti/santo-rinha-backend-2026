package dev.santo.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response body for `POST /fraud-score`. */
@Serializable
data class FraudScoreResponse(
    val approved: Boolean,
    @SerialName("fraud_score") val fraudScore: Double,
)
