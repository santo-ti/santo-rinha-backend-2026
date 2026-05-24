package dev.santo.index

import dev.santo.vectorization.VECTOR_DIMENSIONS

/**
 * Production index: references are partitioned into up to [BUCKET_COUNT] buckets
 * by categorical signature, each holding a [VpTree] over int8-quantized vectors.
 *
 * Search is exact within the quantized space: after filling the k nearest from
 * the home bucket, other buckets are visited only when their categorical lower
 * bound is still closer than the current k-th neighbor — so no closer neighbor
 * is ever skipped.
 */
class BucketedVpTreeIndex internal constructor(
    internal val store: ByteArray,
    internal val labels: BooleanArray,
    internal val dim: Int,
    private val buckets: Array<VpTree?>,
) : VectorIndex {

    internal fun bucket(signature: Int): VpTree? = buckets[signature]

    override fun nearestFraudCount(query: DoubleArray): Int {
        val qz = quantizeVector(query)
        val sig = signatureOf(query)
        val knn = KNearest(K_NEIGHBORS)

        buckets[sig]?.search(qz, knn)
        for (b in buckets.indices) {
            if (b == sig) continue
            val tree = buckets[b] ?: continue
            if (knn.isFull()) {
                val worst = knn.worst()
                if (categoricalLowerBoundSquared(sig, b).toDouble() >= worst * worst) continue
            }
            tree.search(qz, knn)
        }
        return knn.fraudCount()
    }

    companion object {
        fun build(references: List<LabeledVector>, dim: Int = VECTOR_DIMENSIONS): BucketedVpTreeIndex {
            val n = references.size
            val store = ByteArray(n * dim)
            val labels = BooleanArray(n)
            val idsBySignature = Array(BUCKET_COUNT) { ArrayList<Int>() }

            for (i in 0 until n) {
                val reference = references[i]
                System.arraycopy(quantizeVector(reference.vector), 0, store, i * dim, dim)
                labels[i] = reference.isFraud
                idsBySignature[signatureOf(reference.vector)].add(i)
            }

            val buckets = Array<VpTree?>(BUCKET_COUNT) { signature ->
                val ids = idsBySignature[signature]
                if (ids.isEmpty()) null else VpTree.build(ids.toIntArray(), store, labels, dim)
            }
            return BucketedVpTreeIndex(store, labels, dim, buckets)
        }

        /** Reconstructs an index from deserialized parts without rebuilding the trees. */
        fun fromParts(
            store: ByteArray,
            labels: BooleanArray,
            dim: Int,
            bucketIds: Array<IntArray?>,
            bucketThresholds: Array<FloatArray?>,
        ): BucketedVpTreeIndex {
            val buckets = Array<VpTree?>(BUCKET_COUNT) { b ->
                val ids = bucketIds[b] ?: return@Array null
                VpTree.fromPrebuilt(ids, bucketThresholds[b]!!, store, labels, dim)
            }
            return BucketedVpTreeIndex(store, labels, dim, buckets)
        }
    }
}
