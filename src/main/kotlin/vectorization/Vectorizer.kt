package dev.santo.vectorization

import dev.santo.dto.FraudScoreRequest
import dev.santo.dto.LastTransaction
import dev.santo.dto.Transaction
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

// Tomohiko Sakamoto lookup; allocated once (not per call).
private val DOW_T = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
// Digit positions in "YYYY-MM-DDTHH:MM:SSZ".
private val DIGIT_POS = intArrayOf(0, 1, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15, 17, 18)

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

        v[0] = clamp(tx.amount / constants.maxAmount)
        v[1] = clamp(tx.installments / constants.maxInstallments)
        v[2] = clamp((tx.amount / request.customer.avgAmount) / constants.amountVsAvgRatio)
        fillTimeDimensions(v, tx, request.lastTransaction)

        v[7] = clamp(request.terminal.kmFromHome / constants.maxKm)
        v[8] = clamp(request.customer.txCount24h / constants.maxTxCount24h)
        v[9] = if (request.terminal.isOnline) 1.0 else 0.0
        v[10] = if (request.terminal.cardPresent) 1.0 else 0.0
        v[11] = if (request.merchant.id in request.customer.knownMerchants) 0.0 else 1.0
        v[12] = mccRisk.riskOf(request.merchant.mcc)
        v[13] = clamp(request.merchant.avgAmount / constants.maxMerchantAvgAmount)
        return v
    }

    /**
     * Dimensions 3 (hour/23), 4 (day-of-week/6, Mon=0), 5 (minutes since last tx)
     * and 6 (km from last tx). The fast path parses the fixed UTC timestamp
     * "YYYY-MM-DDTHH:MM:SSZ" with integer arithmetic (no `Instant`/`ZonedDateTime`
     * allocation on the hot path) and is bit-identical to the `Instant`-based
     * computation for that format. Any other shape falls back to `Instant.parse`,
     * so behavior is unchanged for non-conforming input.
     */
    private fun fillTimeDimensions(v: DoubleArray, tx: Transaction, last: LastTransaction?) {
        val req = tx.requestedAt
        if (isFixedUtc(req) && (last == null || isFixedUtc(last.timestamp))) {
            v[3] = d2(req, 11) / 23.0
            v[4] = dayOfWeekMon0(d4(req, 0), d2(req, 5), d2(req, 8)) / 6.0
            if (last == null) {
                v[5] = NO_HISTORY_SENTINEL
                v[6] = NO_HISTORY_SENTINEL
            } else {
                val minutes = (epochSecondsUtc(req) - epochSecondsUtc(last.timestamp)) / 60.0
                v[5] = clamp(minutes / constants.maxMinutes)
                v[6] = clamp(last.kmFromCurrent / constants.maxKm)
            }
        } else {
            val requestedAt = Instant.parse(req).atZone(ZoneOffset.UTC)
            v[3] = requestedAt.hour / 23.0
            v[4] = (requestedAt.dayOfWeek.value - 1) / 6.0
            if (last == null) {
                v[5] = NO_HISTORY_SENTINEL
                v[6] = NO_HISTORY_SENTINEL
            } else {
                val minutes = Duration.between(Instant.parse(last.timestamp), requestedAt.toInstant()).seconds / 60.0
                v[5] = clamp(minutes / constants.maxMinutes)
                v[6] = clamp(last.kmFromCurrent / constants.maxKm)
            }
        }
    }

    private fun clamp(x: Double): Double = x.coerceIn(0.0, 1.0)
}

/** True iff [s] is exactly the fixed UTC shape "YYYY-MM-DDTHH:MM:SSZ" with digits in place. */
private fun isFixedUtc(s: String): Boolean {
    if (s.length != 20) return false
    if (s[4] != '-' || s[7] != '-' || s[10] != 'T' || s[13] != ':' || s[16] != ':' || s[19] != 'Z') return false
    for (p in DIGIT_POS) if (s[p] < '0' || s[p] > '9') return false
    return true
}

private fun d2(s: String, i: Int): Int = (s[i] - '0') * 10 + (s[i + 1] - '0')
private fun d4(s: String, i: Int): Int =
    (s[i] - '0') * 1000 + (s[i + 1] - '0') * 100 + (s[i + 2] - '0') * 10 + (s[i + 3] - '0')

/** Sakamoto's algorithm, remapped to Monday=0..Sunday=6 (matches `DayOfWeek.value - 1`). */
private fun dayOfWeekMon0(year: Int, month: Int, day: Int): Int {
    val y = if (month < 3) year - 1 else year
    val dow = (y + y / 4 - y / 100 + y / 400 + DOW_T[month - 1] + day) % 7 // 0=Sun
    return (dow + 6) % 7
}

/** UTC epoch seconds for a fixed-format timestamp (Hinnant days-from-civil + time-of-day). */
private fun epochSecondsUtc(s: String): Long {
    val year = d4(s, 0); val month = d2(s, 5); val day = d2(s, 8)
    val hour = d2(s, 11); val minute = d2(s, 14); val second = d2(s, 17)
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era.toLong() * 146097 + doe.toLong() - 719468
    return days * 86400L + hour * 3600L + minute * 60L + second
}
