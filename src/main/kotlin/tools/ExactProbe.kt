package dev.santo.tools

import dev.santo.fraud.K_NEIGHBORS
import dev.santo.search.IvfReader
import dev.santo.search.quantizeToLogicalCode
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.ForkJoinPool
import java.util.stream.IntStream
import kotlin.math.log10
import kotlin.math.max

/**
 * Measures the EXACT bbox branch-and-bound search (the one in `search.IvfIndex`) over
 * the cached 3M IVF index, against the randomized-date oracle. The decisive question
 * before shipping v1.7.0: does the per-cell/per-district bounding-box prune keep the
 * scanned-point count low on the dim5-saturated randomized-date tail, or does the prune
 * collapse there and the exact scan blow up the p99?
 *
 * Reports POINTS SCANNED (mean/p99/max) — the SIMD scan cost, the p99 driver — plus
 * cells scanned and the weighted detection error E (must be ~0 by construction). This
 * mirrors IvfIndex's algorithm exactly (same seed, same admissible bound), counting work
 * instead of using SIMD. CPU-capped via the parallelism arg so it never freezes the box.
 *
 * Usage: ./gradlew exactProbe -Pargs="build/ivf-k4096-i15.bin build/gold-N5000-fr0.5.bin 5000 [parallelism]"
 */
fun main(args: Array<String>) {
    val indexPath = args.getOrNull(0) ?: "build/ivf-k4096-i15.bin"
    val goldPath = args.getOrNull(1) ?: "build/gold-N5000-fr0.5.bin"
    val numQueries = args.getOrNull(2)?.toInt() ?: 5000
    val cores = Runtime.getRuntime().availableProcessors()
    val parallelism = args.getOrNull(3)?.toInt() ?: max(2, cores / 4)
    val fraudRatio = 0.5
    println("CPU cap: $parallelism of $cores cores.")

    println("Loading cached IVF index $indexPath ...")
    val index = File(indexPath).inputStream().buffered().use { IvfReader.readFrom(it) }
    val probe = ExactBboxProbe(index)
    println("Index: k=${index.k} k1=${index.k1} n=${index.offsets[index.k]} | districts=${index.k1}")

    val oracle = OfficialOracle(fraudRatio = fraudRatio)
    val queries = Array(numQueries) { oracle.nextVector() }
    val gold = DataInputStream(File(goldPath).inputStream().buffered()).use { inp -> IntArray(inp.readInt()) { inp.readInt() } }
    require(gold.size >= numQueries) { "gold has ${gold.size} entries < $numQueries queries" }

    val fp = IntArray(numQueries)
    val fn = IntArray(numQueries)
    val pts = IntArray(numQueries)
    val cells = IntArray(numQueries)
    val pool = ForkJoinPool(parallelism)
    pool.submit {
        IntStream.range(0, numQueries).parallel().forEach { qi ->
            val r = probe.search(queries[qi])
            val pred = r.fraud >= 3
            val gld = gold[qi] >= 3
            if (pred && !gld) fp[qi] = 1
            if (!pred && gld) fn[qi] = 1
            pts[qi] = r.pointsScanned
            cells[qi] = r.cellsScanned
        }
    }.get()
    pool.shutdown()

    val FP = fp.sum(); val FN = fn.sum(); val e = FP + 3 * FN
    val scale = 54100.0 / numQueries
    val det = detScore(e * scale, e.toDouble() / numQueries)
    println()
    println("EXACT bbox branch-and-bound over 3M (randomized-date queries, N=$numQueries):")
    println("  detection: FP=$FP FN=$FN  weighted E=$e (contest-scaled ${"%.0f".format(e * scale)})  det_score≈${"%.0f".format(det)}")
    println("  points scanned/query:  mean=${mean(pts)}  p99=${p99(pts)}  max=${pts.max()}")
    println("  cells scanned/query:   mean=${mean(cells)}  p99=${p99(cells)}  max=${cells.max()}")
    println()
    println("Sustain check: #7644 (16/12, ~9k pts) ran p99 5.6ms. If p99 points ≲ ~25k the exact")
    println("search sustains (SIMD ~6.5x); if p99 ≫ 50k the dim5 tail saturates → need cell-splitting.")
}

private class ProbeResult(val fraud: Int, val pointsScanned: Int, val cellsScanned: Int)

/** Faithful, instrumented re-implementation of search.IvfIndex's exact bbox search. */
private class ExactBboxProbe(private val index: dev.santo.search.IvfIndex) {
    private val dim = index.dim
    private val k = index.k
    private val k1 = index.k1
    private val centroids = index.centroids
    private val metaCentroids = index.metaCentroids
    private val offsets = index.offsets

    private val members: Array<IntArray> = run {
        val lists = Array(k1) { ArrayList<Int>() }
        for (c in 0 until k) lists[index.metaOfCell[c]].add(c)
        Array(k1) { lists[it].toIntArray() }
    }
    private val cellMin = ShortArray(k * dim)
    private val cellMax = ShortArray(k * dim)
    private val distMin = ShortArray(k1 * dim)
    private val distMax = ShortArray(k1 * dim)

