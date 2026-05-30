package dev.santo.tools

import dev.santo.fraud.K_NEIGHBORS
import dev.santo.search.IvfIndex
import dev.santo.search.LabeledVector
import dev.santo.vectorization.VECTOR_DIMENSIONS
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.stream.IntStream
import kotlin.math.log10
import kotlin.math.max

/**
 * Offline IVF recall/cost calibration. Builds an IVF over the real reference set,
 * generates randomized-date queries via [OfficialOracle], computes the float-exact
 * 5-NN gabarito (the contest's ground truth), then reports FP/FN/E and scan cost
 * for a sweep of `nprobe` — so the recall/CPU operating point is chosen BEFORE
 * burning a ~20min native build or a contest preview.
 *
 * Usage: ./gradlew ivfCalibrate -Pargs="build/refs-3m.json.gz [numQueries] [k] [iters] [fraudRatio] [nprobeCSV]"
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: IvfCalibrate <refs.json[.gz]> [numQueries=8000] [k=4096] [iters=20] [fraudRatio=0.5] [nprobeCSV]" }
    val refsPath = args[0]
    val numQueries = args.getOrNull(1)?.toInt() ?: 8000
    val k = args.getOrNull(2)?.toInt() ?: 4096
    val iters = args.getOrNull(3)?.toInt() ?: 20
    val fraudRatio = args.getOrNull(4)?.toDouble() ?: 0.5
    val nprobes = (args.getOrNull(5) ?: "1,8,16,32,64,128,256").split(",").map { it.trim().toInt() }
    val dim = VECTOR_DIMENSIONS

    println("Loading references from $refsPath ...")
    val refs = loadRefs(refsPath)
    val n = refs.size
    println("Loaded $n references")

    // Flat double store + labels for the exact gabarito (the official double oracle).
    val gold = DoubleArray(n * dim)
    val goldLabel = BooleanArray(n)
    for (i in 0 until n) {
        System.arraycopy(refs[i].vector, 0, gold, i * dim, dim)
        goldLabel[i] = refs[i].isFraud
    }

    // The k-means build is deterministic (fixed seed) and the dominant cost, so cache
    // the artifact and reuse it across query/nprobe sweeps.
    val cache = File("build/ivf-k$k-i$iters.bin")
    val index = if (cache.exists()) {
        println("Loading cached IVF from ${cache.path} ...")
        cache.inputStream().buffered().use { dev.santo.search.IvfReader.readFrom(it, nprobe = k) }
    } else {
        println("Building IVF: k=$k iterations=$iters ...")
        val t0 = System.nanoTime()
        val built = IvfBuilder.build(refs, dim = dim, k = k, iterations = iters, nprobe = k)
        println("IVF built in ${(System.nanoTime() - t0) / 1_000_000} ms (cells=${built.k})")
        cache.outputStream().buffered().use { IvfWriter.writeTo(built, it) }
        built
    }

    println("Generating $numQueries randomized-date queries (fraudRatio=$fraudRatio) ...")
    val oracle = OfficialOracle(fraudRatio = fraudRatio)
    val queries = Array(numQueries) { oracle.nextVector() }

    println("Computing float-exact gabarito over $n refs (parallel) ...")
    val tg = System.nanoTime()
    val goldCount = IntArray(numQueries)
    IntStream.range(0, numQueries).parallel().forEach { qi ->
        goldCount[qi] = exactFraudCount(queries[qi], gold, goldLabel, n, dim)
    }
    var goldFraud = 0
    for (qi in 0 until numQueries) if (goldCount[qi] >= 3) goldFraud++
    println("Gabarito done in ${(System.nanoTime() - tg) / 1_000_000} ms; fraud-labeled ${pct(goldFraud, numQueries)}")

    println()
    println("nprobe |   FP   FN |   E   eps%% | det_score | meanScan p99Scan | →contest(N=54100): FP   FN   E")
    println("-------+-----------+------------+-----------+------------------+--------------------------------")
    for (np in nprobes.filter { it in 1..index.k }.sorted()) {
        val view = index.reprobe(np)
        var fp = 0; var fn = 0
        val scans = IntArray(numQueries)
        IntStream.range(0, numQueries).parallel().forEach { qi ->
            scans[qi] = probedPointCount(view, queries[qi])
        }
        for (qi in 0 until numQueries) {
            val pred = view.nearestFraudCount(queries[qi]) >= 3
            val gld = goldCount[qi] >= 3
            if (pred && !gld) fp++
            if (!pred && gld) fn++
        }
        scans.sort()
        val meanScan = scans.average().toInt()
        val p99Scan = scans[(numQueries * 99 / 100).coerceIn(0, numQueries - 1)]
        val e = fp + 3 * fn
        val eps = e.toDouble() / numQueries
        val scale = 54100.0 / numQueries
        // det_score's penalty term uses ABSOLUTE E, which scales with N — so score on
        // the contest-scaled E (eps is already scale-invariant), else det reads optimistic.
        val det = detScore(e * scale, eps)
        println(
            "%6d | %4d %4d | %4d %6.3f | %9.1f | %8d %8d | %6.0f %6.0f %6.0f".format(
                np, fp, fn, e, eps * 100, det,
                meanScan, p99Scan, fp * scale, fn * scale, e * scale,
            )
        )
    }
    println()
    println("Note: nprobe=$k row is exact quantized (int16 floor vs float gabarito). →contest columns scale FP/FN to N=54100.")
}

/** Exact 5-NN fraud count over the double gabarito store (squared euclidean). */
private fun exactFraudCount(q: DoubleArray, store: DoubleArray, labels: BooleanArray, n: Int, dim: Int): Int {
    val k = K_NEIGHBORS
    val bestDist = DoubleArray(k) { Double.MAX_VALUE }
    val bestFraud = BooleanArray(k)
    var i = 0
    var base = 0
    while (i < n) {
        var sum = 0.0
        var d = 0
        while (d < dim) {
            val diff = q[d] - store[base + d]
            sum += diff * diff
            d++
        }
        if (sum < bestDist[k - 1]) {
            var j = k - 1
            while (j > 0 && bestDist[j - 1] > sum) {
                bestDist[j] = bestDist[j - 1]; bestFraud[j] = bestFraud[j - 1]; j--
            }
            bestDist[j] = sum; bestFraud[j] = labels[i]
        }
        i++; base += dim
    }
    var c = 0
    for (j in 0 until k) if (bestFraud[j]) c++
    return c
}

/** Total points held by the nprobe cells a query probes — the IVF scan cost proxy. */
private fun probedPointCount(index: IvfIndex, query: DoubleArray): Int {
    val probes = index.nearestCellsForCalibration(query)
    var total = 0
    for (c in probes) total += index.offsets[c + 1] - index.offsets[c]
    return total
}

private fun detScore(e: Double, eps: Double): Double =
    1000.0 * log10(1.0 / max(eps, 0.001)) - 300.0 * log10(1.0 + e)

private fun pct(a: Int, b: Int): String = "%.1f%%".format(100.0 * a / b)

private fun loadRefs(path: String): List<LabeledVector> {
    val file = File(path)
    return file.inputStream().buffered().use { raw ->
        val stream = if (file.name.endsWith(".gz")) GZIPInputStream(raw) else raw
        References.parse(stream)
    }
}
