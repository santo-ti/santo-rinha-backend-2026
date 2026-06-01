package dev.santo.tools

import dev.santo.search.BlockDistance
import dev.santo.search.DEFAULT_META_CELLS
import dev.santo.search.DEFAULT_NPROBE1
import dev.santo.search.DEFAULT_NPROBE2
import dev.santo.search.IvfIndex
import dev.santo.search.LabeledVector
import dev.santo.search.quantizeVector
import dev.santo.vectorization.VECTOR_DIMENSIONS
import kotlin.random.Random

/**
 * Offline construction of a two-level [IvfIndex] from labeled references:
 * quantizes each vector (int16), runs [KMeans] to partition them into [k] cells,
 * packs the points CONTIGUOUSLY per cell, then clusters the cell centroids into
 * [metaCells] districts (a cheap second k-means over the [k] centroids) so the
 * runtime routes a query through ~[metaCells] districts instead of all [k]
 * centroids. CPU/RAM-heavy — runs once during the image build.
 */
object IvfBuilder {

    fun build(
        references: List<LabeledVector>,
        dim: Int = VECTOR_DIMENSIONS,
        k: Int = DEFAULT_CENTROIDS,
        iterations: Int = 20,
        metaCells: Int = DEFAULT_META_CELLS,
        nprobe1: Int = DEFAULT_NPROBE1,
        nprobe2: Int = DEFAULT_NPROBE2,
        maxCellSize: Int = DEFAULT_MAX_CELL_SIZE,
    ): IvfIndex {
        val n = references.size
        val coarseK = minOf(k, n)

        val srcStore = ShortArray(n * dim)
        val srcLabels = BooleanArray(n)
        for (i in 0 until n) {
            System.arraycopy(quantizeVector(references[i].vector), 0, srcStore, i * dim, dim)
            srcLabels[i] = references[i].isFraud
        }

        val km = KMeans.cluster(srcStore, n, dim, coarseK, iterations)

        // Split any cell larger than maxCellSize by a local sub-k-means (santannaf's
        // MAX_CLUSTER_SIZE). Smaller cells => tighter per-cell bounding boxes => the
        // exact branch-and-bound prunes far more on the dim5-saturated tail, keeping the
        // scanned-point p99 low (the whole point: zero error AND low latency).
        val (centroids, assignment, cellCount) =
            if (maxCellSize > 0) splitLargeCells(srcStore, n, dim, km, maxCellSize)
            else Triple(km.centroids, km.assignment, km.k)

        // Logical (real) per-cell point counts, cumulative.
        val offsets = IntArray(cellCount + 1)
        for (i in 0 until n) offsets[assignment[i] + 1]++
        for (c in 0 until cellCount) offsets[c + 1] += offsets[c]

        // Block offsets: each cell's points pad up to a multiple of BLOCK (SoA-16).
        val block = BlockDistance.BLOCK
        val blockOffsets = IntArray(cellCount + 1)
        for (c in 0 until cellCount) {
            val count = offsets[c + 1] - offsets[c]
            blockOffsets[c + 1] = blockOffsets[c] + (count + block - 1) / block
        }
        val totalBlocks = blockOffsets[cellCount]
        val blockStride = dim * block

        // Pack each point into its cell's blocks, dimension-major within a block.
        // Padding slots stay zero (codes) / false (labels) — masked at scan time.
        val blocks = ShortArray(totalBlocks * blockStride)
        val blockLabels = BooleanArray(totalBlocks * block)
        val within = IntArray(cellCount)
        for (i in 0 until n) {
            val c = assignment[i]
            val j = within[c]++
            val blk = blockOffsets[c] + j / block
            val slot = j % block
            val src = i * dim
            val base = blk * blockStride
            for (d in 0 until dim) blocks[base + d * block + slot] = srcStore[src + d]
            blockLabels[blk * block + slot] = srcLabels[i]
        }

        // Level-2: cluster the cell centroids into districts.
        val k1 = minOf(metaCells, cellCount)
        val (metaCentroids, metaOfCell) = clusterCentroids(centroids, cellCount, dim, k1)

        return IvfIndex(centroids, offsets, blocks, blockOffsets, blockLabels, dim, cellCount, metaCentroids, k1, metaOfCell, nprobe1, nprobe2)
    }

