package dev.santo.search

import dev.santo.fraud.K_NEIGHBORS

/** District count (level-1 meta-centroids over the cell centroids). */
const val DEFAULT_META_CELLS = 64

/**
 * Kept for API/format compatibility (the build tools and env still pass them), but the
 * search is now EXACT and does not gate on a fixed probe count — see [IvfIndex]. A query
 * visits every district/cell whose bounding box could still hold a closer neighbor, so
 * recall no longer depends on these. They are ignored by [IvfIndex.nearestFraudCount].
 */
const val DEFAULT_NPROBE1 = 4
const val DEFAULT_NPROBE2 = 6

/**
 * Two-level IVF (inverted file) index with an EXACT branch-and-bound search. References
 * are partitioned offline into [k] cells by k-means; the cell centroids are themselves
 * clustered into [k1] districts (meta-centroids).
 *
 * WHY EXACT (vs the old fixed nprobe): the previous search probed a fixed NPROBE1
 * districts → NPROBE2 cells and stopped, so when the true 5-NN sat in an unprobed cell
 * the result was wrong (~37 weighted contest errors). This version mirrors the top entry
 * (santannaf): it keeps a per-cell axis-aligned bounding box (AABB) and a per-district
 * union box, seeds the result from the nearest cell to get a tight 5th-NN radius, then
 * visits EVERY district/cell — skipping one only when its box's closest possible point is
 * already farther than the current 5th neighbor. That bound is admissible, so a skipped
 * cell provably cannot hold a top-5 neighbor: the answer equals a full brute-force top-5
 * over all [k] cells (exact in int16 space) → zero routing misses. The boxes prune almost
 * everything, so the exact scan still touches only a few cells per query.
 *
 * The bounding boxes are DERIVED from [blocks] at construction (the real points per cell),
 * so the on-disk artifact format is unchanged — [IvfReader] builds the same object.
 *
 * Point storage is SoA-16 BLOCKS (see [BlockDistance]): each cell's points are packed
 * into blocks of [BlockDistance.BLOCK], dimension-major within a block, so the cell scan
 * computes 16 squared distances per SIMD pass (~6.5× on an AVX2 native image). [offsets]
 * keeps the logical (real) per-cell point counts; [blockOffsets] delimits each cell's
 * blocks; the trailing partial block is padded and the padding slots are masked out. The
 * hot path is allocation-free (pooled per-thread [Scratch], reset per query).
 */
