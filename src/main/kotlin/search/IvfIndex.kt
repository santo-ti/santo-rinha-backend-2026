package dev.santo.search

import dev.santo.fraud.K_NEIGHBORS

/** District count (level-1 meta-centroids over the cell centroids). */
const val DEFAULT_META_CELLS = 64

/**
 * Kept for API/format compatibility (the build tools and env still pass them), but the
 * search is EXACT and does not gate on a fixed probe count — a query visits every
 * district/cell whose bounding box could still hold a closer neighbor. Ignored by search.
 */
const val DEFAULT_NPROBE1 = 4
const val DEFAULT_NPROBE2 = 6

/**
 * Two-level IVF (inverted file) index with an EXACT bbox branch-and-bound search.
 *
 * Point storage is ROW-MAJOR (santannaf's layout): each cell's points are packed
 * contiguously and within a point the [dim] int16 codes are ADJACENT in memory
 * (`rows[i*dim + d]`). So scanning a cell walks memory sequentially and a point's whole
 * vector lives in one cache line — vs the old SoA-16 block store, whose scalar scan
 * strode by 16 shorts per dimension (~[dim]× the cache lines per point). Under the
 * 0.45-CPU / 900-rps saturation that per-point memory traffic is the dominant per-request
 * CPU cost, so the contiguous layout is the real latency lever. The cell scan is a tight
 * per-dimension early-exit loop (drop a far point as soon as its partial squared distance
 * reaches the current 5th-NN — bit-identical to a full-distance scan → E unchanged).
 *
 * The per-cell / per-district bounding boxes are derived from [rows] at construction; the
 * exact branch-and-bound skips a cell only when its box's nearest possible point is already
 * farther than the current 5th neighbor (admissible → equals a full brute-force top-5).
 */
