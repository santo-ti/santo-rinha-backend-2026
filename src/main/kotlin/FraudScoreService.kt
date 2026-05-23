package dev.santo

import dev.santo.index.IndexState
import dev.santo.index.isApproved
import dev.santo.index.scoreOf
import dev.santo.model.FraudScoreRequest
import dev.santo.model.FraudScoreResponse
import dev.santo.vectorization.Vectorizer

/** Vectorizes a transaction, runs the k-NN search and applies the decision rule. */
class FraudScoreService(
    private val vectorizer: Vectorizer,
    private val indexState: IndexState,
) {
    fun evaluate(request: FraudScoreRequest): FraudScoreResponse {
        val index = indexState.current() ?: return FALLBACK
        val score = index.scoreOf(vectorizer.vectorize(request))
        return FraudScoreResponse(approved = isApproved(score), fraudScore = score)
    }

    companion object {
        /** Fast, safe answer used when the index is unavailable or evaluation fails. */
        val FALLBACK = FraudScoreResponse(approved = true, fraudScore = 0.0)
    }
}
