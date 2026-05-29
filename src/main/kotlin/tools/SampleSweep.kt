package dev.santo.tools

import dev.santo.bootstrap.AppComponents
import dev.santo.dto.FraudScoreRequest
import dev.santo.fraud.fraudScore
import dev.santo.fraud.isApproved
import dev.santo.search.SearchBudget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.log10
import kotlin.math.min

/**
 * Offline experiment: sweeps the reference sample size (and search budget) to map
 * how detection error (FP/FN -> detection_score) and search work (distance evals)
 * trade off. Parses the 3M reference dataset ONCE, then rebuilds the in-memory
 * index for each sample size and replays the official test queries.
 *
 * The detection_score's absolute penalty scales with the absolute error volume, so
 * every score is also projected to the full 54100-query contest size to stay
 * comparable regardless of how many queries are sampled here.
 *
 * Run: java -Xmx8g -cp "build/libs/<fat-jar>" dev.santo.tools.SampleSweepKt [dir] [nQueries] [samplesCsv] [budgetsCsv]
 */

@Serializable private class SweepTestData(val entries: List<SweepTestEntry>)
@Serializable private class SweepTestEntry(
    val request: FraudScoreRequest,
    @SerialName("expected_approved") val expectedApproved: Boolean,
)

private const val CONTEST_N = 54100.0

private fun ff(fmt: String, vararg a: Any?) = String.format(Locale.ROOT, fmt, *a)

private fun detScore(fp: Double, fn: Double, n: Double): Double {
    val e = fp + 3 * fn
    val eps = e / n
    val failRate = (fp + fn) / n
    return if (failRate > 0.15) -3000.0
    else 1000 * log10(1 / maxOf(eps, 0.001)) - 300 * log10(1.0 + e)
}

private fun percentile(values: IntArray, p: Int): Int {
    val sorted = values.clone(); sorted.sort()
    return sorted[min(sorted.size - 1, (p / 100.0 * sorted.size).toInt())]
}

fun main(args: Array<String>) {
    val dir = args.getOrElse(0) { "build/measure" }
    val nQueries = args.getOrElse(1) { "54100" }.toInt()
    val samples = args.getOrElse(2) { "100000,250000,500000,1000000,2000000,2147483647" }
        .split(",").map { it.trim().toInt() }
    val budgets = args.getOrElse(3) { "2147483647" }
        .split(",").map { it.trim().toInt() }

    val refsFile = listOf("references.json.gz", "refs.json.gz").map { File("$dir/$it") }.first { it.exists() }
    print("parsing ${refsFile.path} ... "); System.out.flush()
    val t0 = System.nanoTime()
    val references = refsFile.inputStream().buffered().use { References.parse(GZIPInputStream(it)) }
    println("${references.size} refs in ${(System.nanoTime() - t0) / 1e9}s")

    val vectorizer = AppComponents.create().vectorizer
    val data = Json { ignoreUnknownKeys = true }
        .decodeFromString(SweepTestData.serializer(), File("$dir/test-data.json").readText())
    val stride = maxOf(1, data.entries.size / nQueries)
    val queries = data.entries.filterIndexed { i, _ -> i % stride == 0 }.take(nQueries)
        .map { vectorizer.vectorize(it.request) to it.expectedApproved }
    val n = queries.size
    val scale = CONTEST_N / n
    println("queries=$n  (scale to 54100: x${ff("%.2f", scale)})")
    println("")
    println("  sample | budget |   FP |   FN | fail% |  det@n | det@54100 | meanC | p99C")

    val out = StringBuilder()
    fun line(s: String) { println(s); out.appendLine(s) }

    for (s in samples) {
        val buildStart = System.nanoTime()
        val index = IndexBuilder.build(references, maxSize = s)
        val built = (System.nanoTime() - buildStart) / 1e9
        val realN = min(s, references.size)
        for (b in budgets) {
            var fp = 0; var fn = 0; var total = 0L
            val comps = IntArray(n)
            for (i in queries.indices) {
                val budget = SearchBudget.of(b)
                val count = index.nearestFraudCount(queries[i].first, budget)
                val deny = !isApproved(fraudScore(count))
                comps[i] = budget.used
                total += budget.used
                if (queries[i].second && deny) fp++       // legit blocked = FP
                if (!queries[i].second && !deny) fn++      // fraud approved = FN
            }
            val detN = detScore(fp.toDouble(), fn.toDouble(), n.toDouble())
            val det54 = detScore(fp * scale, fn * scale, CONTEST_N)
            val label = if (s == Int.MAX_VALUE) "ALL" else realN.toString()
            val blab = if (b == Int.MAX_VALUE) "exact" else b.toString()
            line(ff("%8s | %6s | %4d | %4d | %5.2f | %6.0f | %9.0f | %5.0f | %5d",
                label, blab, fp, fn, (fp + fn) * 100.0 / n, detN, det54,
                total.toDouble() / n, percentile(comps, 99)))
        }
        line(ff("  ^ build %.1fs", built))
    }
    File("$dir/sweep-report.txt").writeText(out.toString())
    println("\nreport: $dir/sweep-report.txt")
}
