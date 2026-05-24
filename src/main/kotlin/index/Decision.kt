package dev.santo.index

/** Number of nearest neighbors used to vote. */
const val K_NEIGHBORS = 5

/**
 * Fixed decision threshold. A transaction is denied when its fraud score is
 * `>= FRAUD_THRESHOLD`, i.e. approved only when `fraud_score < FRAUD_THRESHOLD`.
 */
const val FRAUD_THRESHOLD = 0.6

/** Fraction of fraud labels among the [K_NEIGHBORS] nearest neighbors. */
fun fraudScore(fraudNeighbors: Int): Double = fraudNeighbors.toDouble() / K_NEIGHBORS

fun isApproved(fraudScore: Double): Boolean = fraudScore < FRAUD_THRESHOLD

/** Squared Euclidean distance over two equal-length vectors. */
fun squaredDistance(a: DoubleArray, b: DoubleArray): Double {
    var sum = 0.0
    for (i in a.indices) {
        val diff = a[i] - b[i]
        sum += diff * diff
    }
    return sum
}
