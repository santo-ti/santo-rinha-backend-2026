package dev.santo.search

/** A k-nearest-neighbor index over labeled 14-dimension vectors. */
interface VectorIndex {
    /** Number of fraud labels among the K nearest references to [query]. */
    fun nearestFraudCount(query: DoubleArray): Int
}
