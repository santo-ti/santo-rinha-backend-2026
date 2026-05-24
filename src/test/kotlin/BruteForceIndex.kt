package dev.santo

import dev.santo.fraud.K_NEIGHBORS
import dev.santo.search.LabeledVector
import dev.santo.search.VectorIndex

/**
 * Exact k-NN by brute force over `Double` vectors. Used as the correctness
 * oracle (it mirrors how the test ground truth was generated) — never on the
 * request hot path.
 */
class BruteForceIndex(private val references: List<LabeledVector>) : VectorIndex {

    override fun nearestFraudCount(query: DoubleArray): Int {
        val k = K_NEIGHBORS
        val bestDist = DoubleArray(k) { Double.MAX_VALUE }
        val bestFraud = BooleanArray(k)
        for (ref in references) {
            val d = squaredDistance(query, ref.vector)
            if (d < bestDist[k - 1]) {
                // Insertion-sort the new neighbor into the k-sized ranking.
                var i = k - 1
                while (i > 0 && bestDist[i - 1] > d) {
                    bestDist[i] = bestDist[i - 1]
                    bestFraud[i] = bestFraud[i - 1]
                    i--
                }
                bestDist[i] = d
                bestFraud[i] = ref.isFraud
            }
        }
        return bestFraud.count { it }
    }
}

/** Squared Euclidean distance over two equal-length vectors (oracle helper). */
fun squaredDistance(a: DoubleArray, b: DoubleArray): Double {
    var sum = 0.0
    for (i in a.indices) {
        val diff = a[i] - b[i]
        sum += diff * diff
    }
    return sum
}
