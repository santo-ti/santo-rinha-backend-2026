package dev.santo.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Incoming transaction payload for `POST /fraud-score`. */
@Serializable
data class FraudScoreRequest(
    val id: String,
    val transaction: Transaction,
    val customer: Customer,
    val merchant: Merchant,
    val terminal: Terminal,
    @SerialName("last_transaction") val lastTransaction: LastTransaction? = null,
)

@Serializable
data class Transaction(
    val amount: Double,
    val installments: Int,
    @SerialName("requested_at") val requestedAt: String,
)

@Serializable
data class Customer(
    @SerialName("avg_amount") val avgAmount: Double,
    @SerialName("tx_count_24h") val txCount24h: Int,
    @SerialName("known_merchants") val knownMerchants: List<String>,
)

@Serializable
data class Merchant(
    val id: String,
    val mcc: String,
    @SerialName("avg_amount") val avgAmount: Double,
)

@Serializable
data class Terminal(
    @SerialName("is_online") val isOnline: Boolean,
    @SerialName("card_present") val cardPresent: Boolean,
    @SerialName("km_from_home") val kmFromHome: Double,
)

@Serializable
data class LastTransaction(
    val timestamp: String,
    @SerialName("km_from_current") val kmFromCurrent: Double,
)

/** Response body for `POST /fraud-score`. */
@Serializable
data class FraudScoreResponse(
    val approved: Boolean,
    @SerialName("fraud_score") val fraudScore: Double,
)
