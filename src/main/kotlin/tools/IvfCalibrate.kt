package dev.santo.tools

import dev.santo.fraud.K_NEIGHBORS
import dev.santo.search.IvfIndex
import dev.santo.search.LabeledVector
import dev.santo.search.quantizeToLogicalCode
import dev.santo.vectorization.VECTOR_DIMENSIONS
import java.io.File
import java.util.concurrent.ForkJoinPool
import java.util.zip.GZIPInputStream
import java.util.stream.IntStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Offline IVF recall/cost calibration. Builds an IVF over the real reference set,
 * generates randomized-date queries via [OfficialOracle], computes the float-exact
 * 5-NN gabarito, then reports — per `nprobe` — recall (FP/FN/E) AND the per-query
 * comparison count both WITHOUT and WITH ideal radius pruning (the triangle-inequality
 * annulus). The comparison count is what gates the 0.45-CPU contest budget: the IVF
 * saturated at ~16k comps/query (#7422/#7428, p99 cut); the +1804 VP-tree sustained
 * at ~1.8k mean. This measures, BEFORE shipping, whether pruning brings the IVF under
 * that bar — and whether it helps the dim5-saturated tail (where the annulus is wide).
 *
 * CPU-capped (leaves cores free) so it does not freeze the dev machine.
 *
 * Usage: ./gradlew ivfCalibrate -Pargs="build/refs-3m.json.gz [numQueries] [k] [iters] [fraudRatio] [nprobeCSV] [parallelism]"
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: IvfCalibrate <refs.json[.gz]> [numQueries=8000] [k=4096] [iters=20] [fraudRatio=0.5] [nprobeCSV] [parallelism]" }
    val refsPath = args[0]
    val numQueries = args.getOrNull(1)?.toInt() ?: 8000
    val k = args.getOrNull(2)?.toInt() ?: 4096
    val iters = args.getOrNull(3)?.toInt() ?: 20
    val fraudRatio = args.getOrNull(4)?.toDouble() ?: 0.5
    val nprobes = (args.getOrNull(5) ?: "8,16,32,64").split(",").map { it.trim().toInt() }
    val cores = Runtime.getRuntime().availableProcessors()
    val parallelism = args.getOrNull(6)?.toInt() ?: max(2, cores / 2)
    val pool = ForkJoinPool(parallelism)
    val dim = VECTOR_DIMENSIONS
    println("CPU cap: using $parallelism of $cores cores (the rest stay free for you).")

    println("Loading references from $refsPath ...")
    val refs = loadRefs(refsPath)
    val n = refs.size
    println("Loaded $n references")

    val gold = DoubleArray(n * dim)
    val goldLabel = BooleanArray(n)
    for (i in 0 until n) {
        System.arraycopy(refs[i].vector, 0, gold, i * dim, dim)
        goldLabel[i] = refs[i].isFraud
    }

    val cache = File("build/ivf-k$k-i$iters.bin")
    val index = if (cache.exists()) {
        println("Loading cached IVF from ${cache.path} ...")
        cache.inputStream().buffered().use { dev.santo.search.IvfReader.readFrom(it) }
    } else {
        println("Building IVF: k=$k iterations=$iters ...")
        val t0 = System.nanoTime()
        val built = pool.submit<IvfIndex> { IvfBuilder.build(refs, dim = dim, k = k, iterations = iters) }.get()
        println("IVF built in ${(System.nanoTime() - t0) / 1_000_000} ms (cells=${built.k})")
        cache.outputStream().buffered().use { IvfWriter.writeTo(built, it) }
        built
    }

    // Precompute each point's distance to its cell centroid (r_p) — constant per point,
    // the radius-pruning bound: dist(q,p) >= |D_cell - r_p|, so |D-r_p| >= W => skip p.
    println("Precomputing point→centroid radii ...")
    val radii = FloatArray(n)
    pool.submit { IntStream.range(0, index.k).parallel().forEach { c -> fillRadii(index, c, radii) } }.get()

    println("Generating $numQueries randomized-date queries (fraudRatio=$fraudRatio) ...")
    val oracle = OfficialOracle(fraudRatio = fraudRatio)
    val queries = Array(numQueries) { oracle.nextVector() }

    println("Computing float-exact gabarito over $n refs ...")
    val goldCount = IntArray(numQueries)
    pool.submit { IntStream.range(0, numQueries).parallel().forEach { qi -> goldCount[qi] = exactFraudCount(queries[qi], gold, goldLabel, n, dim) } }.get()
    var goldFraud = 0
    for (qi in 0 until numQueries) if (goldCount[qi] >= 3) goldFraud++
    println("Gabarito: fraud-labeled ${pct(goldFraud, numQueries)}")

    println()
    println("k=$k | comps = centroid($k) + points scanned. Target ≤ ~3000 (the +1804 VP-tree sustained ~1.8k).")
    println("nprobe |  FP   FN |  E  | det  | comps_noPrune(mean/p99) | comps_PRUNED(mean/p99) | →contest E")
    println("-------+----------+-----+------+-------------------------+------------------------+-----------")
    for (np in nprobes.filter { it in 1..index.k }.sorted()) {
        val fp = IntArray(numQueries); val fn = IntArray(numQueries)
        val rawComps = IntArray(numQueries); val prunedComps = IntArray(numQueries)
        pool.submit {
            IntStream.range(0, numQueries).parallel().forEach { qi ->
                val r = prunedSearch(index, queries[qi], np, radii)
                val pred = r.fraud >= 3; val gld = goldCount[qi] >= 3
                if (pred && !gld) fp[qi] = 1
                if (!pred && gld) fn[qi] = 1
                rawComps[qi] = r.rawComps
                prunedComps[qi] = r.prunedComps
            }
        }.get()
        val FP = fp.sum(); val FN = fn.sum()
        val e = FP + 3 * FN
        val scale = 54100.0 / numQueries
        val det = detScore(e * scale, e.toDouble() / numQueries)
        println(
            "%6d | %3d %3d | %3d | %4.0f | %10d %10d | %10d %10d | %6.0f".format(
                np, FP, FN, e, det,
                mean(rawComps), p99(rawComps), mean(prunedComps), p99(prunedComps), e * scale,
            )
        )
    }
    pool.shutdown()
    println()
    println("comps_PRUNED is the real cost if radius pruning is implemented. If it stays high on")
    println("the saturated tail (p99), pruning won't save us and we need SIMD or a coarser router.")
}

