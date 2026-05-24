package dev.santo.tools

import dev.santo.search.BUCKET_COUNT
import dev.santo.search.BucketedVpTreeIndex
import dev.santo.search.LabeledVector
import dev.santo.search.VpTree
import dev.santo.search.quantizeVector
import dev.santo.search.signatureOf
import dev.santo.vectorization.VECTOR_DIMENSIONS

/**
 * Offline construction of a [BucketedVpTreeIndex] from labeled references:
 * quantizes each vector, partitions by categorical signature, and builds a
 * [VpTree] per bucket. CPU- and RAM-heavy, so it runs once during the image
 * build (never at startup); the runtime reconstructs the result via
 * [BucketedVpTreeIndex.fromParts].
 */
object IndexBuilder {

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
}
