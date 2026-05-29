package dev.santo.tools

import dev.santo.search.BUCKET_COUNT
import dev.santo.search.BucketedVpTreeIndex
import dev.santo.search.LabeledVector
import dev.santo.search.VpTree
import dev.santo.search.quantizeVector
import dev.santo.search.signatureOf
import dev.santo.vectorization.VECTOR_DIMENSIONS
import kotlin.random.Random

/**
 * Offline construction of a [BucketedVpTreeIndex] from labeled references:
 * quantizes each vector (int16), partitions by categorical signature, packs each
 * bucket's points CONTIGUOUSLY into the global store, and builds a [VpTree] per
 * bucket that reorders its slice in place into tree-traversal order. CPU- and
 * RAM-heavy, so it runs once during the image build (never at startup); the
 * runtime reconstructs the result via [BucketedVpTreeIndex.fromParts].
 *
 * The contiguous, tree-ordered layout means a point's id is just `base + position`,
 * so no per-point id array is stored — that both fits the 3M int16 store in the
 * memory budget and makes search reads cache-local.
 *
 * Optional [maxSize] uniformly samples down to that many references first. Indexing
 * all 3M is the dominant detection lever; sampling is only for experiments now that
 * the bucketed VP-Tree + cache-local layout serves the full store within budget.
 */
object IndexBuilder {
    /** Fixed seed so two builds with the same input produce the same index. */
    private const val SAMPLE_SEED = 1L

    fun build(
        references: List<LabeledVector>,
        dim: Int = VECTOR_DIMENSIONS,
        maxSize: Int = Int.MAX_VALUE,
    ): BucketedVpTreeIndex {
        val sampled = if (references.size > maxSize) sample(references, maxSize) else references
        val n = sampled.size
        val idsBySignature = Array(BUCKET_COUNT) { ArrayList<Int>() }
        for (i in 0 until n) idsBySignature[signatureOf(sampled[i].vector)].add(i)

        val store = ShortArray(n * dim)
        val labels = BooleanArray(n)
        val buckets = arrayOfNulls<VpTree>(BUCKET_COUNT)
        var pos = 0
        for (signature in 0 until BUCKET_COUNT) {
            val ids = idsBySignature[signature]
            if (ids.isEmpty()) continue
            val base = pos
            for (id in ids) {
                System.arraycopy(quantizeVector(sampled[id].vector), 0, store, pos * dim, dim)
                labels[pos] = sampled[id].isFraud
                pos++
            }
            // Reorders store[base..pos) in place into tree-traversal order.
            buckets[signature] = VpTree.build(base, ids.size, store, labels, dim)
        }
        return BucketedVpTreeIndex(store, labels, dim, buckets)
    }

    /** Uniform random sample without replacement, deterministic via [SAMPLE_SEED]. */
    private fun sample(references: List<LabeledVector>, maxSize: Int): List<LabeledVector> {
        val rng = Random(SAMPLE_SEED)
        val pickedIndices = HashSet<Int>(maxSize)
        while (pickedIndices.size < maxSize) {
            pickedIndices.add(rng.nextInt(references.size))
        }
        val out = ArrayList<LabeledVector>(maxSize)
        for (i in pickedIndices) out.add(references[i])
        return out
    }
}
