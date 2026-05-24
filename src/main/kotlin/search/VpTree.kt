package dev.santo.search

import kotlin.math.sqrt

/**
 * Implicit Vantage-Point Tree over quantized vectors. The [ids] array is
 * reordered so the tree structure is implicit (no per-node objects) — the only
 * per-point overhead is one `Float` threshold, which keeps 3M points affordable.
 *
 * Search is exact: pruning relies on the triangle inequality over the Euclidean
 * metric, so it never discards a true nearest neighbor. A prebuilt tree can be
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

    fun search(query: ByteArray, knn: KNearest) = searchNode(0, ids.size, query, knn)

    private fun searchNode(lo: Int, hi: Int, query: ByteArray, knn: KNearest) {
        if (lo >= hi) return
        val vp = ids[lo]
        val d = dist(query, vp)
        knn.offer(d, labels[vp])
        if (hi - lo == 1) return

        val mid = lo + 1 + (hi - lo - 1) / 2
        val tau = thresholds[lo]
        if (d < tau) {
            searchNode(lo + 1, mid, query, knn)
            if (d + knn.worst() >= tau) searchNode(mid, hi, query, knn)
        } else {
            searchNode(mid, hi, query, knn)
            if (d - knn.worst() <= tau) searchNode(lo + 1, mid, query, knn)
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

    private fun dist(query: ByteArray, pointId: Int): Double =
        sqrt(squaredDistance(query, store, pointId * dim, dim).toDouble())

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
