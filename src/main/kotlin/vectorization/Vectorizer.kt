package dev.santo.vectorization

import dev.santo.api.dto.FraudScoreRequest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Number of dimensions in a detection vector. */
const val VECTOR_DIMENSIONS = 14

/**
 * Sentinel value for indices 5 and 6 when the transaction has no previous
 * transaction (`last_transaction: null`). It is the only value allowed
 * outside the `[0.0, 1.0]` range.
 */
const val NO_HISTORY_SENTINEL = -1.0

/**
 * Transforms a transaction payload into the 14-dimension detection vector,
 * following the order and normalization rules from the official Rinha de Backend
 * 2026 challenge spec `REGRAS_DE_DETECCAO.md`:
 * https://github.com/zanfranceschi/rinha-de-backend-2026/blob/main/docs/br/REGRAS_DE_DETECCAO.md
 */
class Vectorizer(
    private val constants: NormalizationConstants,
    private val mccRisk: MccRiskTable,
) {
    fun vectorize(request: FraudScoreRequest): DoubleArray {
        val v = DoubleArray(VECTOR_DIMENSIONS)
        val tx = request.transaction
        val requestedAt = Instant.parse(tx.requestedAt).atZone(ZoneOffset.UTC)

        v[0] = clamp(tx.amount / constants.maxAmount)
        v[1] = clamp(tx.installments / constants.maxInstallments)
        v[2] = clamp((tx.amount / request.customer.avgAmount) / constants.amountVsAvgRatio)
        v[3] = requestedAt.hour / 23.0
        v[4] = (requestedAt.dayOfWeek.value - 1) / 6.0 // Mon=0 .. Sun=6

        val last = request.lastTransaction
        if (last == null) {
            v[5] = NO_HISTORY_SENTINEL
            v[6] = NO_HISTORY_SENTINEL
        } else {
            val minutes = Duration.between(Instant.parse(last.timestamp), requestedAt.toInstant()).seconds / 60.0
            v[5] = clamp(minutes / constants.maxMinutes)
            v[6] = clamp(last.kmFromCurrent / constants.maxKm)
        }

        v[7] = clamp(request.terminal.kmFromHome / constants.maxKm)
        v[8] = clamp(request.customer.txCount24h / constants.maxTxCount24h)
        v[9] = if (request.terminal.isOnline) 1.0 else 0.0
        v[10] = if (request.terminal.cardPresent) 1.0 else 0.0
        v[11] = if (request.merchant.id in request.customer.knownMerchants) 0.0 else 1.0
        v[12] = mccRisk.riskOf(request.merchant.mcc)
        v[13] = clamp(request.merchant.avgAmount / constants.maxMerchantAvgAmount)
        return v
    }

    private fun clamp(x: Double): Double = x.coerceIn(0.0, 1.0)
}
