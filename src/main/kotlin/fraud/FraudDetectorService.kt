package dev.santo.fraud

import dev.santo.dto.FraudScoreRequest
import dev.santo.dto.FraudScoreResponse
import dev.santo.search.IndexState
import dev.santo.vectorization.ByteVectorizer
import dev.santo.vectorization.Vectorizer

/** Vectorizes a transaction, runs the k-NN search and applies the decision rule. */
class FraudDetectorService(
    private val vectorizer: Vectorizer,
    private val byteVectorizer: ByteVectorizer,
    private val indexState: IndexState,
) {
    fun evaluate(request: FraudScoreRequest): FraudScoreResponse {
        val index = indexState.current() ?: return FALLBACK
        val score = index.scoreOf(vectorizer.vectorize(request))
        return FraudScoreResponse(approved = isApproved(score), fraudScore = score)
    }

    /**
     * Number of fraud neighbors (`0..K`) among the nearest neighbors of [request], or
     * `0` (the safe-fallback outcome) when the index is not yet available. Lets the HTTP
     * edge map straight to a pre-rendered response body without building a DTO.
     */
    fun fraudCountOf(request: FraudScoreRequest): Int {
        val index = indexState.current() ?: return 0
        return index.nearestFraudCount(vectorizer.vectorize(request))
    }

    /**
     * Fraud-neighbor count straight from the raw request bytes ([buf] valid for `0 until len`),
     * skipping the DTO graph via [ByteVectorizer] — the allocation-light hot path used by the
     * HTTP edge. `0` when the index is not yet available (the safe-fallback outcome).
     */
    fun fraudCountOf(buf: ByteArray, len: Int): Int {
        val index = indexState.current() ?: return 0
        return index.nearestFraudCount(byteVectorizer.vectorize(buf, len))
    }

    /** Fraud-neighbor count from the JSON body in `buf[off until end]` (reactor hot path). */
    fun fraudCountOf(buf: ByteArray, off: Int, end: Int): Int {
        val index = indexState.current() ?: return 0
        return index.nearestFraudCount(byteVectorizer.vectorize(buf, off, end))
    }

    companion object {
        /** Fast, safe answer used when the index is unavailable or evaluation fails. */
        val FALLBACK = FraudScoreResponse(approved = true, fraudScore = 0.0)
    }
}
