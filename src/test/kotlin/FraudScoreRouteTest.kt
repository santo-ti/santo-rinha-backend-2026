package dev.santo

import dev.santo.dto.FraudScoreResponse
import dev.santo.bootstrap.rootModule
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FraudScoreRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val validPayload = """
        {
          "id": "tx-1329056812",
          "transaction": { "amount": 41.12, "installments": 2, "requested_at": "2026-03-11T18:45:53Z" },
          "customer": { "avg_amount": 82.24, "tx_count_24h": 3, "known_merchants": ["MERC-003", "MERC-016"] },
          "merchant": { "id": "MERC-016", "mcc": "5411", "avg_amount": 60.25 },
          "terminal": { "is_online": false, "card_present": true, "km_from_home": 29.23 },
          "last_transaction": null
        }
    """.trimIndent()

    @Test
    fun `zero fraud neighbors approves with score 0`() = withIndex(FixedFraudIndex(0)) {
        val body = postFraudScore(validPayload).decoded()
        assertEquals(true, body.approved)
        assertEquals(0.0, body.fraudScore, 1e-9)
    }

    @Test
    fun `three of five fraud neighbors denies at the 0_6 boundary`() = withIndex(FixedFraudIndex(3)) {
        val body = postFraudScore(validPayload).decoded()
        assertEquals(false, body.approved)
        assertEquals(0.6, body.fraudScore, 1e-9)
    }

    @Test
    fun `two of five fraud neighbors approves`() = withIndex(FixedFraudIndex(2)) {
        val body = postFraudScore(validPayload).decoded()
        assertEquals(true, body.approved)
        assertEquals(0.4, body.fraudScore, 1e-9)
    }

    @Test
    fun `index not ready falls back to 200 without 5xx`() = testApplication {
        application { rootModule(testComponents()) } // no index published
        val response = postFraudScore(validPayload)
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.decoded()
        assertEquals(true, body.approved)
        assertEquals(0.0, body.fraudScore, 1e-9)
    }

    @Test
    fun `internal error falls back to 200 without 5xx`() = withIndex(ThrowingIndex) {
        val response = postFraudScore(validPayload)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(true, response.decoded().approved)
    }

    @Test
    fun `malformed json falls back to 200 without 5xx`() = withIndex(FixedFraudIndex(5)) {
        val response = postFraudScore("{ this is not valid json")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.decoded().approved)
    }

    private fun withIndex(index: dev.santo.search.VectorIndex, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { rootModule(testComponents(index)) }
            block()
        }

    private suspend fun ApplicationTestBuilder.postFraudScore(payload: String): HttpResponse =
        client.post("/fraud-score") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

    private suspend fun HttpResponse.decoded(): FraudScoreResponse =
        json.decodeFromString(FraudScoreResponse.serializer(), bodyAsText())
}
