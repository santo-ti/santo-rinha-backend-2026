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
 * Calibrated against the OFFICIAL oracle (a faithful replay of the contest's
 * `data-generator` over the full 3M int16 index, randomized-date payloads — the
 * mode the contest uses). With the best-first search (see [dev.santo.search]),
 * weighted detection error E=1·FP+3·FN by budget on a 10k randomized sample:
 * 2048→83, 4096→33, 8192→8, 16384→1, 32768→0, with mean distance evals
 * 1.7k / 3.3k / 6.2k / 9.5k / 13k respectively.
 *
 * HARDWARE CEILING (contest #7354, budget 32768): −6000. The Mac Mini's 0.425 CPU
 * could not sustain ~13k evals/query — p99 hit the 2001ms timeout, 84% errors —
 * even though detection on the few served requests was PERFECT (FP=0, FN=0). So
 * the budget is CPU-bound, not detection-bound: keep it near #7265's proven-safe
 * ~3.3k evals. 4096 is the safe-but-strong point (det E33 ≈ detection_score 1459
 * vs 453 for the old depth-first search at the same budget). Tune UP via the env
 * var (8192, 16384) only after a real p99 confirms headroom; the per-query search
 * scratch is pooled (zero allocation), so the limit now is raw CPU, not GC.
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