private class SearchResult(val fraud: Int, val rawComps: Int, val prunedComps: Int)

/**
 * IVF search that also counts comparisons with and without ideal radius pruning.
 * Cells are scanned nearest-centroid first so the worst-distance W shrinks fast; a
 * point is "pruned" when |D_cell - r_p| >= W (triangle inequality — it provably
 * cannot enter the top-k). Pruning is exact: the fraud count is identical with and
 * without it. rawComps counts every point in the probed cells; prunedComps counts
 * only the points whose distance is actually computed (centroid scan = k for both).
 */
private fun prunedSearch(index: IvfIndex, query: DoubleArray, nprobe: Int, radii: FloatArray): SearchResult {
    val dim = index.dim
    val codes = IntArray(dim) { quantizeToLogicalCode(query[it]) }
    val cells = nearestCells(index, codes, nprobe) // ascending by centroid distance
    val bestSq = DoubleArray(K_NEIGHBORS) { Double.MAX_VALUE }
    val bestFraud = BooleanArray(K_NEIGHBORS)
    var raw = 0; var pruned = index.k // centroid scan counts for both

    for (cell in cells) {
        val dCell = sqrt(centroidDistSq(index, codes, cell))
        val start = index.offsets[cell]; val end = index.offsets[cell + 1]
        var p = start
        while (p < end) {
            raw++
            val w = sqrt(bestSq[K_NEIGHBORS - 1])
            if (kotlin.math.abs(dCell - radii[p]) < w || bestSq[K_NEIGHBORS - 1] == Double.MAX_VALUE) {
                pruned++
                val d = pointDistSq(index, codes, p).toDouble()
                if (d < bestSq[K_NEIGHBORS - 1]) {
                    var i = K_NEIGHBORS - 1
                    while (i > 0 && bestSq[i - 1] > d) { bestSq[i] = bestSq[i - 1]; bestFraud[i] = bestFraud[i - 1]; i-- }
                    bestSq[i] = d; bestFraud[i] = index.labels[p]
                }
            }
            p++
        }
    }
    var frauds = 0
    for (i in 0 until K_NEIGHBORS) if (bestFraud[i]) frauds++
    return SearchResult(frauds, raw + index.k, pruned)
}

