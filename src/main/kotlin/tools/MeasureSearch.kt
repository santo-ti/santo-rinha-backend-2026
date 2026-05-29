package dev.santo.tools

import dev.santo.bootstrap.AppComponents
import dev.santo.dto.FraudScoreRequest
import dev.santo.fraud.fraudScore
import dev.santo.fraud.isApproved
import dev.santo.search.IndexReader
import dev.santo.search.SearchBudget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.min

/**
 * Offline calibration for the approximate-search budget. Loads the prebuilt index
 * and the official test-data queries, then for a range of distance-evaluation
 * budgets reports the work per query, the detection errors a budget introduces
 * (vs. the contest's `expected_approved` and vs. the exact search), and a
 * predicted contest score.
 *
 * The local distance-eval counts are anchored to the contest hardware through the
 * one production data point we have (issue #7219: the exact search cost ~9 ms/req
 * on the contest core), so the latency columns estimate contest behavior, not the
 * dev box. Never reachable from the server entrypoint, so it stays out of the
 * native image.
 *
 * Run: java -cp "build/libs/<fat-jar>" dev.santo.tools.MeasureSearchKt [dir] [sample]
 */

@Serializable
private class TestData(val entries: List<TestEntry>)

@Serializable
private class TestEntry(
    val request: FraudScoreRequest,
    @SerialName("expected_approved") val expectedApproved: Boolean,
)

// Exact-search per-request cost observed on the contest hardware (#7219), derived
// from the throughput collapse. Anchors local comp counts to a contest latency.
private const val CONTEST_EXACT_MS = 9.0
private const val FIXED_OVERHEAD_MS = 0.10 // vectorize + JSON decode/encode, contest core

private fun f(fmt: String, vararg a: Any?) = String.format(Locale.ROOT, fmt, *a)

private fun percentile(values: IntArray, p: Int): Int {
    val sorted = values.clone()
    sorted.sort()
    return sorted[min(sorted.size - 1, (p / 100.0 * sorted.size).toInt())]
}

fun main(args: Array<String>) {
    val dir = args.getOrElse(0) { "build/measure" }
    val sampleSize = args.getOrElse(1) { "6000" }.toInt()

    val index = File("$dir/index.bin").inputStream().buffered().use { IndexReader.readFrom(it) }
    val vectorizer = AppComponents.create().vectorizer
    val data = Json { ignoreUnknownKeys = true }
        .decodeFromString(TestData.serializer(), File("$dir/test-data.json").readText())

    val stride = maxOf(1, data.entries.size / sampleSize)
    val queries = data.entries.filterIndexed { i, _ -> i % stride == 0 }.take(sampleSize)
        .map { vectorizer.vectorize(it.request) to it.expectedApproved }

    val out = StringBuilder()
    fun line(s: String) {
        println(s)
        out.appendLine(s)
    }

    line("entries=${data.entries.size}  sample=${queries.size}")

    // Exact pass (unlimited budget): the per-query baseline decision and work.
    val exactDeny = BooleanArray(queries.size)
    val exactComps = IntArray(queries.size)
    var exactTotal = 0L
    val t0 = System.nanoTime()
    for (i in queries.indices) {
        val budget = SearchBudget.unlimited()
        val count = index.nearestFraudCount(queries[i].first, budget)
        exactDeny[i] = !isApproved(fraudScore(count))
        exactComps[i] = budget.used
        exactTotal += budget.used
    }
    val devWallMs = (System.nanoTime() - t0) / 1e6
    val meanExact = exactTotal.toDouble() / queries.size
    val nsPerComp = CONTEST_EXACT_MS * 1e6 / meanExact

    line(f("exact: meanComps=%.0f  p99Comps=%d  devWall=%.0fms (%.2f ns/comp dev)",
        meanExact, percentile(exactComps, 99), devWallMs, devWallMs * 1e6 / exactTotal))
    line(f("anchor: %.1f ms exact on contest -> %.1f ns/comp; <=1ms target => budget ~%.0f comps",
        CONTEST_EXACT_MS, nsPerComp, (1.0 - FIXED_OVERHEAD_MS) * 1e6 / nsPerComp))
    line("")
    line("budget |  meanC |   p99C | estMeanMs | estP99Ms | FP | FN | failRate% | flipsExact | detScore | p99Score | final")

    for (limit in intArrayOf(Int.MAX_VALUE, 128, 256, 512, 768, 1024, 1536, 2048, 4096, 8192)) {
        var fp = 0
        var fn = 0
        var flips = 0
        var total = 0L
        val comps = IntArray(queries.size)
        for (i in queries.indices) {
            val budget = SearchBudget.of(limit)
            val count = index.nearestFraudCount(queries[i].first, budget)
            val deny = !isApproved(fraudScore(count))
            comps[i] = budget.used
            total += budget.used
            val expectedApproved = queries[i].second
            if (expectedApproved && deny) fp++ // legit blocked
            if (!expectedApproved && !deny) fn++ // fraud approved
            if (deny != exactDeny[i]) flips++
        }
        val n = queries.size
        val e = fp + 3 * fn
        val eps = e.toDouble() / n
        val failRate = (fp + fn).toDouble() / n
        val meanC = total.toDouble() / n
        val p99c = percentile(comps, 99)
        val estMean = meanC * nsPerComp / 1e6 + FIXED_OVERHEAD_MS
        val estP99 = p99c * nsPerComp / 1e6 + FIXED_OVERHEAD_MS
        val det = if (failRate > 0.15) -3000.0 else 1000 * log10(1 / maxOf(eps, 0.001)) - 300 * log10(1.0 + e)
        val p99s = if (estP99 > 2000) -3000.0 else 1000 * log10(1000 / maxOf(estP99, 1.0))
        val label = if (limit == Int.MAX_VALUE) "exact" else limit.toString()
        line(f("%6s | %6.0f | %6d | %9.2f | %8.2f | %2d | %2d | %8.3f | %10d | %8.0f | %8.0f | %5.0f",
            label, meanC, p99c, estMean, estP99, fp, fn, failRate * 100, flips, det, p99s, det + p99s))
    }

    File("$dir/measure-report.txt").writeText(out.toString())
    line("\nreport: $dir/measure-report.txt")
}
