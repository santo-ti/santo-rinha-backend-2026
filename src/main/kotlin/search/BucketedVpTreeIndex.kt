package dev.santo.search

import dev.santo.fraud.K_NEIGHBORS

/**
 * Production index: references are partitioned into up to [BUCKET_COUNT] buckets
 * by categorical signature, each holding a [VpTree] over int8-quantized vectors.
 *
 * Search is exact within the quantized space: after filling the k nearest from
 * the home bucket, other buckets are visited only when their categorical lower
 * bound is still closer than the current k-th neighbor — so no closer neighbor
 * is ever skipped.
 *
 * Built offline by `tools.IndexBuilder`; the runtime only reconstructs it from a
 * prebuilt artifact via [fromParts].
 */
class BucketedVpTreeIndex internal constructor(
    internal val store: ShortArray,
    internal val labels: BooleanArray,
    internal val dim: Int,
    private val buckets: Array<VpTree?>,
    private val searchBudget: Int = Int.MAX_VALUE,
) : VectorIndex {

    internal fun bucket(signature: Int): VpTree? = buckets[signature]

    override fun nearestFraudCount(query: DoubleArray): Int =
        nearestFraudCount(query, SearchBudget.of(searchBudget))

    /**
     * Same search with an explicit [budget], shared across all buckets so the cap
     * is per query. An unlimited budget is exact; a finite one bounds the distance
     * evaluations (closest bucket and subtrees first), trading a little recall for
     * a hard latency ceiling.
     */
    internal fun nearestFraudCount(query: DoubleArray, budget: SearchBudget): Int {
        val queryCodes = IntArray(dim) { quantizeToLogicalCode(query[it]) }
        val sig = signatureOf(query)
        val knn = KNearest(K_NEIGHBORS)

        buckets[sig]?.search(queryCodes, knn, budget)
        for (b in buckets.indices) {
            if (b == sig) continue
            if (budget.exhausted()) break
            val tree = buckets[b] ?: continue
            if (knn.isFull()) {
                val worst = knn.worst()
                if (categoricalLowerBoundSquared(sig, b).toDouble() >= worst * worst) continue
            }
            tree.search(queryCodes, knn, budget)
        }
        return knn.fraudCount()
    }

    companion object {
        /** Reconstructs an index from deserialized parts without rebuilding the trees. */
        fun fromParts(
            store: ShortArray,
            labels: BooleanArray,
            dim: Int,
            bucketSizes: IntArray,
            bucketThresholds: Array<FloatArray?>,
            searchBudget: Int = Int.MAX_VALUE,
        ): BucketedVpTreeIndex {
            // Buckets are stored back-to-back in signature order, so each bucket's
            // slice starts at the running sum of the preceding sizes.
            var base = 0
            val buckets = Array<VpTree?>(BUCKET_COUNT) { b ->
                val size = bucketSizes[b]
                val tree = if (size == 0) null
                else VpTree.fromPrebuilt(base, size, bucketThresholds[b]!!, store, labels, dim)
                base += size
                tree
            }
            return BucketedVpTreeIndex(store, labels, dim, buckets, searchBudget)
        }
    }
}
