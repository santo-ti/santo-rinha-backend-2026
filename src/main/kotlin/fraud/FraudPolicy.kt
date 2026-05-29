package dev.santo.fraud

import dev.santo.search.VectorIndex

/** Number of nearest neighbors used to vote. */
const val K_NEIGHBORS = 5

/**
 * Fixed decision threshold. A transaction is denied when its fraud score is
 * `>= FRAUD_THRESHOLD`, i.e. approved only when `fraud_score < FRAUD_THRESHOLD`.
 */
const val FRAUD_THRESHOLD = 0.6

/**
 * Default per-query cap on distance evaluations the index search may perform,
 * overridable at runtime via the `SEARCH_BUDGET` env var so the budget can be
 * tuned against the contest hardware without rebuilding the native image.
 *
 * Calibrated over the FULL 3M index (offline `tools.SampleSweepKt` + a local k6
 * run under the real 0.425-CPU / 160MiB limits). Indexing all 3M instead of the
 * old 100k sample is the dominant lever (det 222 → 696+). The budget then trades
 * detection against CPU: higher budgets evaluate more neighbors (better det) but
 * cost more per query and saturate the 0.425 core under 900 rps. Local k6 (3M):
 * 256→p99 13ms/det 696, 512→29ms/874, 1024→43ms/1177, 2048→saturates (250 errs).
 * The contest Mac Mini is ~2.8× slower, so saturation hits ~2.8× lower: 256
 * (meanComps ~247) stays safely below the collapse threshold; 512+ risks it.
 */
const val SEARCH_BUDGET = 256

/** Fraction of fraud labels among the [K_NEIGHBORS] nearest neighbors. */
fun fraudScore(fraudNeighbors: Int): Double = fraudNeighbors.toDouble() / K_NEIGHBORS

fun isApproved(fraudScore: Double): Boolean = fraudScore < FRAUD_THRESHOLD

/**
 * Fraud score (`fraud_neighbors / K`) for [query] against [this] index. The
 * bridge between the domain rule and the search engine: composes the engine's
 * neighbor count with the domain's [fraudScore].
 */
fun VectorIndex.scoreOf(query: DoubleArray): Double = fraudScore(nearestFraudCount(query))
