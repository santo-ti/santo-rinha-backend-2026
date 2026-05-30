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
 * Layout: [centroids] are dimension-major (`centroids[d*k + c]`) so the
 * query-to-all-centroids pass reads each dimension's column contiguously. Points
 * are grouped by cell, contiguous in [store] (int16 logical codes, the int16
 * scheme reserves a negative sentinel so the stored Short IS its own code), with
 * [offsets] delimiting each cell's slice and [labels] parallel to the points.
 *
 * At `nprobe == k` every cell is scanned, so the result is identical to exact
 * quantized brute force — recall is exact in that limit.
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

    override fun nearestFraudCount(query: DoubleArray): Int {
        val codes = IntArray(dim) { quantizeToLogicalCode(query[it]) }
        val probes = nearestCells(codes, probeCount)
        val knn = KNearest(K_NEIGHBORS)
        for (pi in probes.indices) {
            val cell = probes[pi]
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
    internal fun nearestCellsForCalibration(query: DoubleArray): IntArray =
        nearestCells(IntArray(dim) { quantizeToLogicalCode(query[it]) }, probeCount)

    /**
     * The [count] cells whose centroid is closest to the query, nearest first.
     * Distance is computed in float against the (fractional) centroid means; the
     * per-cell point scan then uses exact integer codes.
     */
    private fun nearestCells(codes: IntArray, count: Int): IntArray {
        val bestDist = DoubleArray(count) { Double.MAX_VALUE }
        val bestCell = IntArray(count)
        for (c in 0 until k) {
            var sum = 0.0
            var idx = c // dimension-major: centroids[d*k + c]
            for (d in 0 until dim) {
                val diff = codes[d] - centroids[idx]
                sum += diff * diff
                idx += k
            }
            if (sum >= bestDist[count - 1]) continue
            var i = count - 1
            while (i > 0 && bestDist[i - 1] > sum) {
                bestDist[i] = bestDist[i - 1]
                bestCell[i] = bestCell[i - 1]
                i--
            }
            bestDist[i] = sum
            bestCell[i] = c
        }
        return bestCell
    }
}
