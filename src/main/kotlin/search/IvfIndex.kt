package dev.santo.search

import dev.santo.fraud.K_NEIGHBORS

/** District count (level-1 meta-centroids over the cell centroids). */
const val DEFAULT_META_CELLS = 64

/**
 * Default districts probed (level 1) per query; env-overridable via `NPROBE1`.
 * Calibrated offline (tools.IvfTwoLevel, k=4096): nprobe1=4 keeps recall while
 * scanning only ~4·(k/k1) member cells instead of all k.
 */
const val DEFAULT_NPROBE1 = 4

/**
 * Default cells probed (level 2) per query; env-overridable via `NPROBE2`.
 * nprobe2=6 → ~3.8k comparisons/query (≈0.23ms on the contest core, well under the
 * ~1ms budget) at detection ~exact (contest E≈81). Raise for recall, lower for CPU.
 */
const val DEFAULT_NPROBE2 = 6

/**
 * Two-level IVF (inverted file) index. References are partitioned offline into [k]
 * cells by k-means; the cell centroids are themselves clustered into [k1] districts
 * (meta-centroids). A query (1) scans the [k1] districts → nearest [np1]; (2) ranks
 * the member cells of those districts → nearest [np2]; (3) scans those cells' points.
 *
 * WHY two levels: a flat IVF scans ALL [k] centroids to route every query — at
 * k=4096 that fixed ~4096-comparison cost dominated and saturated the 0.45-CPU core
 * (contest #7422/#7428, p99 cut). Routing through ~[k1]=64 districts cuts it to
 * ~k1 + np1·(k/k1) ≈ a few hundred, dropping total comparisons ~4× (≈16k → ≈3.8k)
 * with no loss of detection — IVF cells follow the data, so (unlike the old VP-tree)
 * the dim5-saturated randomized-date tail does not degenerate.
 *
 * Layout: [centroids] and [metaCentroids] are centroid-major (each centroid's dims
 * contiguous → the scalar scan streams through cache). Points are grouped by cell,
 * contiguous in [store] (int16 logical codes; the int16 scheme reserves a negative
 * sentinel so the stored Short IS its own code), [offsets] delimits each cell, and
 * [labels] is parallel to the points. The hot path is allocation-free (pooled
 * per-thread [Scratch], reset per query — safe: a search is synchronous and
 * non-reentrant, and ActiveProcessorCount=1 keeps the CIO worker count tiny).
 */
class IvfIndex internal constructor(
    internal val centroids: FloatArray,     // k*dim, centroid-major (cell centroids)
    internal val offsets: IntArray,         // size k+1; cell c occupies [offsets[c], offsets[c+1])
    internal val store: ShortArray,         // n*dim int16, grouped by cell
    internal val labels: BooleanArray,      // size n
    internal val dim: Int,
    internal val k: Int,
    internal val metaCentroids: FloatArray, // k1*dim, centroid-major (district centroids)
    internal val k1: Int,
    internal val metaOfCell: IntArray,      // size k; cell -> its district
    nprobe1: Int = DEFAULT_NPROBE1,
    nprobe2: Int = DEFAULT_NPROBE2,
) : VectorIndex {

    private val np1 = nprobe1.coerceIn(1, k1)
    private val np2 = nprobe2.coerceIn(1, k)

    /** Cells belonging to each district, built once from [metaOfCell]. */
    private val metaMembers: Array<IntArray> = run {
        val lists = Array(k1) { ArrayList<Int>() }
        for (c in 0 until k) lists[metaOfCell[c]].add(c)
        Array(k1) { lists[it].toIntArray() }
    }

    private val scratch = ThreadLocal.withInitial { Scratch(dim, np1, np2, K_NEIGHBORS) }

    override fun nearestFraudCount(query: DoubleArray): Int {
        val s = scratch.get()
        val codes = s.codes
        for (d in 0 until dim) codes[d] = quantizeToLogicalCode(query[d])

        // Level 1: the np1 nearest districts.
        selectNearestAll(codes, metaCentroids, k1, np1, s.metaDist, s.metaIdx)

        // Level 2: rank the member cells of those districts, keep the np2 nearest.
        resetTop(s.cellDist, s.cellIdx, np2)
        for (pi in 0 until np1) {
            for (cell in metaMembers[s.metaIdx[pi]]) {
                offerTop(distSq(codes, centroids, cell * dim), cell, s.cellDist, s.cellIdx, np2)
            }
        }

        // Level 3: scan the np2 nearest cells' points.
        val knn = s.knn.also { it.reset() }
        for (ri in 0 until np2) {
            if (s.cellDist[ri] == Double.MAX_VALUE) continue
            val cell = s.cellIdx[ri]
            val end = offsets[cell + 1]
            var p = offsets[cell]
            while (p < end) {
                knn.offer(squaredDistance(codes, store, p * dim, dim).toDouble(), labels[p])
                p++
            }
        }
        return knn.fraudCount()
    }

    /** Distance² from query codes to a centroid-major centroid at [base] (float). */
    private fun distSq(codes: IntArray, arr: FloatArray, base: Int): Double {
        var sum = 0.0
        var idx = base
        for (d in 0 until dim) {
            val diff = codes[d] - arr[idx]
            sum += diff * diff
            idx++
        }
        return sum
    }

    /** Scans all [count] centroids in [arr], filling the [keep] nearest into [outIdx]/[outDist]. */
    private fun selectNearestAll(codes: IntArray, arr: FloatArray, count: Int, keep: Int, outDist: DoubleArray, outIdx: IntArray) {
        resetTop(outDist, outIdx, keep)
        for (c in 0 until count) offerTop(distSq(codes, arr, c * dim), c, outDist, outIdx, keep)
    }

    private fun resetTop(dist: DoubleArray, idx: IntArray, keep: Int) {
        for (i in 0 until keep) {
            dist[i] = Double.MAX_VALUE
            idx[i] = 0
        }
    }

    /** Insertion-sorts ([d], [id]) into the [keep]-sized nearest ranking. */
    private fun offerTop(d: Double, id: Int, dist: DoubleArray, idx: IntArray, keep: Int) {
        if (d >= dist[keep - 1]) return
        var i = keep - 1
        while (i > 0 && dist[i - 1] > d) {
            dist[i] = dist[i - 1]
            idx[i] = idx[i - 1]
            i--
        }
        dist[i] = d
        idx[i] = id
    }

    /** Per-thread reusable scratch — keeps the search hot path allocation-free. */
    private class Scratch(dim: Int, np1: Int, np2: Int, k: Int) {
        val codes = IntArray(dim)
        val metaDist = DoubleArray(np1)
        val metaIdx = IntArray(np1)
        val cellDist = DoubleArray(np2)
        val cellIdx = IntArray(np2)
        val knn = KNearest(k)
    }
}
