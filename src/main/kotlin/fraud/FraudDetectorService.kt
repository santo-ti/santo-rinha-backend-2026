package dev.santo.fraud

import dev.santo.dto.FraudScoreRequest
import dev.santo.dto.FraudScoreResponse
import dev.santo.search.IndexState
import dev.santo.vectorization.Vectorizer

/** Vectorizes a transaction, runs the k-NN search and applies the decision rule. */
class FraudDetectorService(
    private val vectorizer: Vectorizer,
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

    companion object {
        /** Fast, safe answer used when the index is unavailable or evaluation fails. */
        val FALLBACK = FraudScoreResponse(approved = true, fraudScore = 0.0)
    }
}