private fun nearestCells(index: IvfIndex, codes: IntArray, nprobe: Int): IntArray {
    val count = nprobe.coerceIn(1, index.k)
    val bestD = DoubleArray(count) { Double.MAX_VALUE }
    val bestC = IntArray(count)
    for (c in 0 until index.k) {
        val sum = centroidDistSq(index, codes, c)
        if (sum >= bestD[count - 1]) continue
        var i = count - 1
        while (i > 0 && bestD[i - 1] > sum) { bestD[i] = bestD[i - 1]; bestC[i] = bestC[i - 1]; i-- }
        bestD[i] = sum; bestC[i] = c
    }
    return bestC
}

private fun centroidDistSq(index: IvfIndex, codes: IntArray, c: Int): Double {
    var sum = 0.0; var idx = c * index.dim
    for (d in 0 until index.dim) { val diff = codes[d] - index.centroids[idx]; sum += diff * diff; idx++ }
    return sum
}

private fun pointDistSq(index: IvfIndex, codes: IntArray, p: Int): Long {
    var sum = 0L; val base = p * index.dim
    for (d in 0 until index.dim) { val diff = (codes[d] - index.store[base + d]).toLong(); sum += diff * diff }
    return sum
}

private fun fillRadii(index: IvfIndex, c: Int, radii: FloatArray) {
    val dim = index.dim
    val cb = c * dim
    var p = index.offsets[c]; val end = index.offsets[c + 1]
    while (p < end) {
        var sum = 0.0; val base = p * dim
        for (d in 0 until dim) { val diff = index.store[base + d] - index.centroids[cb + d]; sum += diff * diff }
        radii[p] = sqrt(sum).toFloat()
        p++
    }
}

private fun exactFraudCount(q: DoubleArray, store: DoubleArray, labels: BooleanArray, n: Int, dim: Int): Int {
    val k = K_NEIGHBORS
    val bestDist = DoubleArray(k) { Double.MAX_VALUE }
    val bestFraud = BooleanArray(k)
    var i = 0; var base = 0
    while (i < n) {
        var sum = 0.0; var d = 0
        while (d < dim) { val diff = q[d] - store[base + d]; sum += diff * diff; d++ }
        if (sum < bestDist[k - 1]) {
            var j = k - 1
            while (j > 0 && bestDist[j - 1] > sum) { bestDist[j] = bestDist[j - 1]; bestFraud[j] = bestFraud[j - 1]; j-- }
            bestDist[j] = sum; bestFraud[j] = labels[i]
        }
        i++; base += dim
    }
    var c = 0
    for (j in 0 until k) if (bestFraud[j]) c++
    return c
}

private fun mean(a: IntArray): Int = if (a.isEmpty()) 0 else (a.sumOf { it.toLong() } / a.size).toInt()
private fun p99(a: IntArray): Int { val s = a.clone(); s.sort(); return s[(s.size * 99 / 100).coerceIn(0, s.size - 1)] }
private fun detScore(e: Double, eps: Double): Double = 1000.0 * log10(1.0 / max(eps, 0.001)) - 300.0 * log10(1.0 + e)
private fun pct(a: Int, b: Int): String = "%.1f%%".format(100.0 * a / b)

private fun loadRefs(path: String): List<LabeledVector> {
    val file = File(path)
    return file.inputStream().buffered().use { raw ->
        val stream = if (file.name.endsWith(".gz")) GZIPInputStream(raw) else raw
        References.parse(stream)
    }
}
