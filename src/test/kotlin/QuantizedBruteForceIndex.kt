package dev.santo

import dev.santo.fraud.K_NEIGHBORS
import dev.santo.search.KNearest
import dev.santo.search.LabeledVector
import dev.santo.search.VectorIndex
import dev.santo.search.quantizeVector
import dev.santo.search.squaredDistance
import dev.santo.vectorization.VECTOR_DIMENSIONS
import kotlin.math.sqrt

/**
 * Brute force over int8-quantized vectors. Serves as the exactness reference for
 * [dev.santo.search.BucketedVpTreeIndex]: both operate in the same quantized
 * space, so any difference between them is a bug in the index structure, not
 * quantization.
 */
class QuantizedBruteForceIndex(
    references: List<LabeledVector>,
    private val dim: Int = VECTOR_DIMENSIONS,
) : VectorIndex {
    private val store = ByteArray(references.size * dim)
    private val labels = BooleanArray(references.size)

    init {
        for (i in references.indices) {
            System.arraycopy(quantizeVector(references[i].vector), 0, store, i * dim, dim)
            labels[i] = references[i].isFraud
        }
    }

    override fun nearestFraudCount(query: DoubleArray): Int {
        val qz = quantizeVector(query)
        val knn = KNearest(K_NEIGHBORS)
        for (i in labels.indices) {
            knn.offer(sqrt(squaredDistance(qz, store, i * dim, dim).toDouble()), labels[i])
        }
        return knn.fraudCount()
    }
}
