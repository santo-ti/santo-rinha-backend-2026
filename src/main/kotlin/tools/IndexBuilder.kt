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
 * quantizes each vector, partitions by categorical signature, and builds a
 * [VpTree] per bucket. CPU- and RAM-heavy, so it runs once during the image
 * build (never at startup); the runtime reconstructs the result via
 * [BucketedVpTreeIndex.fromParts].
 *
 * Optional [maxSize] uniformly samples down to that many references before
 * building. The exact bucketed VP-Tree degenerates at 14 dimensions (curse of
 * dimensionality: triangle-inequality pruning fails), so search cost over the
 * full 3M store dominates the contest's 0.425-CPU budget and bursts past the
 * 2001ms timeout. Sampling trades a smaller fraction of FP/FN (weight 1/3)
 * for far fewer HTTP timeouts (weight 5) — a net win under the official rules,
 * which explicitly permit approximate techniques.
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
        val store = ByteArray(n * dim)
        val labels = BooleanArray(n)
        val idsBySignature = Array(BUCKET_COUNT) { ArrayList<Int>() }

        for (i in 0 until n) {
            val reference = sampled[i]
            System.arraycopy(quantizeVector(reference.vector), 0, store, i * dim, dim)
            labels[i] = reference.isFraud
            idsBySignature[signatureOf(reference.vector)].add(i)
        }

        val trees = Array<VpTree?>(BUCKET_COUNT) { signature ->
            val ids = idsBySignature[signature]
            if (ids.isEmpty()) null else VpTree.build(ids.toIntArray(), store, labels, dim)
        }

        // Re-lay the store so each bucket's points are contiguous in tree-traversal
        // order. The search hops between nodes that are near in the tree, so this
        // turns the scattered (original-sample-order) reads into mostly-local ones.
        // The full 3M store (42MB) does not fit the contest CPU's cache, where ns/
        // comp ~3x worse than the cache-resident 100k store; locality recovers that.
        // Functionally identical: same points, distances, thresholds and topology —
        // only the byte positions change (the remapped ids stay contiguous per bucket).
        val packedStore = ByteArray(n * dim)
        val packedLabels = BooleanArray(n)
        val buckets = arrayOfNulls<VpTree>(BUCKET_COUNT)
        var pos = 0
        for (signature in 0 until BUCKET_COUNT) {
            val tree = trees[signature] ?: continue
            val treeIds = tree.orderedIds()
            val remapped = IntArray(treeIds.size)
            for (i in treeIds.indices) {
                val src = treeIds[i]
                System.arraycopy(store, src * dim, packedStore, pos * dim, dim)
                packedLabels[pos] = labels[src]
                remapped[i] = pos
                pos++
            }
            buckets[signature] = VpTree.fromPrebuilt(remapped, tree.thresholds(), packedStore, packedLabels, dim)
        }
        return BucketedVpTreeIndex(packedStore, packedLabels, dim, buckets)
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
