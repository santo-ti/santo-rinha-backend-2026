package dev.santo.tools

import dev.santo.search.DEFAULT_NPROBE
import dev.santo.search.IvfIndex
import dev.santo.search.LabeledVector
import dev.santo.search.quantizeVector
import dev.santo.vectorization.VECTOR_DIMENSIONS

/**
 * Offline construction of an [IvfIndex] from labeled references: quantizes each
 * vector (int16 logical codes), runs [KMeans] to partition them into [k] cells,
 * then packs the points CONTIGUOUSLY per cell (counting-sort by assignment) so a
 * cell is a single sequential slice of the store. CPU/RAM-heavy — runs once during
 * the image build; the runtime reconstructs the result from the binary artifact.
 */
object IvfBuilder {

    fun build(
        references: List<LabeledVector>,
        dim: Int = VECTOR_DIMENSIONS,
        k: Int = DEFAULT_CENTROIDS,
        iterations: Int = 20,
        nprobe: Int = DEFAULT_NPROBE,
    ): IvfIndex {
        val n = references.size
        val cellCount = minOf(k, n)

        // Quantize once into a flat store, in reference order.
        val srcStore = ShortArray(n * dim)
        val srcLabels = BooleanArray(n)
        for (i in 0 until n) {
            System.arraycopy(quantizeVector(references[i].vector), 0, srcStore, i * dim, dim)
            srcLabels[i] = references[i].isFraud
        }

        val km = KMeans.cluster(srcStore, n, dim, cellCount, iterations)

        // Counting sort points by cell -> offsets + contiguous store/labels.
        val offsets = IntArray(cellCount + 1)
        for (i in 0 until n) offsets[km.assignment[i] + 1]++
        for (c in 0 until cellCount) offsets[c + 1] += offsets[c]

        val store = ShortArray(n * dim)
        val labels = BooleanArray(n)
        val cursor = offsets.copyOf()
        for (i in 0 until n) {
            val pos = cursor[km.assignment[i]]++
            System.arraycopy(srcStore, i * dim, store, pos * dim, dim)
            labels[pos] = srcLabels[i]
        }

        return IvfIndex(km.centroids, offsets, store, labels, dim, cellCount, nprobe)
    }

    /** Default cell count. Tuned offline against the recall/cost curve. */
    const val DEFAULT_CENTROIDS = 2048
}
