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
 * Per-query cap on distance evaluations the index search may perform. Picked by
 * `tools.MeasureSearchKt` calibrated against the 100k-sampled index and the
 * contest's 9ms/req exact cost: 128 keeps the per-query work under ~1.3ms on
 * the Mac Mini, well below the 2001ms timeout. Lifts the tail of pathological
 * queries that v1.0.2 still hit at exactly the timeout boundary.
 */
const val SEARCH_BUDGET = 128

/** Fraction of fraud labels among the [K_NEIGHBORS] nearest neighbors. */
fun fraudScore(fraudNeighbors: Int): Double = fraudNeighbors.toDouble() / K_NEIGHBORS

fun isApproved(fraudScore: Double): Boolean = fraudScore < FRAUD_THRESHOLD

/**
 * Fraud score (`fraud_neighbors / K`) for [query] against [this] index. The
 * bridge between the domain rule and the search engine: composes the engine's
 * neighbor count with the domain's [fraudScore].
 */
fun VectorIndex.scoreOf(query: DoubleArray): Double = fraudScore(nearestFraudCount(query))
