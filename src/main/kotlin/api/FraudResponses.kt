package dev.santo.api

import dev.santo.dto.FraudScoreResponse
import dev.santo.fraud.K_NEIGHBORS
import dev.santo.fraud.fraudScore
import dev.santo.fraud.isApproved
import kotlinx.serialization.json.Json

/**
 * Pre-rendered `/fraud-score` response bodies. Only `K_NEIGHBORS + 1` outcomes are
 * possible (the fraud-neighbor count is `0..K`), so every response body is serialized
 * once at class-load time and the hot path just hands back the ready bytes — no
 * per-request `encodeToString` / String→ByteArray copy. The bodies are produced by the
 * SAME compile-time serializer the route used before, so the wire output is byte-identical.
 */
internal object FraudResponses {
    private val json = Json

    /** Index = fraud-neighbor count (0..K); value = the UTF-8 JSON body for that count. */
    private val bodies: Array<ByteArray> = Array(K_NEIGHBORS + 1) { count ->
        val score = fraudScore(count)
        val response = FraudScoreResponse(approved = isApproved(score), fraudScore = score)
        json.encodeToString(FraudScoreResponse.serializer(), response).encodeToByteArray()
    }

    /** Safe answer (approved, score 0.0) when the index is unavailable or evaluation fails. */
    val FALLBACK: ByteArray = bodies[0]

    /** Ready-to-write JSON body for [fraudCount] nearest fraud neighbors. */
    fun forCount(fraudCount: Int): ByteArray = bodies[fraudCount]
}
