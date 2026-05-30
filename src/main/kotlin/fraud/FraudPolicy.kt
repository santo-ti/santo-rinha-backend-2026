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
 * Default per-query cap on distance evaluations the DEPTH-FIRST index search may
 * perform, overridable at runtime via the `SEARCH_BUDGET` env var so the budget
 * can be tuned against the contest hardware without rebuilding the native image.
 *
 * WHY DEPTH-FIRST (and not best-first): best-first branch-and-bound reaches exact
 * detection in far fewer distance evals, but its priority-queue overhead is brutal
 * on the Mac Mini's tiny cache — contest #7359 (best-first, budget 4096) hit p99
 * 2001ms (cut) and #7363 (best-first 2048) sustained only at p99 378ms. The plain
 * depth-first VP-tree walk is O(1) per node (no heap) and contest #7265 ran it at
 * p99 17ms, so it affords a far higher budget. Detection (DFS, weighted E=FP+3·FN
 * on a 10k randomized sample): 4096→195, 8192→123, 16384→29. 16384 is the knee —
 * det jumps there while the cheap per-node cost keeps p99 low. Paired with the LB
 * CPU reallocated to the APIs (0.45 each). Tune via the env var if a real p99 says
 * there is room up (32768) or it saturates (8192).
 */
const val SEARCH_BUDGET = 16384

/** Fraction of fraud labels among the [K_NEIGHBORS] nearest neighbors. */
fun fraudScore(fraudNeighbors: Int): Double = fraudNeighbors.toDouble() / K_NEIGHBORS

fun isApproved(fraudScore: Double): Boolean = fraudScore < FRAUD_THRESHOLD

/**
 * Fraud score (`fraud_neighbors / K`) for [query] against [this] index. The
 * bridge between the domain rule and the search engine: composes the engine's
 * neighbor count with the domain's [fraudScore].
 */
fun VectorIndex.scoreOf(query: DoubleArray): Double = fraudScore(nearestFraudCount(query))
