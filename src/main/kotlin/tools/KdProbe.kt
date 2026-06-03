package dev.santo.tools

import dev.santo.fraud.K_NEIGHBORS
import dev.santo.search.quantizeToLogicalCode
import dev.santo.search.quantizeVector
import dev.santo.vectorization.VECTOR_DIMENSIONS
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * De-risks the KD-tree direction: builds a [dev.santo.search.KdTreeIndex] over [maxSize]
 * references and checks it against an EXACT brute force over the SAME set. Because the
 * KD-tree branch-and-bound is exact, E MUST be 0 (any non-zero = a bug). The headline number
 * is VISITED NODES per query (mean/p99) — the p99 proxy: our IVF tail scanned ~16k points, so
 * if the KD-tree visits only hundreds/low-thousands at E=0, it's the zero-error + low-p99 win.
 *
 * Usage: ./gradlew kdProbe -Pargs="build/refs-3m.json.gz 200000 2000"
 *   args: refs, maxSize, numQueries
 */
fun main(args: Array<String>) {
    val refsPath = args.getOrNull(0) ?: "build/refs-3m.json.gz"
    val maxSize = args.getOrNull(1)?.toIntOrNull() ?: 200000
    val numQueries = args.getOrNull(2)?.toInt() ?: 2000
    val dim = VECTOR_DIMENSIONS

    println("Loading references ($refsPath) ...")
    val input = File(refsPath)
    val allRefs = input.inputStream().buffered().use { raw ->
        References.parse(if (input.name.endsWith(".gz")) GZIPInputStream(raw) else raw)
    }
    val refs = if (allRefs.size > maxSize) allRefs.subList(0, maxSize) else allRefs
    val n = refs.size
    println("Using $n references, dim=$dim")

    val vectors = ShortArray(n * dim)
    val labels = BooleanArray(n)
    for (i in 0 until n) {
        System.arraycopy(quantizeVector(refs[i].vector), 0, vectors, i * dim, dim)
        labels[i] = refs[i].isFraud
    }

    val t0 = System.nanoTime()
    val index = KdTreeBuilder.build(vectors, labels, n, dim)
    println("Built KD-tree in ${(System.nanoTime() - t0) / 1_000_000} ms")

    val oracle = OfficialOracle(fraudRatio = 0.5)
    val queries = Array(numQueries) { oracle.nextVector() }

    // Gold = exact brute-force fraud count per query, computed once.
    val gold = IntArray(numQueries) { bruteForceFraudCount(vectors, labels, n, dim, queries[it]) }

    val scale = 54100.0 / numQueries
    println()
    println("KD-tree over $n refs, $numQueries queries (vs brute force on the same set):")
    println("  budget |   E (contest) | visits mean | visits p99 | FP | FN")
    val budgets = if (n > 500_000) intArrayOf(4000, 6000, 8000, 10000, 14000, 20000)
                  else intArrayOf(200, 500, 1000, 2000, 5000, Int.MAX_VALUE)
    for (budget in budgets) {
        index.visitBudget = budget
        var fp = 0; var fn = 0
        val visits = IntArray(numQueries)
        for (i in 0 until numQueries) {
            val kd = index.nearestFraudCount(queries[i])
            visits[i] = index.lastVisits.get()[0]
            if (kd >= 3 && gold[i] < 3) fp++
            if (kd < 3 && gold[i] >= 3) fn++
        }
        val e = fp + 3 * fn
        val label = if (budget == Int.MAX_VALUE) "exact " else "%6d".format(budget)
        println("  $label | E=$e (${"%.0f".format(e * scale)}) | ${mean(visits)} | ${p99(visits)} | $fp | $fn")
    }
    println("  (IVF exact scanned ~16k pts p99 → 17.5ms. Want: a budget with low visits AND low contest-E.)")
}

private fun bruteForceFraudCount(vectors: ShortArray, labels: BooleanArray, n: Int, dim: Int, query: DoubleArray): Int {
    val q = IntArray(dim) { quantizeToLogicalCode(query[it]) }
    val bestD = LongArray(K_NEIGHBORS) { Long.MAX_VALUE }
    val bestF = BooleanArray(K_NEIGHBORS)
    for (i in 0 until n) {
        var sum = 0L
        val base = i * dim
        var d = 0
        while (d < dim) { val diff = (q[d] - vectors[base + d]).toLong(); sum += diff * diff; d++ }
        if (sum < bestD[K_NEIGHBORS - 1]) {
            var j = K_NEIGHBORS - 1
            while (j > 0 && bestD[j - 1] > sum) { bestD[j] = bestD[j - 1]; bestF[j] = bestF[j - 1]; j-- }
            bestD[j] = sum; bestF[j] = labels[i]
        }
    }
    var c = 0; for (i in 0 until K_NEIGHBORS) if (bestF[i]) c++
    return c
}

private fun mean(a: IntArray): Int = if (a.isEmpty()) 0 else (a.sumOf { it.toLong() } / a.size).toInt()
private fun p99(a: IntArray): Int { val s = a.clone(); s.sort(); return s[(s.size * 99 / 100).coerceIn(0, s.size - 1)] }
