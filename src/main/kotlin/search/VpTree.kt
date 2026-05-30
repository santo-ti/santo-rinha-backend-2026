package dev.santo.search

import kotlin.math.sqrt

/**
 * Implicit Vantage-Point Tree over a CONTIGUOUS slice of the global quantized
 * store: the bucket's points live at `store[(base + p) * dim]` for local position
 * `p in [0, count)`, laid out in tree-traversal order. The tree structure is
 * therefore fully implicit — node `lo` of a `[lo, hi)` range is the vantage point,
 * its children are `[lo+1, mid)` and `[mid, hi)` — so the only per-point overhead
 * is one `Float` threshold. No per-point id array is stored (it would be just
 * `base + p`), which keeps a 3M-point int16 store within the memory budget and
 * makes traversal reads contiguous (cache-friendly).
 *
 * Search pruning relies on the triangle inequality over the Euclidean metric. A
 * [SearchBudget] caps distance evaluations: an unlimited budget keeps the search
 * exact (never discards a true nearest neighbor); a finite one makes it approximate
 * (closest regions first), trading a little recall for a hard ceiling on work.
 */
class VpTree private constructor(
    private val base: Int, // offset (in points) of this bucket's slice in the global store
    private val count: Int, // number of points in the bucket
    private val thresholds: FloatArray, // per-node split radius, indexed by node low bound
    private val store: ShortArray, // global quantized vectors, n*dim
    private val labels: BooleanArray, // global fraud labels
    private val dim: Int,
) {
    val size: Int get() = count

    internal val storeBase: Int get() = base
    internal val nodeCount: Int get() = count
    internal fun thresholds(): FloatArray = thresholds
    internal fun thresholdAt(lo: Int): Float = thresholds[lo]

    /** Distance from the query to the vantage point of the node at local [lo]. */
    internal fun distAt(queryCodes: IntArray, lo: Int): Double = dist(queryCodes, base + lo)

    /** Fraud label of the vantage point of the node at local [lo]. */
    internal fun labelAt(lo: Int): Boolean = labels[base + lo]

    private fun buildNode(lo: Int, hi: Int) {
        if (hi - lo <= 1) return
        val mid = lo + 1 + (hi - lo - 1) / 2
        nthElement(lo + 1, hi, mid, base + lo)
        thresholds[lo] = distPoints(base + lo, base + mid).toFloat()
        buildNode(lo + 1, mid)
        buildNode(mid, hi)
    }

    private fun dist(queryCodes: IntArray, pointIdx: Int): Double =
        sqrt(squaredDistance(queryCodes, store, pointIdx * dim, dim).toDouble())

    private fun distPoints(idA: Int, idB: Int): Double =
        sqrt(squaredDistance(store, idA * dim, idB * dim, dim).toDouble())

    /** Partitions local range `[from, to)` (store rows `base+from..base+to`) by
     *  distance to vantage point [vp] so that local position `nth` is the median. */
    private fun nthElement(from: Int, to: Int, nth: Int, vp: Int) {
        var lo = from
        var hi = to - 1
        while (lo < hi) {
            val pivot = distPoints(vp, base + ((lo + hi) ushr 1))
            var i = lo
            var j = hi
            while (i <= j) {
                while (distPoints(vp, base + i) < pivot) i++
                while (distPoints(vp, base + j) > pivot) j--
                if (i <= j) { swapRows(base + i, base + j); i++; j-- }
            }
            if (nth <= j) hi = j else if (nth >= i) lo = i else break
        }
    }

    /** Swaps two store rows (and their labels) in place — the build-time reorder. */
    private fun swapRows(a: Int, b: Int) {
        val offA = a * dim
        val offB = b * dim
        for (k in 0 until dim) {
            val t = store[offA + k]; store[offA + k] = store[offB + k]; store[offB + k] = t
        }
        val tl = labels[a]; labels[a] = labels[b]; labels[b] = tl
    }

    companion object {
        /** Builds a tree over `store[base..base+count)`, reordering those rows in
         *  place into tree-traversal order and computing the node thresholds. */
        fun build(base: Int, count: Int, store: ShortArray, labels: BooleanArray, dim: Int): VpTree {
            val tree = VpTree(base, count, FloatArray(count), store, labels, dim)
            tree.buildNode(0, count)
            return tree
        }

        /** Reconstructs a tree from its already-built slice and thresholds (no rebuild). */
        fun fromPrebuilt(
            base: Int,
            count: Int,
            thresholds: FloatArray,
            store: ShortArray,
            labels: BooleanArray,
            dim: Int,
        ): VpTree = VpTree(base, count, thresholds, store, labels, dim)
    }
}
