package dev.santo.tools

import dev.santo.fraud.K_NEIGHBORS
import dev.santo.search.quantizeToLogicalCode
import dev.santo.search.quantizeVector
import dev.santo.vectorization.VECTOR_DIMENSIONS
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.ForkJoinPool
import java.util.stream.IntStream
import java.util.zip.GZIPInputStream
import kotlin.math.max

/**
 * Measures the int16 QUANTIZATION FLOOR, independent of any search structure: for
 * each query it computes the EXACT int16 brute-force 5-NN over all 3M refs and
 * compares the fraud decision to the float-exact gabarito. If FP/FN ≈ 0 here, then
 * int16 is precise enough for zero errors and ALL the contest's residual errors are
 * the approximate router (fixable with smaller cells / more probes). If FP/FN > 0
 * here, it is a hard floor — exact-quality recall needs a float rescore, not better
 * routing. Reuses the cached gabarito (build/gold-N{n}-fr{fr}.bin). CPU-capped.
 *
 * Usage: ./gradlew quantFloor -Pargs="build/refs-3m.json.gz [numQueries] [parallelism]"
 */
fun main(args: Array<String>) {
    val refsPath = args[0]
    val numQueries = args.getOrNull(1)?.toInt() ?: 2000
    val cores = Runtime.getRuntime().availableProcessors()
    val parallelism = args.getOrNull(2)?.toInt() ?: max(2, cores / 2)
    val pool = ForkJoinPool(parallelism)
    val dim = VECTOR_DIMENSIONS
    val fraudRatio = 0.5
    println("CPU cap: $parallelism of $cores cores.")

    val gf = File("build/gold-N$numQueries-fr${fraudRatio}.bin")
    require(gf.exists()) { "No cached gabarito ${gf.path}; run ivfTwoLevel first to create it." }
    val gold = DataInputStream(gf.inputStream().buffered()).use { inp -> IntArray(inp.readInt()) { inp.readInt() } }

    println("Loading 3M refs and quantizing to int16 ...")
    val refs = loadRefs(refsPath)
    val n = refs.size
    val store = ShortArray(n * dim)
    val labels = BooleanArray(n)
    for (i in 0 until n) {
        System.arraycopy(quantizeVector(refs[i].vector), 0, store, i * dim, dim)
        labels[i] = refs[i].isFraud
    }

    val oracle = OfficialOracle(fraudRatio = fraudRatio)
    val queries = Array(numQueries) { oracle.nextVector() }

    println("Brute-forcing EXACT int16 5-NN over $n refs for $numQueries queries ...")
    val int16Count = IntArray(numQueries)
    pool.submit {
        IntStream.range(0, numQueries).parallel().forEach { qi ->
            int16Count[qi] = int16FraudCount(queries[qi], store, labels, n, dim)
        }
    }.get()
    pool.shutdown()

    var fp = 0; var fn = 0
    for (qi in 0 until numQueries) {
        val pred = int16Count[qi] >= 3; val gld = gold[qi] >= 3
        if (pred && !gld) fp++
        if (!pred && gld) fn++
    }
    val e = fp + 3 * fn
    val scale = 54100.0 / numQueries
    println()
    println("int16 EXACT (brute force, no routing) vs float gabarito over $numQueries queries:")
    println("  FP=$fp  FN=$fn  E=$e  → contest-scale E≈${"%.0f".format(e * scale)} (FP≈${"%.0f".format(fp * scale)} FN≈${"%.0f".format(fn * scale)})")
    println()
    if (e == 0) println("  => int16 is PRECISE ENOUGH for zero errors. The contest residual is the router → smaller cells / more probes reach zero.")
    else println("  => int16 has a FLOOR of ~$e here. Zero errors needs a float rescore of the top candidates, not just better routing.")
}

/** Exact 5-NN fraud count over the int16 store (logical codes), brute force. */
private fun int16FraudCount(q: DoubleArray, store: ShortArray, labels: BooleanArray, n: Int, dim: Int): Int {
    val codes = IntArray(dim) { quantizeToLogicalCode(q[it]) }
    val k = K_NEIGHBORS
    val bestDist = LongArray(k) { Long.MAX_VALUE }
    val bestFraud = BooleanArray(k)
    var i = 0; var base = 0
    while (i < n) {
        var sum = 0L; var d = 0
        while (d < dim) { val diff = (codes[d] - store[base + d]).toLong(); sum += diff * diff; d++ }
        if (sum < bestDist[k - 1]) {
            var j = k - 1
            while (j > 0 && bestDist[j - 1] > sum) { bestDist[j] = bestDist[j - 1]; bestFraud[j] = bestFraud[j - 1]; j-- }
            bestDist[j] = sum; bestFraud[j] = labels[i]
        }
        i++; base += dim
    }
    var c = 0; for (j in 0 until k) if (bestFraud[j]) c++; return c
}

private fun loadRefs(path: String): List<dev.santo.search.LabeledVector> {
    val file = File(path)
    return file.inputStream().buffered().use { raw -> References.parse(if (file.name.endsWith(".gz")) GZIPInputStream(raw) else raw) }
}
