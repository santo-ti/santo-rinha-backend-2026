package dev.santo.search

import kotlin.math.sqrt

/**
 * Implicit Vantage-Point Tree over quantized vectors. The [ids] array is
 * reordered so the tree structure is implicit (no per-node objects) — the only
 * per-point overhead is one `Float` threshold, which keeps 3M points affordable.
 *
 * Search pruning relies on the triangle inequality over the Euclidean metric. A
 * [SearchBudget] caps the distance evaluations: an unlimited budget keeps the
 * search exact (never discards a true nearest neighbor), while a finite one makes
 * it approximate — the traversal visits the closest regions first, so the cap
 * trades a little recall for a hard ceiling on work. A prebuilt tree can be
 * reconstructed from its reordered [ids] and [thresholds] without rebuilding.
 */
class VpTree private constructor(
    private val ids: IntArray, // global point ids, reordered during build
    private val thresholds: FloatArray, // per-node split radius, indexed by node low bound
    private val store: ByteArray, // global quantized vectors, n*dim
    private val labels: BooleanArray, // global fraud labels
    private val dim: Int,
) {
    val size: Int get() = ids.size

    internal fun orderedIds(): IntArray = ids

    internal fun thresholds(): FloatArray = thresholds

    fun search(queryCodes: IntArray, knn: KNearest, budget: SearchBudget) =
        searchNode(0, ids.size, queryCodes, knn, budget)

    private fun searchNode(lo: Int, hi: Int, queryCodes: IntArray, knn: KNearest, budget: SearchBudget) {
        if (lo >= hi || budget.exhausted()) return
        val vp = ids[lo]
        val d = dist(queryCodes, vp)
        budget.consume()
        knn.offer(d, labels[vp])
        if (hi - lo == 1) return

        val mid = lo + 1 + (hi - lo - 1) / 2
        val tau = thresholds[lo]
        if (d < tau) {
            searchNode(lo + 1, mid, queryCodes, knn, budget)
            if (d + knn.worst() >= tau) searchNode(mid, hi, queryCodes, knn, budget)
        } else {
            searchNode(mid, hi, queryCodes, knn, budget)
            if (d - knn.worst() <= tau) searchNode(lo + 1, mid, queryCodes, knn, budget)
        }
    }

    private fun build(lo: Int, hi: Int) {
        if (hi - lo <= 1) return
        val vp = ids[lo]
        val mid = lo + 1 + (hi - lo - 1) / 2
        nthElement(lo + 1, hi, mid, vp)
        thresholds[lo] = dist(vp, ids[mid]).toFloat()
        build(lo + 1, mid)
        build(mid, hi)
    }

    private fun dist(queryCodes: IntArray, pointId: Int): Double =
        sqrt(squaredDistance(queryCodes, store, pointId * dim, dim).toDouble())

    private fun dist(idA: Int, idB: Int): Double =
        sqrt(squaredDistance(store, idA * dim, idB * dim, dim).toDouble())

    /** Partitions `ids[from, to)` by distance to [vp] so that `ids[nth]` is the median. */
    private fun nthElement(from: Int, to: Int, nth: Int, vp: Int) {
        var lo = from
        var hi = to - 1
        while (lo < hi) {
            val pivot = dist(vp, ids[(lo + hi) ushr 1])
            var i = lo
            var j = hi
            while (i <= j) {
                while (dist(vp, ids[i]) < pivot) i++
                while (dist(vp, ids[j]) > pivot) j--
                if (i <= j) {
                    val t = ids[i]; ids[i] = ids[j]; ids[j] = t
                    i++; j--
                }
            }
            if (nth <= j) hi = j else if (nth >= i) lo = i else break
        }
    }

    companion object {
        fun build(ids: IntArray, store: ByteArray, labels: BooleanArray, dim: Int): VpTree {
            val tree = VpTree(ids, FloatArray(ids.size), store, labels, dim)
            tree.build(0, ids.size)
            return tree
        }

        /** Reconstructs a tree from its already-built order and thresholds (no rebuild). */
        fun fromPrebuilt(
            ids: IntArray,
            thresholds: FloatArray,
            store: ByteArray,
            labels: BooleanArray,
            dim: Int,
        ): VpTree = VpTree(ids, thresholds, store, labels, dim)
    }
}
