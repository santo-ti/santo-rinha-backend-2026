package dev.santo.index

/** A k-nearest-neighbor index over labeled 14-dimension vectors. */
interface VectorIndex {
    /** Number of fraud labels among the [K_NEIGHBORS] nearest references to [query]. */
    fun nearestFraudCount(query: DoubleArray): Int
}

/** Fraud score (`fraud_neighbors / K`) for [query] against this index. */
fun VectorIndex.scoreOf(query: DoubleArray): Double = fraudScore(nearestFraudCount(query))