    /**
     * Splits every coarse cell with more than [maxCellSize] points into
     * `ceil(count / targetSubSize)` sub-cells via a local Lloyd k-means; cells already
     * within the cap pass through unchanged. Returns the final centroid-major centroids,
     * per-point assignment, and final cell count. Mirrors santannaf's `MAX_CLUSTER_SIZE`
     * split — it bounds each cell's spatial extent so the per-cell bounding box is tight,
     * which is what lets the exact branch-and-bound stay cheap on the saturated tail.
     */
    private fun splitLargeCells(
        srcStore: ShortArray,
        n: Int,
        dim: Int,
        km: KMeans.Result,
        maxCellSize: Int,
        targetSubSize: Int = maxCellSize / 2,
    ): Triple<FloatArray, IntArray, Int> {
        val pointsByCell = Array(km.k) { IntArray(0) }
        run {
            val counts = IntArray(km.k)
            for (i in 0 until n) counts[km.assignment[i]]++
            for (c in 0 until km.k) pointsByCell[c] = IntArray(counts[c])
            val fill = IntArray(km.k)
            for (i in 0 until n) { val c = km.assignment[i]; pointsByCell[c][fill[c]++] = i }
        }

        val finalCentroids = ArrayList<FloatArray>(km.k)
        val finalAssignment = IntArray(n)
        var kFinal = 0
        for (c in 0 until km.k) {
            val pts = pointsByCell[c]
            if (pts.size <= maxCellSize) {
                val id = kFinal++
                finalCentroids.add(meanCentroid(srcStore, pts, dim))
                for (p in pts) finalAssignment[p] = id
            } else {
                val nSub = (pts.size + targetSubSize - 1) / targetSubSize
                val subStore = ShortArray(pts.size * dim)
                for (j in pts.indices) System.arraycopy(srcStore, pts[j] * dim, subStore, j * dim, dim)
                val sub = KMeans.cluster(subStore, pts.size, dim, nSub, iterations = 10, seed = (c + 1).toLong())
                val base = kFinal
                kFinal += sub.k
                for (s in 0 until sub.k) {
                    val cb = FloatArray(dim)
                    System.arraycopy(sub.centroids, s * dim, cb, 0, dim)
                    finalCentroids.add(cb)
                }
                for (j in pts.indices) finalAssignment[pts[j]] = base + sub.assignment[j]
            }
        }
        val cent = FloatArray(kFinal * dim)
        for (c in 0 until kFinal) System.arraycopy(finalCentroids[c], 0, cent, c * dim, dim)
        println("Cell split: ${km.k} coarse cells -> $kFinal cells (cap $maxCellSize).")
        return Triple(cent, finalAssignment, kFinal)
    }

    private fun meanCentroid(srcStore: ShortArray, pts: IntArray, dim: Int): FloatArray {
        val cb = FloatArray(dim)
        if (pts.isEmpty()) return cb
        for (p in pts) { val base = p * dim; for (d in 0 until dim) cb[d] += srcStore[base + d] }
        val inv = 1.0f / pts.size
        for (d in 0 until dim) cb[d] *= inv
        return cb
    }

    /**
     * Float Lloyd k-means over the [count] cell centroids (centroid-major) → [k1]
     * district centroids (centroid-major) + each cell's district. Tiny (k≈4096
     * points), so a fixed iteration count is fine.
     */
    private fun clusterCentroids(src: FloatArray, count: Int, dim: Int, k1: Int): Pair<FloatArray, IntArray> {
        val meta = FloatArray(k1 * dim)
        val rng = Random(1L)
        val seen = HashSet<Int>()
        var c = 0
        while (c < k1) {
            val p = rng.nextInt(count)
            if (seen.add(p)) { System.arraycopy(src, p * dim, meta, c * dim, dim); c++ }
        }
        val assign = IntArray(count)
        repeat(25) {
            for (ci in 0 until count) {
                var best = 0
                var bd = Double.MAX_VALUE
                val cb = ci * dim
                for (m in 0 until k1) {
                    var s = 0.0
                    val mb = m * dim
                    for (d in 0 until dim) { val diff = (src[cb + d] - meta[mb + d]).toDouble(); s += diff * diff }
                    if (s < bd) { bd = s; best = m }
                }
                assign[ci] = best
            }
            val sum = DoubleArray(k1 * dim)
            val cnt = IntArray(k1)
            for (ci in 0 until count) {
                val m = assign[ci]; cnt[m]++; val cb = ci * dim; val mb = m * dim
                for (d in 0 until dim) sum[mb + d] += src[cb + d]
            }
            for (m in 0 until k1) {
                val mb = m * dim
                if (cnt[m] == 0) {
                    val p = rng.nextInt(count); System.arraycopy(src, p * dim, meta, mb, dim)
                } else {
                    val inv = 1.0 / cnt[m]; for (d in 0 until dim) meta[mb + d] = (sum[mb + d] * inv).toFloat()
                }
            }
        }
        return meta to assign
    }

    /** Default cell count. Tuned offline against the recall/cost curve. */
    const val DEFAULT_CENTROIDS = 4096

    /**
     * Cells larger than this are split by a local sub-k-means (santannaf's
     * MAX_CLUSTER_SIZE). Caps each cell's spatial extent so its bounding box is tight,
     * keeping the exact branch-and-bound's scanned-point count low on the saturated tail.
     * 0 disables splitting.
     */
    const val DEFAULT_MAX_CELL_SIZE = 1024
}
