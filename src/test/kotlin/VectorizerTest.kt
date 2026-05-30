package dev.santo

import dev.santo.dto.Customer
import dev.santo.dto.FraudScoreRequest
import dev.santo.dto.LastTransaction
import dev.santo.dto.Merchant
import dev.santo.dto.Terminal
import dev.santo.dto.Transaction
import dev.santo.vectorization.ReferenceResources
import dev.santo.vectorization.Vectorizer
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorizerTest {

    private val vectorizer = Vectorizer(
        ReferenceResources.loadNormalization(),
        ReferenceResources.loadMccRisk(),
    )

    private fun assertVector(expected: DoubleArray, actual: DoubleArray, delta: Double = 1e-3) {
        assertEquals(expected.size, actual.size, "vector size")
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], delta, "dimension $i")
        }
    }

    @Test
    fun `legit example from spec produces expected vector`() {
        // From the official challenge spec REGRAS_DE_DETECCAO.md, "Visão geral do fluxo":
        // https://github.com/zanfranceschi/rinha-de-backend-2026/blob/main/docs/br/REGRAS_DE_DETECCAO.md
        val request = FraudScoreRequest(
            id = "tx-1329056812",
            transaction = Transaction(amount = 41.12, installments = 2, requestedAt = "2026-03-11T18:45:53Z"),
            customer = Customer(avgAmount = 82.24, txCount24h = 3, knownMerchants = listOf("MERC-003", "MERC-016")),
            merchant = Merchant(id = "MERC-016", mcc = "5411", avgAmount = 60.25),
            terminal = Terminal(isOnline = false, cardPresent = true, kmFromHome = 29.23),
            lastTransaction = null,
        )

        val expected = doubleArrayOf(
            0.0041, 0.1667, 0.05, 0.7826, 0.3333, -1.0, -1.0, 0.0292, 0.15, 0.0, 1.0, 0.0, 0.15, 0.006,
        )
        assertVector(expected, vectorizer.vectorize(request))
    }

    @Test
    fun `amount above max is clamped to 1`() {
        val request = baseRequest().copy(
            transaction = Transaction(amount = 12500.0, installments = 2, requestedAt = "2026-03-11T18:45:53Z"),
        )
        assertEquals(1.0, vectorizer.vectorize(request)[0], 1e-9)
    }

    @Test
    fun `unknown mcc uses default risk`() {
        val request = baseRequest().copy(
            merchant = Merchant(id = "MERC-016", mcc = "9999", avgAmount = 60.25),
        )
        assertEquals(0.5, vectorizer.vectorize(request)[12], 1e-9)
    }

    @Test
    fun `known mcc uses table value`() {
        val request = baseRequest().copy(
            merchant = Merchant(id = "MERC-016", mcc = "7995", avgAmount = 60.25),
        )
        assertEquals(0.85, vectorizer.vectorize(request)[12], 1e-9)
    }

    @Test
    fun `unknown merchant flag is inverted`() {
        val unknown = baseRequest().copy(
            merchant = Merchant(id = "MERC-999", mcc = "5411", avgAmount = 60.25),
            customer = Customer(avgAmount = 82.24, txCount24h = 3, knownMerchants = listOf("MERC-003", "MERC-016")),
        )
        assertEquals(1.0, vectorizer.vectorize(unknown)[11], 1e-9)

        val known = baseRequest().copy(
            merchant = Merchant(id = "MERC-016", mcc = "5411", avgAmount = 60.25),
            customer = Customer(avgAmount = 82.24, txCount24h = 3, knownMerchants = listOf("MERC-003", "MERC-016")),
        )
        assertEquals(0.0, vectorizer.vectorize(known)[11], 1e-9)
    }

    @Test
    fun `null last transaction yields sentinel on indices 5 and 6`() {
        val v = vectorizer.vectorize(baseRequest().copy(lastTransaction = null))
        assertEquals(-1.0, v[5], 1e-9)
        assertEquals(-1.0, v[6], 1e-9)
    }

    @Test
    fun `present last transaction normalizes minutes and km`() {
        // From API.md example tx-3576980410: 14:58:35Z -> 20:23:35Z = 325 minutes.
        val request = baseRequest().copy(
            transaction = Transaction(amount = 384.88, installments = 3, requestedAt = "2026-03-11T20:23:35Z"),
            lastTransaction = LastTransaction(timestamp = "2026-03-11T14:58:35Z", kmFromCurrent = 18.8626479774),
        )
        val v = vectorizer.vectorize(request)
        assertEquals(325.0 / 1440.0, v[5], 1e-4)
        assertEquals(18.8626479774 / 1000.0, v[6], 1e-4)
    }

    @Test
    fun `fast timestamp path is bit-identical to the Instant-based computation`() {
        // The integer-arithmetic fast path (dims 3,4,5,6) must reproduce the old
        // Instant/Duration computation EXACTLY — a 1-bit drift would shift a
        // quantized code and could flip a k-NN decision. Sweep years (incl. leap
        // 2024/2028), every month, edge days/hours, and last_transaction offsets.
        val years = intArrayOf(2024, 2025, 2026, 2028, 2030, 2031)
        val days = intArrayOf(1, 15, 28)
        val hours = intArrayOf(0, 7, 12, 23)
        val offsetsSeconds = longArrayOf(60, 325 * 60, 3600, 86400, 86400L * 400, 86400L * 3650)
        var checked = 0
        for (y in years) for (mo in 1..12) for (d in days) for (h in hours) {
            val reqTs = "%04d-%02d-%02dT%02d:%02d:%02dZ".format(y, mo, d, h, 30, 15)
            val req = Instant.parse(reqTs).atZone(ZoneOffset.UTC)

            val vNull = vectorizer.vectorize(baseRequest().copy(
                transaction = Transaction(41.12, 2, reqTs), lastTransaction = null,
            ))
            assertEquals(req.hour / 23.0, vNull[3])
            assertEquals((req.dayOfWeek.value - 1) / 6.0, vNull[4])
            assertEquals(-1.0, vNull[5])
            assertEquals(-1.0, vNull[6])
            checked++

            for (off in offsetsSeconds) {
                val lastTs = Instant.ofEpochSecond(req.toInstant().epochSecond - off).toString()
                val v = vectorizer.vectorize(baseRequest().copy(
                    transaction = Transaction(41.12, 2, reqTs),
                    lastTransaction = LastTransaction(timestamp = lastTs, kmFromCurrent = 12.5),
                ))
                val expectedMinutes =
                    Duration.between(Instant.parse(lastTs), req.toInstant()).seconds / 60.0
                assertEquals(req.hour / 23.0, v[3])
                assertEquals((req.dayOfWeek.value - 1) / 6.0, v[4])
                assertEquals((expectedMinutes / 1440.0).coerceIn(0.0, 1.0), v[5])
                assertEquals((12.5 / 1000.0).coerceIn(0.0, 1.0), v[6])
                checked++
            }
        }
        assertEquals(6 * 12 * 3 * 4 * 7, checked, "sweep coverage")
    }

    private fun baseRequest() = FraudScoreRequest(
        id = "tx-test",
        transaction = Transaction(amount = 41.12, installments = 2, requestedAt = "2026-03-11T18:45:53Z"),
        customer = Customer(avgAmount = 82.24, txCount24h = 3, knownMerchants = listOf("MERC-003", "MERC-016")),
        merchant = Merchant(id = "MERC-016", mcc = "5411", avgAmount = 60.25),
        terminal = Terminal(isOnline = false, cardPresent = true, kmFromHome = 29.23),
        lastTransaction = null,
    )
}