class IvfIndex internal constructor(
    internal val centroids: FloatArray,      // k*dim, centroid-major (cell centroids)
    internal val offsets: IntArray,          // size k+1; cumulative REAL point counts (logical)
    internal val blocks: ShortArray,         // totalBlocks*dim*BLOCK int16, SoA-16, grouped by cell
    internal val blockOffsets: IntArray,     // size k+1; cell c occupies blocks [blockOffsets[c], blockOffsets[c+1])
    internal val blockLabels: BooleanArray,  // size totalBlocks*BLOCK; padding slots = false
    internal val dim: Int,
    internal val k: Int,
    internal val metaCentroids: FloatArray,  // k1*dim, centroid-major (district centroids)
    internal val k1: Int,
    internal val metaOfCell: IntArray,       // size k; cell -> its district
    @Suppress("UNUSED_PARAMETER") nprobe1: Int = DEFAULT_NPROBE1,
    @Suppress("UNUSED_PARAMETER") nprobe2: Int = DEFAULT_NPROBE2,
) : VectorIndex {

    private val blockStride = dim * BlockDistance.BLOCK

    /** Cells belonging to each district, built once from [metaOfCell]. */
    private val metaMembers: Array<IntArray> = run {
        val lists = Array(k1) { ArrayList<Int>() }
        for (c in 0 until k) lists[metaOfCell[c]].add(c)
        Array(k1) { lists[it].toIntArray() }
    }

    // Per-cell AABB over the cell's real int16 points (empty cells stay inverted MAX/MIN,
    // so their lower bound is huge and they are always pruned). Derived from [blocks].
    private val cellBboxMin = ShortArray(k * dim)
    private val cellBboxMax = ShortArray(k * dim)
    // Per-district union AABB (over its member cells), the cheap one-test district prune.
    private val districtBboxMin = ShortArray(k1 * dim)
    private val districtBboxMax = ShortArray(k1 * dim)

    init {
        buildBoundingBoxes()
    }

    private val scratch = ThreadLocal.withInitial { Scratch(dim, K_NEIGHBORS) }

    override fun nearestFraudCount(query: DoubleArray): Int {
        val s = scratch.get()
        val codes = s.codes
        for (d in 0 until dim) codes[d] = quantizeToLogicalCode(query[d])

        val knn = s.knn.also { it.reset() }

        // Seed: nearest district -> nearest member cell. Scanning it first makes `worst`
        // (the 5th-NN squared distance) tight immediately, so the box prune rejects almost
        // every other cell.
        val seedDistrict = nearestDistrict(codes)
        val seedCell = if (seedDistrict >= 0) nearestCellIn(seedDistrict, codes) else -1
        if (seedCell >= 0) scanCell(seedCell, codes, knn)
        var worst = knn.worst()

        // Exact branch-and-bound: visit every district/cell that its box says could still
        // hold a closer neighbor than the current 5th-NN.
        for (district in 0 until k1) {
            if (!districtCanContain(codes, district, worst)) continue
            for (cell in metaMembers[district]) {
                if (cell == seedCell) continue
                if (cellCanContain(codes, cell, worst)) {
                    scanCell(cell, codes, knn)
                    worst = knn.worst()
                }
            }
        }
        return knn.fraudCount()
    }

    /**
     * Scans all real points of [cell] with a per-dimension early-exit, offering each
     * surviving (distance², label) to [knn]. For a far point we stop summing as soon as the
     * partial squared distance reaches the current 5th-NN ([KNearest.worst]): the remaining
     * dimensions can only add to the sum and [KNearest.offer] rejects anything `>= worst`, so
     * that point provably cannot enter the top-5 and skipping it leaves the result unchanged
     * (bit-identical to scanning every dimension). On the exact search's heavy tail most
     * candidates are far, so the cutoff usually fires after a few of the 14 dimensions — much
     * cheaper than the SIMD block kernel, which always computes the full distance for all 16
     * points. Reads int16 codes straight from the SoA-16 [blocks] (dim d of `slot` lives at
     * `blockBase + d*BLOCK + slot`), so a block stays L1-resident across its points.
     */
    private fun scanCell(cell: Int, codes: IntArray, knn: KNearest) {
        var remaining = offsets[cell + 1] - offsets[cell]
        if (remaining == 0) return
        val block = BlockDistance.BLOCK
        val blkEnd = blockOffsets[cell + 1]
        var blk = blockOffsets[cell]
        while (blk < blkEnd) {
            val slots = if (remaining < block) remaining else block
            val blockBase = blk * blockStride
            val lbase = blk * block
            for (slot in 0 until slots) {
                val worst = knn.worst()
                var sum = 0L
                var idx = blockBase + slot
                var d = 0
                while (d < dim) {
                    val diff = (codes[d] - blocks[idx].toInt()).toLong()
                    sum += diff * diff
                    if (sum >= worst) break
                    idx += block
                    d++
                }
                if (d == dim) knn.offer(sum.toDouble(), blockLabels[lbase + slot])
            }
            remaining -= slots
            blk++
        }
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

    /**
     * True if [cell]'s bounding box could still hold a point closer than [worst]
     * (squared). Admissible lower bound: per dimension the gap to the box, squared and
     * summed, with early-exit once it exceeds [worst]. `worst == +inf` (fewer than K
     * seen) makes this always true, so nothing is wrongly skipped before K are found.
     */
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
        val block = BlockDistance.BLOCK
        for (c in 0 until k) {
            val base = c * dim
            for (d in 0 until dim) {
                cellBboxMin[base + d] = Short.MAX_VALUE
                cellBboxMax[base + d] = Short.MIN_VALUE
            }
            var remaining = offsets[c + 1] - offsets[c]
            var blk = blockOffsets[c]
            while (remaining > 0) {
                val slots = if (remaining < block) remaining else block
                val blockBase = blk * blockStride
                for (d in 0 until dim) {
                    var mn = cellBboxMin[base + d]
                    var mx = cellBboxMax[base + d]
                    val dimBase = blockBase + d * block
                    for (slot in 0 until slots) {
                        val v = blocks[dimBase + slot]
                        if (v < mn) mn = v
                        if (v > mx) mx = v
                    }
                    cellBboxMin[base + d] = mn
                    cellBboxMax[base + d] = mx
                }
                remaining -= slots
                blk++
            }
        }
        // District union: min/max over member cells (empty districts stay inverted).
        for (m in 0 until k1) {
            val mb = m * dim
            for (d in 0 until dim) {
                districtBboxMin[mb + d] = Short.MAX_VALUE
                districtBboxMax[mb + d] = Short.MIN_VALUE
            }
            for (cell in metaMembers[m]) {
                val cb = cell * dim
                if (offsets[cell + 1] - offsets[cell] == 0) continue
                for (d in 0 until dim) {
                    if (cellBboxMin[cb + d] < districtBboxMin[mb + d]) districtBboxMin[mb + d] = cellBboxMin[cb + d]
                    if (cellBboxMax[cb + d] > districtBboxMax[mb + d]) districtBboxMax[mb + d] = cellBboxMax[cb + d]
                }
            }
        }
    }

    /** int16 code of the [within]-th point of [cell] in dimension [d] (offline-tool access). */
    internal fun codeAt(cell: Int, within: Int, d: Int): Int {
        val blk = blockOffsets[cell] + within / BlockDistance.BLOCK
        return blocks[blk * blockStride + d * BlockDistance.BLOCK + within % BlockDistance.BLOCK].toInt()
    }

    /** Fraud label of the [within]-th point of [cell] (offline-tool access). */
    internal fun labelAt(cell: Int, within: Int): Boolean {
        val blk = blockOffsets[cell] + within / BlockDistance.BLOCK
        return blockLabels[blk * BlockDistance.BLOCK + within % BlockDistance.BLOCK]
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

    /** Per-thread reusable scratch — keeps the search hot path allocation-free. */
    private class Scratch(dim: Int, k: Int) {
        val codes = IntArray(dim)
        val knn = KNearest(k)
    }
}
