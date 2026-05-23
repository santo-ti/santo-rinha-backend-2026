package dev.santo.vectorization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Normalization constants loaded from `normalization.json`. */
@Serializable
data class NormalizationConstants(
    @SerialName("max_amount") val maxAmount: Double,
    @SerialName("max_installments") val maxInstallments: Double,
    @SerialName("amount_vs_avg_ratio") val amountVsAvgRatio: Double,
    @SerialName("max_minutes") val maxMinutes: Double,
    @SerialName("max_km") val maxKm: Double,
    @SerialName("max_tx_count_24h") val maxTxCount24h: Double,
    @SerialName("max_merchant_avg_amount") val maxMerchantAvgAmount: Double,
)

/** MCC -> risk score, with a default for codes absent from the table. */
class MccRiskTable(private val risks: Map<String, Double>) {
    fun riskOf(mcc: String): Double = risks[mcc] ?: DEFAULT_RISK

    companion object {
        const val DEFAULT_RISK: Double = 0.5
    }
}

/** Loads the static reference resources bundled on the classpath. */
object ReferenceResources {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadNormalization(): NormalizationConstants =
        json.decodeFromString(readResource("/normalization.json"))

    fun loadMccRisk(): MccRiskTable =
        MccRiskTable(json.decodeFromString(readResource("/mcc_risk.json")))

    private fun readResource(path: String): String =
        ReferenceResources::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Resource not found on classpath: $path")
}
