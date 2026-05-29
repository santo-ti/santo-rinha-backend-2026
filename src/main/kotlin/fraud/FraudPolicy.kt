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
 * run under the real 0.425-CPU / 160MiB limits). Two levers stack:
 *   1. Index all 3M (vs the old 100k sample) — det 222 → up to 1859.
 *   2. Pack the store by bucket+tree order (cache locality) — halves ns/comp, so
 *      a larger budget fits the same CPU. This removed the saturation a 2048
 *      budget hit BEFORE the reorder (250 http_errors); now it runs clean.
 * Local k6 on the reordered 3M index (0 http_errors throughout): 1024→final 2950
 * (det 1175), 2048→3329 (det 1584), 4096→3529 (det 1859). 2048 is the balanced
 * start; calibrate up to 4096 against the real Mac Mini via the env var.
 */
const val SEARCH_BUDGET = 2048

/** Fraction of fraud labels among the [K_NEIGHBORS] nearest neighbors. */
fun fraudScore(fraudNeighbors: Int): Double = fraudNeighbors.toDouble() / K_NEIGHBORS

fun isApproved(fraudScore: Double): Boolean = fraudScore < FRAUD_THRESHOLD

/**
 * Fraud score (`fraud_neighbors / K`) for [query] against [this] index. The
 * bridge between the domain rule and the search engine: composes the engine's
 * neighbor count with the domain's [fraudScore].
 */
fun VectorIndex.scoreOf(query: DoubleArray): Double = fraudScore(nearestFraudCount(query))
