package dev.santo.search

import dev.santo.fraud.K_NEIGHBORS

/**
 * Default number of cells probed per query; env-overridable via `NPROBE` (see
 * bootstrap.IndexLoader). Calibrated offline over the 3M refs with randomized-date
 * queries (tools.IvfCalibrate): at k=4096, recall stops improving past ~16 probes —
 * the residual error is the int16 quantization floor, not truncation — so 16 buys
 * exact-quality detection at the lowest scan cost.
 */
const val DEFAULT_NPROBE = 16

/**
 * IVF (inverted file) index. The references are partitioned offline into [k] cells
 * by k-means (`tools.KMeans`); a query is matched only against the [nprobe] cells
 * whose centroid is nearest to it, so cost is ~`nprobe·(n/k)` distance evaluations
 * instead of the full `n`.
 *
 * Unlike the bucketed VP-tree this REPLACES, IVF cells follow the data rather than
 * a metric-pruning bound, so it does NOT degenerate on the dim5-saturated
 * randomized-date tail (last_tx far in the past → dim5 = 1.0, a region empty of
 * refs → flat distance landscape → VP pruning collapses). That degeneracy was the
 * detection↔CPU wall; IVF removes it, giving exact-quality recall cheaply.
 *
 * Layout: [centroids] are centroid-major (`centroids[c*dim + d]`) so the scalar
 * per-centroid scan streams each centroid's 14 dims contiguously through cache
 * (the dim-major layout strided by k and caused the #7422 saturation). Points
 * are grouped by cell, contiguous in [store] (int16 logical codes, the int16
 * scheme reserves a negative sentinel so the stored Short IS its own code), with
 * [offsets] delimiting each cell's slice and [labels] parallel to the points.
 *
 * At `nprobe == k` every cell is scanned, so the result is identical to exact
 * quantized brute force — recall is exact in that limit. The hot path is
 * zero-allocation: per-thread scratch ([Scratch]) is pooled and reset per query
 * (safe — a search is synchronous and non-reentrant, with no coroutine suspension
 * mid-search, and ActiveProcessorCount=1 keeps the CIO worker count tiny).
 */
class IvfIndex internal constructor(
    internal val centroids: FloatArray, // k*dim, dimension-major: centroids[d*k + c]
    internal val offsets: IntArray,     // size k+1; cell c occupies [offsets[c], offsets[c+1])
    internal val store: ShortArray,     // n*dim int16, grouped by cell
    internal val labels: BooleanArray,  // size n
    internal val dim: Int,
    internal val k: Int,
    nprobe: Int = DEFAULT_NPROBE,
) : VectorIndex {

    private val probeCount = nprobe.coerceIn(1, k)
    private val scratch = ThreadLocal.withInitial { Scratch(dim, probeCount, K_NEIGHBORS) }

    override fun nearestFraudCount(query: DoubleArray): Int {
        val s = scratch.get()
        val codes = s.codes
        for (d in 0 until dim) codes[d] = quantizeToLogicalCode(query[d])

        selectCells(codes, probeCount, s.cellDist, s.cell)
        val knn = s.knn.also { it.reset() }
        for (pi in 0 until probeCount) {
            val cell = s.cell[pi]
            val end = offsets[cell + 1]
            var p = offsets[cell]
            while (p < end) {
                knn.offer(squaredDistance(codes, store, p * dim, dim).toDouble(), labels[p])
                p++
            }
        }
        return knn.fraudCount()
    }

    /** A view sharing this index's arrays but probing a different number of cells (calibration). */
    fun reprobe(nprobe: Int): IvfIndex = IvfIndex(centroids, offsets, store, labels, dim, k, nprobe)

    /** The cells this index would probe for [query], for offline cost measurement. */
    internal fun nearestCellsForCalibration(query: DoubleArray): IntArray {
        val codes = IntArray(dim) { quantizeToLogicalCode(query[it]) }
        val outDist = DoubleArray(probeCount)
        val outCell = IntArray(probeCount)
        selectCells(codes, probeCount, outDist, outCell)
        return outCell
    }

    /**
     * Fills [outCell] with the [count] cells whose centroid is closest to the query
     * (nearest first) and [outDist] with their squared distances. Distance is in
     * float against the (fractional) centroid means; the per-cell point scan then
     * uses exact integer codes. Both outputs are caller-provided so the hot path can
     * pool them.
     */
    private fun selectCells(codes: IntArray, count: Int, outDist: DoubleArray, outCell: IntArray) {
        for (i in 0 until count) {
            outDist[i] = Double.MAX_VALUE
            outCell[i] = 0
        }
        for (c in 0 until k) {
            var sum = 0.0
            var idx = c * dim // centroid-major: centroids[c*dim + d], 14 dims contiguous
            for (d in 0 until dim) {
                val diff = codes[d] - centroids[idx]
                sum += diff * diff
                idx++
            }
            if (sum >= outDist[count - 1]) continue
            var i = count - 1
            while (i > 0 && outDist[i - 1] > sum) {
                outDist[i] = outDist[i - 1]
                outCell[i] = outCell[i - 1]
                i--
            }
            outDist[i] = sum
            outCell[i] = c
        }
    }

    /** Per-thread reusable scratch — keeps the search hot path allocation-free. */
    private class Scratch(dim: Int, probeCount: Int, k: Int) {
        val codes = IntArray(dim)
        val cellDist = DoubleArray(probeCount)
        val cell = IntArray(probeCount)
        val knn = KNearest(k)
    }
}