    init {
        for (c in 0 until k) {
            val base = c * dim
            for (d in 0 until dim) { cellMin[base + d] = Short.MAX_VALUE; cellMax[base + d] = Short.MIN_VALUE }
            val count = offsets[c + 1] - offsets[c]
            for (w in 0 until count) for (d in 0 until dim) {
                val v = index.codeAt(c, w, d).toShort()
                if (v < cellMin[base + d]) cellMin[base + d] = v
                if (v > cellMax[base + d]) cellMax[base + d] = v
            }
        }
        for (m in 0 until k1) {
            val mb = m * dim
            for (d in 0 until dim) { distMin[mb + d] = Short.MAX_VALUE; distMax[mb + d] = Short.MIN_VALUE }
            for (cell in members[m]) {
                if (offsets[cell + 1] - offsets[cell] == 0) continue
                val cb = cell * dim
                for (d in 0 until dim) {
                    if (cellMin[cb + d] < distMin[mb + d]) distMin[mb + d] = cellMin[cb + d]
                    if (cellMax[cb + d] > distMax[mb + d]) distMax[mb + d] = cellMax[cb + d]
                }
            }
        }
    }

    fun search(query: DoubleArray): ProbeResult {
        val codes = IntArray(dim) { quantizeToLogicalCode(query[it]) }
        val bestSq = DoubleArray(K_NEIGHBORS) { Double.MAX_VALUE }
        val bestFraud = BooleanArray(K_NEIGHBORS)
        var points = 0
        var cellsScanned = 0

        val seedDistrict = nearestDistrict(codes)
        val seedCell = if (seedDistrict >= 0) nearestCellIn(seedDistrict, codes) else -1
        if (seedCell >= 0) { points += scanCell(seedCell, codes, bestSq, bestFraud); cellsScanned++ }
        var worst = bestSq[K_NEIGHBORS - 1]

        for (district in 0 until k1) {
            if (!boxCanContain(codes, distMin, distMax, district * dim, worst)) continue
            for (cell in members[district]) {
                if (cell == seedCell) continue
                if (boxCanContain(codes, cellMin, cellMax, cell * dim, worst)) {
                    points += scanCell(cell, codes, bestSq, bestFraud)
                    cellsScanned++
                    worst = bestSq[K_NEIGHBORS - 1]
                }
            }
        }
        var f = 0; for (i in 0 until K_NEIGHBORS) if (bestFraud[i]) f++
        return ProbeResult(f, points, cellsScanned)
    }

    private fun scanCell(cell: Int, codes: IntArray, bestSq: DoubleArray, bestFraud: BooleanArray): Int {
        val count = offsets[cell + 1] - offsets[cell]
        for (w in 0 until count) {
            var s = 0L
            for (d in 0 until dim) { val diff = (codes[d] - index.codeAt(cell, w, d)).toLong(); s += diff * diff }
            val ds = s.toDouble()
            if (ds < bestSq[K_NEIGHBORS - 1]) {
                var i = K_NEIGHBORS - 1
                while (i > 0 && bestSq[i - 1] > ds) { bestSq[i] = bestSq[i - 1]; bestFraud[i] = bestFraud[i - 1]; i-- }
                bestSq[i] = ds; bestFraud[i] = index.labelAt(cell, w)
            }
        }
        return count
    }

    private fun nearestDistrict(codes: IntArray): Int {
        var best = -1; var bd = Double.MAX_VALUE
        for (m in 0 until k1) { val d = distSq(codes, metaCentroids, m * dim); if (d < bd) { bd = d; best = m } }
        return best
    }

    private fun nearestCellIn(district: Int, codes: IntArray): Int {
        var best = -1; var bd = Double.MAX_VALUE
        for (cell in members[district]) { val d = distSq(codes, centroids, cell * dim); if (d < bd) { bd = d; best = cell } }
        return best
    }

    private fun boxCanContain(codes: IntArray, lo: ShortArray, hi: ShortArray, base: Int, worst: Double): Boolean {
        var sum = 0L
        for (d in 0 until dim) {
            val q = codes[d]; val mn = lo[base + d].toInt(); val mx = hi[base + d].toInt()
            val delta = if (q < mn) mn - q else if (q > mx) q - mx else 0
            if (delta != 0) { sum += delta.toLong() * delta; if (sum > worst) return false }
        }
        return true
    }

    private fun distSq(codes: IntArray, arr: FloatArray, base: Int): Double {
        var sum = 0.0; var idx = base
        for (d in 0 until dim) { val diff = codes[d] - arr[idx]; sum += diff * diff; idx++ }
        return sum
    }
}

private fun mean(a: IntArray): Int = if (a.isEmpty()) 0 else (a.sumOf { it.toLong() } / a.size).toInt()
private fun p99(a: IntArray): Int { val s = a.clone(); s.sort(); return s[(s.size * 99 / 100).coerceIn(0, s.size - 1)] }
private fun detScore(e: Double, eps: Double): Double = 1000.0 * log10(1.0 / max(eps, 0.001)) - 300.0 * log10(1.0 + e)
