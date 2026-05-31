package dev.santo.tools

import dev.santo.fraud.K_NEIGHBORS
import dev.santo.search.IvfIndex
import dev.santo.search.IvfReader
import dev.santo.search.quantizeToLogicalCode
import dev.santo.vectorization.VECTOR_DIMENSIONS
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ForkJoinPool
import java.util.stream.IntStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Measures TWO-LEVEL routing for the IVF: the K cell centroids are themselves
 * clustered into K1 "districts" (meta-centroids). A query scans the K1 districts
 * (cheap), descends into the top `nprobe1` districts' member cells, ranks them,
 * and scans the top `nprobe2` cells' points (with radius pruning). This cuts the
 * routing cost from O(K)=4096 comps/query (the #7428 saturation) to ~K1 +
 * nprobe1·(K/K1) ≈ a few hundred — WITHOUT touching detection (still exact within
 * the probed cells). Reports comps (mean/p99) and recall so the gain is proven
 * BEFORE baking it into the production index.
 *
 * Reuses the cached flat index (build/ivf-k{K}-i{I}.bin) and caches the gabarito,
 * so param sweeps are near-instant. CPU-capped — does not freeze the dev machine.
 *
 * Usage: ./gradlew ivfTwoLevel -Pargs="build/refs-3m.json.gz <K> <iters> [numQueries] [k1,np1,np2 ; ...] [parallelism]"
 */
fun main(args: Array<String>) {
    val refsPath = args[0]
    val k = args.getOrNull(1)?.toInt() ?: 4096
    val iters = args.getOrNull(2)?.toInt() ?: 12
    val numQueries = args.getOrNull(3)?.toInt() ?: 2000
    // Config token is space-free (Gradle splits -Pargs by space): "K1,np1,np2;K1,np1,np2;..."
    val configs = (args.getOrNull(4) ?: "64,4,8;128,4,8;128,8,16;256,8,16;256,16,32")
        .split(";").map { it.split(",").map { s -> s.toInt() } }
    val cores = Runtime.getRuntime().availableProcessors()
    val parallelism = args.getOrNull(5)?.toInt() ?: max(2, cores / 2)
    val pool = ForkJoinPool(parallelism)
    val dim = VECTOR_DIMENSIONS
    val fraudRatio = 0.5
    println("CPU cap: $parallelism of $cores cores.")

    val cache = File("build/ivf-k$k-i$iters.bin")
    require(cache.exists()) { "No cached index at ${cache.path}; run ivfCalibrate with k=$k iters=$iters first." }
    println("Loading cached IVF k=$k ...")
    val index = cache.inputStream().buffered().use { IvfReader.readFrom(it) }
    val n = index.offsets[index.k]

    // Per-point radius to its cell centroid (pruning bound), precomputed once.
    val radii = FloatArray(n)
    pool.submit { IntStream.range(0, index.k).parallel().forEach { c -> fillRadii(index, c, radii) } }.get()

    // Queries (deterministic) + gabarito (cached: the 3M brute force is the only heavy load).
    val oracle = OfficialOracle(fraudRatio = fraudRatio)
    val queries = Array(numQueries) { oracle.nextVector() }
    val goldCount = loadOrComputeGold(refsPath, numQueries, fraudRatio, dim, pool, queries)
    var goldFraud = 0; for (qi in 0 until numQueries) if (goldCount[qi] >= 3) goldFraud++
    println("Gabarito: fraud-labeled ${"%.1f%%".format(100.0 * goldFraud / numQueries)}")

    // Cluster the K cell centroids into districts (cheap: K points, 14-dim).
    println()
    println("k=$k | comps = K1(meta) + cells-in-top-districts + points scanned(pruned). +1804 sustained ~1.8k.")
    println("K1   np1 np2 |  FP  FN |  E  | det  | comps mean/p99 | flat np2 comps(ref)")
    println("-------------+---------+-----+------+----------------+--------------------")
    val builtMetas = HashMap<Int, Meta>()
    for (cfg in configs) {
        val (k1, np1, np2) = cfg
        val meta = builtMetas.getOrPut(k1) { buildMeta(index, k1, 25) }
        val fp = IntArray(numQueries); val fn = IntArray(numQueries); val comps = IntArray(numQueries)
        pool.submit {
            IntStream.range(0, numQueries).parallel().forEach { qi ->
                val r = twoLevelSearch(index, meta, queries[qi], np1, np2, radii)
                val pred = r.fraud >= 3; val gld = goldCount[qi] >= 3
                if (pred && !gld) fp[qi] = 1
                if (!pred && gld) fn[qi] = 1
                comps[qi] = r.comps
            }
        }.get()
        val FP = fp.sum(); val FN = fn.sum(); val e = FP + 3 * FN
        val scale = 54100.0 / numQueries
        val det = detScore(e * scale, e.toDouble() / numQueries)
        val flatRef = k + np2 * (n / k) // rough flat routing+scan reference
        println("%4d %3d %3d | %3d %3d | %3d | %4.0f | %6d %6d | %d".format(k1, np1, np2, FP, FN, e, det, mean(comps), p99(comps), flatRef))
    }
    pool.shutdown()
    println()
    println("Goal: a config with comps mean ≤ ~3000 (p99 not wildly higher) AND E ~0. That ships.")
}

private class Meta(val centroids: FloatArray, val k1: Int, val membersByMeta: Array<IntArray>)
private class TLResult(val fraud: Int, val comps: Int)

/** Lloyd k-means over the index's cell centroids → K1 district centroids + membership. */
private fun buildMeta(index: IvfIndex, k1: Int, iterations: Int): Meta {
    val dim = index.dim; val k = index.k
    val src = index.centroids // centroid-major, k*dim
    val meta = FloatArray(k1 * dim)
    val rng = java.util.Random(1L)
    val seen = HashSet<Int>()
    var c = 0
    while (c < k1) { val p = rng.nextInt(k); if (seen.add(p)) { System.arraycopy(src, p * dim, meta, c * dim, dim); c++ } }
    val assign = IntArray(k)
    repeat(iterations) {
        for (ci in 0 until k) {
            var best = 0; var bd = Double.MAX_VALUE
            for (m in 0 until k1) {
                var s = 0.0; val cb = ci * dim; val mb = m * dim
                for (d in 0 until dim) { val diff = (src[cb + d] - meta[mb + d]).toDouble(); s += diff * diff }
                if (s < bd) { bd = s; best = m }
            }
            assign[ci] = best
        }
        val sum = DoubleArray(k1 * dim); val cnt = IntArray(k1)
        for (ci in 0 until k) { val m = assign[ci]; cnt[m]++; val cb = ci * dim; val mb = m * dim; for (d in 0 until dim) sum[mb + d] += src[cb + d] }
        for (m in 0 until k1) { val mb = m * dim; if (cnt[m] == 0) { val p = rng.nextInt(k); System.arraycopy(src, p * dim, meta, mb, dim) } else { val inv = 1.0 / cnt[m]; for (d in 0 until dim) meta[mb + d] = (sum[mb + d] * inv).toFloat() } }
    }
    val lists = Array(k1) { ArrayList<Int>() }
    for (ci in 0 until k) lists[assign[ci]].add(ci)
    return Meta(meta, k1, Array(k1) { lists[it].toIntArray() })
}

/** Two-level routed search with radius pruning; counts every distance comparison. */
private fun twoLevelSearch(index: IvfIndex, meta: Meta, query: DoubleArray, np1: Int, np2: Int, radii: FloatArray): TLResult {
    val dim = index.dim
    val codes = IntArray(dim) { quantizeToLogicalCode(query[it]) }
    var comps = 0

    // Level 1: nearest np1 districts.
    val n1 = np1.coerceIn(1, meta.k1)
    val md = DoubleArray(n1) { Double.MAX_VALUE }; val mi = IntArray(n1)
    for (m in 0 until meta.k1) {
        comps++
        var s = 0.0; var idx = m * dim
        for (d in 0 until dim) { val diff = codes[d] - meta.centroids[idx]; s += diff * diff; idx++ }
        if (s >= md[n1 - 1]) continue
        var i = n1 - 1; while (i > 0 && md[i - 1] > s) { md[i] = md[i - 1]; mi[i] = mi[i - 1]; i-- }; md[i] = s; mi[i] = m
    }

    // Level 2: rank member cells of those districts, take top np2.
    val n2 = np2.coerceIn(1, index.k)
    val cd = DoubleArray(n2) { Double.MAX_VALUE }; val ci = IntArray(n2)
    for (pi in 0 until n1) for (cell in meta.membersByMeta[mi[pi]]) {
        comps++
        var s = 0.0; var idx = cell * dim
        for (d in 0 until dim) { val diff = codes[d] - index.centroids[idx]; s += diff * diff; idx++ }
        if (s >= cd[n2 - 1]) continue
        var i = n2 - 1; while (i > 0 && cd[i - 1] > s) { cd[i] = cd[i - 1]; ci[i] = ci[i - 1]; i-- }; cd[i] = s; ci[i] = cell
    }

    // Scan top np2 cells' points (nearest first → W shrinks), with radius pruning.
    val bestSq = DoubleArray(K_NEIGHBORS) { Double.MAX_VALUE }; val bestFraud = BooleanArray(K_NEIGHBORS)
    for (rank in 0 until n2) {
        val cell = ci[rank]; if (cd[rank] == Double.MAX_VALUE) continue
        val dCell = sqrt(cd[rank])
        val start = index.offsets[cell]; val end = index.offsets[cell + 1]
        var p = start
        while (p < end) {
            val within = p - start
            val full = bestSq[K_NEIGHBORS - 1]
            if (full == Double.MAX_VALUE || abs(dCell - radii[p]) < sqrt(full)) {
                comps++
                var s = 0L
                for (d in 0 until dim) { val diff = (codes[d] - index.codeAt(cell, within, d)).toLong(); s += diff * diff }
                val ds = s.toDouble()
                if (ds < bestSq[K_NEIGHBORS - 1]) { var i = K_NEIGHBORS - 1; while (i > 0 && bestSq[i - 1] > ds) { bestSq[i] = bestSq[i - 1]; bestFraud[i] = bestFraud[i - 1]; i-- }; bestSq[i] = ds; bestFraud[i] = index.labelAt(cell, within) }
            }
            p++
        }
    }
    var f = 0; for (i in 0 until K_NEIGHBORS) if (bestFraud[i]) f++
    return TLResult(f, comps)
}

private fun fillRadii(index: IvfIndex, c: Int, radii: FloatArray) {
    val dim = index.dim; val cb = c * dim
    val start = index.offsets[c]; val end = index.offsets[c + 1]
    var p = start
    while (p < end) {
        val within = p - start
        var sum = 0.0
        for (d in 0 until dim) { val diff = index.codeAt(c, within, d) - index.centroids[cb + d]; sum += diff * diff }
        radii[p] = sqrt(sum).toFloat(); p++
    }
}

private fun loadOrComputeGold(refsPath: String, numQueries: Int, fraudRatio: Double, dim: Int, pool: ForkJoinPool, queries: Array<DoubleArray>): IntArray {
    val gf = File("build/gold-N$numQueries-fr${fraudRatio}.bin")
    if (gf.exists()) {
        println("Loading cached gabarito ${gf.path} ...")
        DataInputStream(gf.inputStream().buffered()).use { inp -> return IntArray(inp.readInt()) { inp.readInt() } }
    }
    println("Computing gabarito over the 3M refs (one-time, cached after) ...")
    val refs = loadRefs(refsPath); val n = refs.size
    val gold = DoubleArray(n * dim); val lab = BooleanArray(n)
    for (i in 0 until n) { System.arraycopy(refs[i].vector, 0, gold, i * dim, dim); lab[i] = refs[i].isFraud }
    val gc = IntArray(numQueries)
    pool.submit { IntStream.range(0, numQueries).parallel().forEach { qi -> gc[qi] = exactFraudCount(queries[qi], gold, lab, n, dim) } }.get()
    DataOutputStream(gf.outputStream().buffered()).use { out -> out.writeInt(numQueries); for (v in gc) out.writeInt(v) }
    return gc
}

private fun exactFraudCount(q: DoubleArray, store: DoubleArray, labels: BooleanArray, n: Int, dim: Int): Int {
    val k = K_NEIGHBORS; val bestDist = DoubleArray(k) { Double.MAX_VALUE }; val bestFraud = BooleanArray(k)
    var i = 0; var base = 0
    while (i < n) {
        var sum = 0.0; var d = 0
        while (d < dim) { val diff = q[d] - store[base + d]; sum += diff * diff; d++ }
        if (sum < bestDist[k - 1]) { var j = k - 1; while (j > 0 && bestDist[j - 1] > sum) { bestDist[j] = bestDist[j - 1]; bestFraud[j] = bestFraud[j - 1]; j-- }; bestDist[j] = sum; bestFraud[j] = labels[i] }
        i++; base += dim
    }
    var c = 0; for (j in 0 until k) if (bestFraud[j]) c++; return c
}

private fun mean(a: IntArray): Int = if (a.isEmpty()) 0 else (a.sumOf { it.toLong() } / a.size).toInt()
private fun p99(a: IntArray): Int { val s = a.clone(); s.sort(); return s[(s.size * 99 / 100).coerceIn(0, s.size - 1)] }
private fun detScore(e: Double, eps: Double): Double = 1000.0 * log10(1.0 / max(eps, 0.001)) - 300.0 * log10(1.0 + e)
private fun loadRefs(path: String): List<dev.santo.search.LabeledVector> {
    val file = File(path)
    return file.inputStream().buffered().use { raw -> References.parse(if (file.name.endsWith(".gz")) GZIPInputStream(raw) else raw) }
}