class IvfIndex internal constructor(
    internal val centroids: FloatArray,      // k*dim, centroid-major (cell centroids)
    internal val offsets: IntArray,          // size k+1; cumulative point counts (row range per cell)
    internal val rows: ShortArray,           // n*dim int16, ROW-MAJOR, grouped by cell
    internal val labels: BooleanArray,       // size n; fraud label per row (cell order)
    internal val dim: Int,
    internal val k: Int,
    internal val metaCentroids: FloatArray,  // k1*dim, centroid-major (district centroids)
    internal val k1: Int,
    internal val metaOfCell: IntArray,       // size k; cell -> its district
    @Suppress("UNUSED_PARAMETER") nprobe1: Int = DEFAULT_NPROBE1,
    @Suppress("UNUSED_PARAMETER") nprobe2: Int = DEFAULT_NPROBE2,
) : VectorIndex {

    /** Cells belonging to each district, built once from [metaOfCell]. */
    private val metaMembers: Array<IntArray> = run {
        val lists = Array(k1) { ArrayList<Int>() }
        for (c in 0 until k) lists[metaOfCell[c]].add(c)
        Array(k1) { lists[it].toIntArray() }
    }

    // Per-cell AABB over the cell's real int16 points; per-district union AABB.
    private val cellBboxMin = ShortArray(k * dim)
    private val cellBboxMax = ShortArray(k * dim)
    private val districtBboxMin = ShortArray(k1 * dim)
    private val districtBboxMax = ShortArray(k1 * dim)

    init {
        buildBoundingBoxes()
    }

    private val scratch = ThreadLocal.withInitial { Scratch(dim, K_NEIGHBORS) }

    /**
     * Optional work-cap (env `IVF_POINT_CAP`, 0/unset = disabled): abort the branch-and-bound
     * once this many points have been scanned. Unset keeps the fully-exact 0-error path.
     */
    private val pointCap = System.getenv("IVF_POINT_CAP")?.toIntOrNull()?.takeIf { it > 0 } ?: Int.MAX_VALUE

    override fun nearestFraudCount(query: DoubleArray): Int {
        val s = scratch.get()
        val codes = s.codes
        for (d in 0 until dim) codes[d] = quantizeToLogicalCode(query[d])

        val knn = s.knn.also { it.reset() }

        // Seed from the nearest district -> nearest member cell so `worst` (the 5th-NN
        // squared distance) is tight immediately and the box prune rejects almost everything.
        val seedDistrict = nearestDistrict(codes)
        val seedCell = if (seedDistrict >= 0) nearestCellIn(seedDistrict, codes) else -1
        var points = if (seedCell >= 0) scanCell(seedCell, codes, knn) else 0
        var worst = knn.worst()

        if (points < pointCap) {
            run {
                for (district in 0 until k1) {
                    if (!districtCanContain(codes, district, worst)) continue
                    for (cell in metaMembers[district]) {
                        if (cell == seedCell) continue
                        if (cellCanContain(codes, cell, worst)) {
                            points += scanCell(cell, codes, knn)
                            worst = knn.worst()
                            if (points >= pointCap) return@run
                        }
                    }
                }
            }
        }
        return knn.fraudCount()
    }

    /**
     * Scans every real point of [cell] sequentially over the ROW-MAJOR [rows] (point `i`'s
     * [dim] codes at `i*dim .. i*dim+dim`), with a per-dimension early-exit: stop summing a
     * far point as soon as its partial squared distance reaches the current 5th-NN ([worst]),
     * since [KNearest.offer] would reject anything `>= worst` — bit-identical to a full scan.
     * `worst` is hoisted and refreshed only when a point is actually offered.
     */
    private fun scanCell(cell: Int, codes: IntArray, knn: KNearest): Int {
        val from = offsets[cell]
        val to = offsets[cell + 1]
        if (from >= to) return 0
        val r = rows
        val d = dim
        var worst = knn.worst()
        var i = from
        while (i < to) {
            var base = i * d
            val end = base + d
            var sum = 0L
            var ci = 0
            while (base < end) {
                val diff = (codes[ci] - r[base].toInt()).toLong()
                sum += diff * diff
                if (sum >= worst) break
                base++; ci++
            }
            if (base == end) {
                knn.offer(sum.toDouble(), labels[i])
                worst = knn.worst()
            }
            i++
        }
        return to - from
    }

    /** Nearest district (level-1 meta-centroid) to [codes], or -1 if there are none. */
    private fun nearestDistrict(codes: IntArray): Int {
        var best = -1
        var bd = Double.MAX_VALUE
        for (m in 0 until k1) {
            val d = distSq(codes, metaCentroids, m * dim)
            if (d < bd) { bd = d; best = m }
        }
        return best
    }

    /** Nearest member cell of [district] to [codes], or -1 if the district is empty. */
    private fun nearestCellIn(district: Int, codes: IntArray): Int {
        var best = -1
        var bd = Double.MAX_VALUE
        for (cell in metaMembers[district]) {
            val d = distSq(codes, centroids, cell * dim)
            if (d < bd) { bd = d; best = cell }
        }
        return best
    }

    private fun cellCanContain(codes: IntArray, cell: Int, worst: Double): Boolean =
        boxCanContain(codes, cellBboxMin, cellBboxMax, cell * dim, worst)

    private fun districtCanContain(codes: IntArray, district: Int, worst: Double): Boolean =
        boxCanContain(codes, districtBboxMin, districtBboxMax, district * dim, worst)

    private fun boxCanContain(codes: IntArray, lo: ShortArray, hi: ShortArray, base: Int, worst: Double): Boolean {
        var sum = 0L
        for (d in 0 until dim) {
            val q = codes[d]
            val mn = lo[base + d].toInt()
            val mx = hi[base + d].toInt()
            val delta = if (q < mn) mn - q else if (q > mx) q - mx else 0
            if (delta != 0) {
                sum += delta.toLong() * delta
                if (sum > worst) return false
            }
        }
        return true
    }

    /** Computes per-cell AABBs from the real points, then per-district union AABBs. */
    private fun buildBoundingBoxes() {
        for (c in 0 until k) {
            val cb = c * dim
            for (d in 0 until dim) {
                cellBboxMin[cb + d] = Short.MAX_VALUE
                cellBboxMax[cb + d] = Short.MIN_VALUE
            }
            var i = offsets[c]
            val to = offsets[c + 1]
            while (i < to) {
                val base = i * dim
                for (d in 0 until dim) {
                    val v = rows[base + d]
                    if (v < cellBboxMin[cb + d]) cellBboxMin[cb + d] = v
                    if (v > cellBboxMax[cb + d]) cellBboxMax[cb + d] = v
                }
                i++
            }
        }
        for (m in 0 until k1) {
            val mb = m * dim
            for (d in 0 until dim) {
                districtBboxMin[mb + d] = Short.MAX_VALUE
                districtBboxMax[mb + d] = Short.MIN_VALUE
            }
            for (cell in metaMembers[m]) {
                if (offsets[cell + 1] - offsets[cell] == 0) continue
                val cb = cell * dim
                for (d in 0 until dim) {
                    if (cellBboxMin[cb + d] < districtBboxMin[mb + d]) districtBboxMin[mb + d] = cellBboxMin[cb + d]
                    if (cellBboxMax[cb + d] > districtBboxMax[mb + d]) districtBboxMax[mb + d] = cellBboxMax[cb + d]
                }
            }
        }
    }

    /** int16 code of the [within]-th point of [cell] in dimension [d] (offline-tool access). */
    internal fun codeAt(cell: Int, within: Int, d: Int): Int = rows[(offsets[cell] + within) * dim + d].toInt()

    /** Fraud label of the [within]-th point of [cell] (offline-tool access). */
    internal fun labelAt(cell: Int, within: Int): Boolean = labels[offsets[cell] + within]

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

    /** Per-thread reusable scratch — keeps the search hot path allocation-free. */
    private class Scratch(dim: Int, k: Int) {
        val codes = IntArray(dim)
        val knn = KNearest(k)
    }
}
