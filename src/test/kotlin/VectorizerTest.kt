package dev.santo

import dev.santo.model.Customer
import dev.santo.model.FraudScoreRequest
import dev.santo.model.LastTransaction
import dev.santo.model.Merchant
import dev.santo.model.Terminal
import dev.santo.model.Transaction
import dev.santo.vectorization.ReferenceResources
import dev.santo.vectorization.Vectorizer
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

    private fun baseRequest() = FraudScoreRequest(
        id = "tx-test",
        transaction = Transaction(amount = 41.12, installments = 2, requestedAt = "2026-03-11T18:45:53Z"),
        customer = Customer(avgAmount = 82.24, txCount24h = 3, knownMerchants = listOf("MERC-003", "MERC-016")),
        merchant = Merchant(id = "MERC-016", mcc = "5411", avgAmount = 60.25),
        terminal = Terminal(isOnline = false, cardPresent = true, kmFromHome = 29.23),
        lastTransaction = null,
    )
}
