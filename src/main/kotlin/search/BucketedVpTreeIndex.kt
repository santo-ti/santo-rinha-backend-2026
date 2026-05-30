package dev.santo.search

import dev.santo.fraud.K_NEIGHBORS
import kotlin.math.sqrt

/**
 * Production index: references are partitioned into up to [BUCKET_COUNT] buckets
 * by categorical signature, each holding a [VpTree] over int16-quantized vectors.
 *
 * Search is a global best-first branch-and-bound across all buckets and tree
 * nodes: a priority queue holds candidate subtrees ordered by a lower bound on
 * the distance from any point they contain to the query (categorical mismatch
 * for whole buckets, the VP-tree annulus bound for inner subtrees). The most
 * promising region is always expanded first, so the true k nearest are found
 * after very few distance evaluations even when the metric landscape is flat
 * (e.g. a query in a region the references barely populate). An unlimited
 * [SearchBudget] is exact (the queue is drained until its head can no longer beat
 * the k-th neighbor); a finite one caps the evaluations, and because the order is
 * best-first the truncation costs far less recall than a depth-first walk would.
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
     * Same search with an explicit [budget], shared across the whole traversal so
     * the cap is per query. An unlimited budget is exact; a finite one bounds the
     * distance evaluations (most-promising region first), trading a little recall
     * for a hard latency ceiling.
     */
    internal fun nearestFraudCount(query: DoubleArray, budget: SearchBudget): Int {
        val queryCodes = IntArray(dim) { quantizeToLogicalCode(query[it]) }
        val sig = signatureOf(query)
        val knn = KNearest(K_NEIGHBORS)

        // Frontier of subtrees ordered by their lower-bound distance to the query.
        // Each entry packs (bucket, lo, hi) into a Long alongside a parallel lb key,
        // so the hot path allocates two primitive arrays per query, not one object
        // per visited node.
        val frontier = FrontierHeap(2 * BUCKET_COUNT)
        for (b in buckets.indices) {
            val tree = buckets[b] ?: continue
            val lb = if (b == sig) 0.0 else sqrt(categoricalLowerBoundSquared(sig, b).toDouble())
            frontier.push(lb, pack(b, 0, tree.nodeCount))
        }

        while (frontier.isNotEmpty() && !budget.exhausted()) {
            val lb = frontier.minKey()
            // The heap is ordered by lb; once the head cannot beat the k-th
            // neighbor, neither can anything behind it — the search is done.
            if (knn.isFull() && lb >= knn.worst()) break
            val node = frontier.pop()

            val tree = buckets[unpackBucket(node)]!!
            val lo = unpackLo(node)
            val hi = unpackHi(node)
            val d = tree.distAt(queryCodes, lo)
            budget.consume()
            knn.offer(d, tree.labelAt(lo))

            val width = hi - lo
            if (width == 1) continue
            val mid = lo + 1 + (width - 1) / 2
            val tau = tree.thresholdAt(lo)
            val bucket = unpackBucket(node)
            // Inner points lie within tau of the vantage point: dist(q,p) >= d - tau.
            if (mid > lo + 1) frontier.push(maxOf(lb, d - tau), pack(bucket, lo + 1, mid))
            // Outer points lie beyond tau: dist(q,p) >= tau - d.
            if (hi > mid) frontier.push(maxOf(lb, tau - d), pack(bucket, mid, hi))
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

// A subtree frontier entry packs (bucket, lo, hi) into one Long: 4 bits of bucket
// signature (0..15) and two 22-bit point indices (each < 4.19M, covering the 3M
// store). Avoids an object per visited node on the search hot path.
private const val IDX_MASK = 0x3FFFFFL // 22 bits
private fun pack(bucket: Int, lo: Int, hi: Int): Long =
    (bucket.toLong() shl 44) or (lo.toLong() shl 22) or hi.toLong()
private fun unpackBucket(packed: Long): Int = (packed ushr 44).toInt()
private fun unpackLo(packed: Long): Int = ((packed ushr 22) and IDX_MASK).toInt()
private fun unpackHi(packed: Long): Int = (packed and IDX_MASK).toInt()

/**
 * Binary min-heap over `(Double key, Long value)` pairs in parallel primitive
 * arrays — the search frontier ordered by lower-bound distance. Grows on demand;
 * one instance is allocated per query (two primitive arrays), so no per-node
 * object churn reaches the GC under load.
 */
private class FrontierHeap(initialCapacity: Int) {
    private var keys = DoubleArray(initialCapacity)
    private var vals = LongArray(initialCapacity)
    private var n = 0

    fun isNotEmpty(): Boolean = n > 0
    fun minKey(): Double = keys[0]

    fun push(key: Double, value: Long) {
        if (n == keys.size) {
            keys = keys.copyOf(n * 2)
            vals = vals.copyOf(n * 2)
        }
        var i = n++
        keys[i] = key
        vals[i] = value
        while (i > 0) {
            val parent = (i - 1) ushr 1
            if (keys[parent] <= keys[i]) break
            swap(i, parent)
            i = parent
        }
    }

    /** Removes and returns the value with the smallest key (peek the key first). */
    fun pop(): Long {
        val top = vals[0]
        if (--n > 0) {
            keys[0] = keys[n]
            vals[0] = vals[n]
            var i = 0
            while (true) {
                val l = 2 * i + 1
                val r = l + 1
                var smallest = i
                if (l < n && keys[l] < keys[smallest]) smallest = l
                if (r < n && keys[r] < keys[smallest]) smallest = r
                if (smallest == i) break
                swap(i, smallest)
                i = smallest
            }
        }
        return top
    }

    private fun swap(a: Int, b: Int) {
        val tk = keys[a]; keys[a] = keys[b]; keys[b] = tk
        val tv = vals[a]; vals[a] = vals[b]; vals[b] = tv
    }
}
