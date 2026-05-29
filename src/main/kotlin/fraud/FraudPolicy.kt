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
 * Calibrated over the FULL 3M int16 index (offline `tools.SampleSweepKt` + a local
 * k6 run under the real 0.425-CPU / 160MiB limits). Three levers stack:
 *   1. Index all 3M (vs the old 100k sample) — recall is no longer the gap.
 *   2. int16 store — quantization no longer flips borderline decisions.
 *   3. Bucket+tree cache-local layout — search reads stay local despite 84MB store.
 * int16 det@54100 by budget: 2048→2155, 4096→2688, exact→2819. Local k6 at budget
 * 4096: p99 ~43ms (host-contended; real Mac Mini differs), 0 http_errors, mem peak
 * 130/160MiB, final ~4049. det is hardware-independent, so 4096 is the start;
 * calibrate (2048↔8192) against the real p99 via the env var without rebuilding.
 */
const val SEARCH_BUDGET = 4096

/** Fraction of fraud labels among the [K_NEIGHBORS] nearest neighbors. */
fun fraudScore(fraudNeighbors: Int): Double = fraudNeighbors.toDouble() / K_NEIGHBORS

fun isApproved(fraudScore: Double): Boolean = fraudScore < FRAUD_THRESHOLD

/**
 * Fraud score (`fraud_neighbors / K`) for [query] against [this] index. The
 * bridge between the domain rule and the search engine: composes the engine's
 * neighbor count with the domain's [fraudScore].
 */
fun VectorIndex.scoreOf(query: DoubleArray): Double = fraudScore(nearestFraudCount(query))
