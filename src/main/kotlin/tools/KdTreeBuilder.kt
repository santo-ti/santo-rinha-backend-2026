package dev.santo.tools

import dev.santo.search.KdTreeIndex
import dev.santo.vectorization.VECTOR_DIMENSIONS

/**
 * Builds a balanced [KdTreeIndex] from int16 vectors. At each node: split on the largest-range
 * dimension (sampled), put the median point there, recurse. Nodes are assigned in PRE-ORDER
 * (node, then its whole left subtree, then right), so a node's left child is always `node+1`
 * and only the right child id needs storing — packed with the split dim and a has-left flag
 * into one int per node (see [KdTreeIndex]). Median by quickselect → O(n log n) build.
 */
object KdTreeBuilder {

    fun build(src: ShortArray, labels: BooleanArray, n: Int, dim: Int = VECTOR_DIMENSIONS): KdTreeIndex {
        val idx = IntArray(n) { it }
        val nodeVec = ShortArray(n * dim)
        val nodeLabel = BooleanArray(n)
        val packed = IntArray(n)
        val next = IntArray(1)
        val root = if (n == 0) -1 else build(idx, 0, n, src, labels, dim, nodeVec, nodeLabel, packed, next)
        return KdTreeIndex(nodeVec, nodeLabel, packed, root, dim, n)
    }

    private fun build(
        idx: IntArray, lo: Int, hi: Int, src: ShortArray, labels: BooleanArray, dim: Int,
        nodeVec: ShortArray, nodeLabel: BooleanArray, packed: IntArray, next: IntArray,
    ): Int {
        if (lo >= hi) return -1
        val sd = maxRangeDim(idx, lo, hi, src, dim)
        val mid = (lo + hi) ushr 1
        quickselect(idx, lo, hi, mid, src, dim, sd)

        val nodeId = next[0]++
        val p = idx[mid]
        System.arraycopy(src, p * dim, nodeVec, nodeId * dim, dim)
        nodeLabel[nodeId] = labels[p]

        val leftId = build(idx, lo, mid, src, labels, dim, nodeVec, nodeLabel, packed, next)   // = nodeId+1 when present
        val rightId = build(idx, mid + 1, hi, src, labels, dim, nodeVec, nodeLabel, packed, next)
        val hasLeft = if (leftId >= 0) 1 else 0
        packed[nodeId] = (sd shl 23) or (hasLeft shl 22) or (rightId + 1)
        return nodeId
    }

    private fun maxRangeDim(idx: IntArray, lo: Int, hi: Int, src: ShortArray, dim: Int): Int {
        val count = hi - lo
        val step = if (count > 256) count / 256 else 1
        var bestDim = 0
        var bestRange = -1
        for (d in 0 until dim) {
            var mn = Int.MAX_VALUE; var mx = Int.MIN_VALUE
            var i = lo
            while (i < hi) {
                val v = src[idx[i] * dim + d].toInt()
                if (v < mn) mn = v
                if (v > mx) mx = v
                i += step
            }
            val r = mx - mn
            if (r > bestRange) { bestRange = r; bestDim = d }
        }
        return bestDim
    }

    /** Quickselect so idx[k] is the element that belongs there ordered by dimension [sd]. */
    private fun quickselect(idx: IntArray, lo: Int, hi: Int, k: Int, src: ShortArray, dim: Int, sd: Int) {
        var l = lo; var r = hi - 1
        while (l < r) {
            val pivot = valAt(idx, medianOfThree(idx, l, r, src, dim, sd), src, dim, sd)
            var i = l; var j = r
            while (i <= j) {
                while (valAt(idx, i, src, dim, sd) < pivot) i++
                while (valAt(idx, j, src, dim, sd) > pivot) j--
                if (i <= j) { val t = idx[i]; idx[i] = idx[j]; idx[j] = t; i++; j-- }
            }
            if (k <= j) r = j else if (k >= i) l = i else return
        }
    }

    private fun valAt(idx: IntArray, i: Int, src: ShortArray, dim: Int, sd: Int): Int = src[idx[i] * dim + sd].toInt()

    private fun medianOfThree(idx: IntArray, lo: Int, hi: Int, src: ShortArray, dim: Int, sd: Int): Int {
        val mid = (lo + hi) ushr 1
        val a = valAt(idx, lo, src, dim, sd); val b = valAt(idx, mid, src, dim, sd); val c = valAt(idx, hi, src, dim, sd)
        return if (a <= b) { if (b <= c) mid else if (a <= c) hi else lo }
        else { if (a <= c) lo else if (b <= c) hi else mid }
    }
}
