package dev.santo.vectorization

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * DTO-free vectorizer: scans the raw request bytes once and writes the 14-dimension detection
 * vector into a pooled per-thread scratch, skipping the kotlinx.serialization object graph
 * (5 data classes + the `known_merchants` list + every String field) that the [Vectorizer]
 * path allocates per request. Removing that young-gen churn is the request-edge win that trims
 * the GC-driven p99 tail (the search itself is unchanged).
 *
 * It is a strict drop-in for [Vectorizer]: numbers are parsed with the SAME [java.lang.Double]
 * round-tripping (the exact numeric substring → `parseDouble`), so the produced doubles — and
 * therefore the quantized int16 codes the search consumes — are bit-identical to the DTO path.
 * That equivalence is pinned by `ByteVectorizerTest` (quantized codes must match on every
 * example payload), so the proven 0-error detection is preserved by construction.
 *
 * The scan is schema-aware but key-checked (not positional): each object's fields are matched
 * by name, so it is robust to key order and to either compact or whitespaced JSON. Only the
 * `mcc` lookup key allocates a small String; `merchant.id ∈ known_merchants` is decided by
 * comparing byte ranges in place, and the returned vector reuses the thread-local array (the
 * caller quantizes it synchronously before any suspension, so it never escapes).
 */
class ByteVectorizer(
    private val constants: NormalizationConstants,
    private val mccRisk: MccRiskTable,
) {
    private val scratch = ThreadLocal.withInitial { Scratch() }

    fun vectorize(buf: ByteArray, len: Int): DoubleArray {
        val s = scratch.get().also { it.reset(buf) }
        parseTopObject(buf, skipWs(buf, 0, len), len, s)
        return compute(s)
    }

    // --- top-level + section dispatch -------------------------------------------------------

    private fun parseTopObject(buf: ByteArray, from: Int, len: Int, s: Scratch): Int {
        var p = from + 1 // past '{'
        while (true) {
            p = skipWs(buf, p, len)
            if (buf[p] == '}'.code.toByte()) return p + 1
            p = parseStringRange(buf, p, len, s)
            val ks = s.strStart; val kl = s.strLen
            p = skipWs(buf, p, len); p++ // ':'
            p = skipWs(buf, p, len)
            p = when {
                keyEq(buf, ks, kl, "transaction") -> parseSection(buf, p, len, s, Section.TX)
                keyEq(buf, ks, kl, "customer") -> parseSection(buf, p, len, s, Section.CUSTOMER)
                keyEq(buf, ks, kl, "merchant") -> parseSection(buf, p, len, s, Section.MERCHANT)
                keyEq(buf, ks, kl, "terminal") -> parseSection(buf, p, len, s, Section.TERMINAL)
                keyEq(buf, ks, kl, "last_transaction") ->
                    if (buf[p] == 'n'.code.toByte()) p + 4 // null
                    else { s.hasLast = true; parseSection(buf, p, len, s, Section.LAST) }
                else -> skipValue(buf, p, len, s)
            }
            p = skipWs(buf, p, len)
            if (buf[p] == ','.code.toByte()) p++ else return p + 1 // '}'
        }
    }

    private fun parseSection(buf: ByteArray, from: Int, len: Int, s: Scratch, section: Section): Int {
        var p = from + 1 // past '{'
        while (true) {
            p = skipWs(buf, p, len)
            if (buf[p] == '}'.code.toByte()) return p + 1
            p = parseStringRange(buf, p, len, s)
            val ks = s.strStart; val kl = s.strLen
            p = skipWs(buf, p, len); p++ // ':'
            p = skipWs(buf, p, len)
            p = captureField(buf, p, len, s, section, ks, kl)
            p = skipWs(buf, p, len)
            if (buf[p] == ','.code.toByte()) p++ else return p + 1 // '}'
        }
    }

    private fun captureField(buf: ByteArray, p: Int, len: Int, s: Scratch, section: Section, ks: Int, kl: Int): Int =
        when (section) {
            Section.TX -> when {
                keyEq(buf, ks, kl, "amount") -> parseNumber(buf, p, len, s).also { s.amount = s.num }
                keyEq(buf, ks, kl, "installments") -> parseNumber(buf, p, len, s).also { s.installments = s.num }
                keyEq(buf, ks, kl, "requested_at") -> parseStringRange(buf, p, len, s).also { s.reqStart = s.strStart; s.reqLen = s.strLen }
                else -> skipValue(buf, p, len, s)
            }
            Section.CUSTOMER -> when {
                keyEq(buf, ks, kl, "avg_amount") -> parseNumber(buf, p, len, s).also { s.custAvg = s.num }
                keyEq(buf, ks, kl, "tx_count_24h") -> parseNumber(buf, p, len, s).also { s.txCount = s.num }
                keyEq(buf, ks, kl, "known_merchants") -> parseKnownMerchants(buf, p, len, s)
                else -> skipValue(buf, p, len, s)
            }
            Section.MERCHANT -> when {
                keyEq(buf, ks, kl, "id") -> parseStringRange(buf, p, len, s).also { s.midStart = s.strStart; s.midLen = s.strLen }
                keyEq(buf, ks, kl, "mcc") -> parseStringRange(buf, p, len, s).also { s.mccStart = s.strStart; s.mccLen = s.strLen }
                keyEq(buf, ks, kl, "avg_amount") -> parseNumber(buf, p, len, s).also { s.merchAvg = s.num }
                else -> skipValue(buf, p, len, s)
            }
            Section.TERMINAL -> when {
                keyEq(buf, ks, kl, "is_online") -> parseBool(buf, p, len, s).also { s.isOnline = s.boolVal }
                keyEq(buf, ks, kl, "card_present") -> parseBool(buf, p, len, s).also { s.cardPresent = s.boolVal }
                keyEq(buf, ks, kl, "km_from_home") -> parseNumber(buf, p, len, s).also { s.kmHome = s.num }
                else -> skipValue(buf, p, len, s)
            }
            Section.LAST -> when {
                keyEq(buf, ks, kl, "timestamp") -> parseStringRange(buf, p, len, s).also { s.lastStart = s.strStart; s.lastLen = s.strLen }
                keyEq(buf, ks, kl, "km_from_current") -> parseNumber(buf, p, len, s).also { s.lastKm = s.num }
                else -> skipValue(buf, p, len, s)
            }
        }

    private fun parseKnownMerchants(buf: ByteArray, from: Int, len: Int, s: Scratch): Int {
        var p = from + 1 // past '['
        while (true) {
            p = skipWs(buf, p, len)
            if (buf[p] == ']'.code.toByte()) return p + 1
            p = parseStringRange(buf, p, len, s)
            if (s.kmCount < s.kmStart.size) {
                s.kmStart[s.kmCount] = s.strStart
                s.kmLen[s.kmCount] = s.strLen
                s.kmCount++
            }
            p = skipWs(buf, p, len)
            if (buf[p] == ','.code.toByte()) p++ else return p + 1 // ']'
        }
    }

    // --- scalar token scanners --------------------------------------------------------------

    private fun parseStringRange(buf: ByteArray, from: Int, len: Int, s: Scratch): Int {
        var p = from + 1 // past opening quote
        s.strStart = p
        while (p < len) {
            val c = buf[p]
            if (c == '\\'.code.toByte()) { p += 2; continue }
            if (c == '"'.code.toByte()) break
            p++
        }
        s.strLen = p - s.strStart
        return p + 1 // past closing quote
    }

    private fun parseNumber(buf: ByteArray, from: Int, len: Int, s: Scratch): Int {
        var p = from
        while (p < len) {
            val c = buf[p].toInt().toChar()
            if (c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') p++ else break
        }
        s.num = java.lang.Double.parseDouble(String(buf, from, p - from, Charsets.US_ASCII))
        return p
    }

    private fun parseBool(buf: ByteArray, from: Int, len: Int, s: Scratch): Int {
        if (buf[from] == 't'.code.toByte()) { s.boolVal = true; return from + 4 }
        s.boolVal = false; return from + 5
    }

    /** Skips an arbitrary JSON value (string/number/bool/null/object/array) — unknown keys. */
    private fun skipValue(buf: ByteArray, from: Int, len: Int, s: Scratch): Int {
        val c = buf[from]
        return when {
            c == '"'.code.toByte() -> parseStringRange(buf, from, len, s)
            c == '{'.code.toByte() || c == '['.code.toByte() -> skipContainer(buf, from, len)
            else -> { var p = from; while (p < len && !isValueEnd(buf[p])) p++; p }
        }
    }

    private fun skipContainer(buf: ByteArray, from: Int, len: Int): Int {
        var p = from
        var depth = 0
        while (p < len) {
            val c = buf[p]
            when (c) {
                '"'.code.toByte() -> { p = skipRawString(buf, p, len); continue }
                '{'.code.toByte(), '['.code.toByte() -> depth++
                '}'.code.toByte(), ']'.code.toByte() -> { depth--; if (depth == 0) return p + 1 }
            }
            p++
        }
        return p
    }

    private fun skipRawString(buf: ByteArray, from: Int, len: Int): Int {
        var p = from + 1
        while (p < len) {
            val c = buf[p]
            if (c == '\\'.code.toByte()) { p += 2; continue }
            if (c == '"'.code.toByte()) return p + 1
            p++
        }
        return p
    }

    // --- vector assembly (mirrors Vectorizer; the test pins the equivalence) ----------------

    private fun compute(s: Scratch): DoubleArray {
        val v = s.out
        v[0] = clamp(s.amount / constants.maxAmount)
        v[1] = clamp(s.installments / constants.maxInstallments)
        v[2] = clamp((s.amount / s.custAvg) / constants.amountVsAvgRatio)
        fillTimeDimensions(v, s)
        v[7] = clamp(s.kmHome / constants.maxKm)
        v[8] = clamp(s.txCount / constants.maxTxCount24h)
        v[9] = if (s.isOnline) 1.0 else 0.0
        v[10] = if (s.cardPresent) 1.0 else 0.0
        v[11] = if (merchantIsKnown(s)) 0.0 else 1.0
        v[12] = mccRisk.riskOf(String(s.buf, s.mccStart, s.mccLen, Charsets.US_ASCII))
        v[13] = clamp(s.merchAvg / constants.maxMerchantAvgAmount)
        return v
    }

    private fun merchantIsKnown(s: Scratch): Boolean {
        for (i in 0 until s.kmCount) {
            if (s.kmLen[i] == s.midLen && rangesEqual(s.buf, s.kmStart[i], s.midStart, s.midLen)) return true
        }
        return false
    }

    private fun fillTimeDimensions(v: DoubleArray, s: Scratch) {
        val buf = s.buf
        val reqFixed = isFixedUtc(buf, s.reqStart, s.reqLen)
        val lastFixed = !s.hasLast || isFixedUtc(buf, s.lastStart, s.lastLen)
        if (reqFixed && lastFixed) {
            v[3] = d2(buf, s.reqStart + 11) / 23.0
            v[4] = dayOfWeekMon0(d4(buf, s.reqStart), d2(buf, s.reqStart + 5), d2(buf, s.reqStart + 8)) / 6.0
            if (!s.hasLast) {
                v[5] = NO_HISTORY_SENTINEL
                v[6] = NO_HISTORY_SENTINEL
            } else {
                val minutes = (epochSecondsUtc(buf, s.reqStart) - epochSecondsUtc(buf, s.lastStart)) / 60.0
                v[5] = clamp(minutes / constants.maxMinutes)
                v[6] = clamp(s.lastKm / constants.maxKm)
            }
        } else {
            val req = Instant.parse(String(buf, s.reqStart, s.reqLen, Charsets.US_ASCII)).atZone(ZoneOffset.UTC)
            v[3] = req.hour / 23.0
            v[4] = (req.dayOfWeek.value - 1) / 6.0
            if (!s.hasLast) {
                v[5] = NO_HISTORY_SENTINEL
                v[6] = NO_HISTORY_SENTINEL
            } else {
                val last = Instant.parse(String(buf, s.lastStart, s.lastLen, Charsets.US_ASCII))
                v[5] = clamp(Duration.between(last, req.toInstant()).seconds / 60.0 / constants.maxMinutes)
                v[6] = clamp(s.lastKm / constants.maxKm)
            }
        }
    }

    private fun clamp(x: Double): Double = x.coerceIn(0.0, 1.0)

    private enum class Section { TX, CUSTOMER, MERCHANT, TERMINAL, LAST }

    private class Scratch {
        lateinit var buf: ByteArray
        val out = DoubleArray(VECTOR_DIMENSIONS)
        var amount = 0.0; var installments = 0.0; var custAvg = 0.0; var txCount = 0.0
        var merchAvg = 0.0; var kmHome = 0.0; var lastKm = 0.0
        var isOnline = false; var cardPresent = false; var hasLast = false
        var reqStart = 0; var reqLen = 0; var lastStart = 0; var lastLen = 0
        var midStart = 0; var midLen = 0; var mccStart = 0; var mccLen = 0
        val kmStart = IntArray(64); val kmLen = IntArray(64); var kmCount = 0
        // last-token outputs from the scanners
        var strStart = 0; var strLen = 0; var num = 0.0; var boolVal = false

        fun reset(buf: ByteArray) {
            this.buf = buf
            hasLast = false
            kmCount = 0
        }
    }
}

// --- byte-level helpers (ASCII) -------------------------------------------------------------

private fun skipWs(buf: ByteArray, from: Int, len: Int): Int {
    var p = from
    while (p < len) {
        val c = buf[p]
        if (c == ' '.code.toByte() || c == '\n'.code.toByte() || c == '\r'.code.toByte() || c == '\t'.code.toByte()) p++ else break
    }
    return p
}

private fun isValueEnd(c: Byte): Boolean =
    c == ','.code.toByte() || c == '}'.code.toByte() || c == ']'.code.toByte() ||
        c == ' '.code.toByte() || c == '\n'.code.toByte() || c == '\r'.code.toByte() || c == '\t'.code.toByte()

private fun keyEq(buf: ByteArray, start: Int, length: Int, name: String): Boolean {
    if (length != name.length) return false
    for (i in 0 until length) if (buf[start + i] != name[i].code.toByte()) return false
    return true
}

private fun rangesEqual(buf: ByteArray, aStart: Int, bStart: Int, length: Int): Boolean {
    for (i in 0 until length) if (buf[aStart + i] != buf[bStart + i]) return false
    return true
}

private fun d2(buf: ByteArray, i: Int): Int = (buf[i] - '0'.code.toByte()) * 10 + (buf[i + 1] - '0'.code.toByte())
private fun d4(buf: ByteArray, i: Int): Int =
    (buf[i] - '0'.code.toByte()) * 1000 + (buf[i + 1] - '0'.code.toByte()) * 100 +
        (buf[i + 2] - '0'.code.toByte()) * 10 + (buf[i + 3] - '0'.code.toByte())

/** True iff the [length]-byte range at [start] is the fixed UTC shape "YYYY-MM-DDTHH:MM:SSZ". */
private fun isFixedUtc(buf: ByteArray, start: Int, length: Int): Boolean {
    if (length != 20) return false
    if (buf[start + 4] != '-'.code.toByte() || buf[start + 7] != '-'.code.toByte() ||
        buf[start + 10] != 'T'.code.toByte() || buf[start + 13] != ':'.code.toByte() ||
        buf[start + 16] != ':'.code.toByte() || buf[start + 19] != 'Z'.code.toByte()
    ) return false
    for (p in TS_DIGIT_POS) {
        val c = buf[start + p]
        if (c < '0'.code.toByte() || c > '9'.code.toByte()) return false
    }
    return true
}

private val TS_DIGIT_POS = intArrayOf(0, 1, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15, 17, 18)
private val TS_DOW_T = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)

/** Sakamoto's algorithm remapped to Monday=0..Sunday=6 (matches `DayOfWeek.value - 1`). */
private fun dayOfWeekMon0(year: Int, month: Int, day: Int): Int {
    val y = if (month < 3) year - 1 else year
    val dow = (y + y / 4 - y / 100 + y / 400 + TS_DOW_T[month - 1] + day) % 7
    return (dow + 6) % 7
}

/** UTC epoch seconds for a fixed-format timestamp at [start] (Hinnant days-from-civil). */
private fun epochSecondsUtc(buf: ByteArray, start: Int): Long {
    val year = d4(buf, start); val month = d2(buf, start + 5); val day = d2(buf, start + 8)
    val hour = d2(buf, start + 11); val minute = d2(buf, start + 14); val second = d2(buf, start + 17)
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era.toLong() * 146097 + doe.toLong() - 719468
    return days * 86400L + hour * 3600L + minute * 60L + second
}
