package dev.santo.tools

import java.util.stream.IntStream
import kotlin.random.Random

/**
 * Lloyd k-means over the int16-quantized reference store, in the same logical-code
 * space the IVF search compares against (so centroids are directly comparable to a
 * query's quantized codes). Produces dimension-major float centroids and a
 * per-point cell assignment. Offline only (image build) — assignment, the hot
 * step, is parallelized across cores; the update is a cheap single-threaded pass.
 */
object KMeans {

    class Result(val centroids: FloatArray, val assignment: IntArray, val k: Int, val dim: Int)

    /**
     * @param store flat `n*dim` int16 logical codes (the same layout the index uses).
     * @param centroids output is centroid-major (`centroids[c*dim + d]`) — each centroid's
     *   dims are contiguous, so [dev.santo.search.IvfIndex]'s scalar per-centroid scan
     *   streams sequentially through cache instead of striding by k (the cache-hostile
     *   dim-major layout caused the #7422 saturation: p99 1585ms).
     */
    fun cluster(
        store: ShortArray,
        n: Int,
        dim: Int,
        k: Int,
        iterations: Int = 20,
        seed: Long = 1L,
    ): Result {
        require(n >= k) { "k-means needs at least k=$k points, got n=$n" }
        val rng = Random(seed)

        // Centroid-major during clustering (each centroid's dims contiguous => the
        // assignment inner loop is cache-sequential); transposed to dim-major at the end.
        val cent = FloatArray(k * dim)
        val initIds = distinctIndices(n, k, rng)
        for (c in 0 until k) {
            val src = initIds[c] * dim
            for (d in 0 until dim) cent[c * dim + d] = store[src + d].toFloat()
        }

        val assignment = IntArray(n)
        for (iter in 0 until iterations) {
            val changed = assignAll(store, n, dim, k, cent, assignment)
            updateCentroids(store, n, dim, k, cent, assignment, rng)
            if (changed == 0 && iter > 0) break
        }

        // cent is already centroid-major (cent[c*dim+d]) — exactly the layout the search wants.
        return Result(cent, assignment, k, dim)
    }

    /** Parallel nearest-centroid assignment; returns how many points changed cell. */
    private fun assignAll(
        store: ShortArray, n: Int, dim: Int, k: Int, cent: FloatArray, assignment: IntArray,
    ): Int {
        val changed = java.util.concurrent.atomic.AtomicInteger(0)
        IntStream.range(0, n).parallel().forEach { i ->
            val base = i * dim
            var best = 0
            var bestDist = Float.MAX_VALUE
            var c = 0
            while (c < k) {
                val cb = c * dim
                var sum = 0f
                var d = 0
                while (d < dim) {
                    val diff = store[base + d] - cent[cb + d]
                    sum += diff * diff
                    d++
                }
                if (sum < bestDist) { bestDist = sum; best = c }
                c++
            }
            if (assignment[i] != best) { assignment[i] = best; changed.incrementAndGet() }
        }
        return changed.get()
    }

    /** Recomputes each centroid as the mean of its members; reseeds empty cells. */
    private fun updateCentroids(
        store: ShortArray, n: Int, dim: Int, k: Int, cent: FloatArray, assignment: IntArray, rng: Random,
    ) {
        val sums = DoubleArray(k * dim)
        val counts = IntArray(k)
        for (i in 0 until n) {
            val c = assignment[i]
            counts[c]++
            val base = i * dim
            val cb = c * dim
            for (d in 0 until dim) sums[cb + d] += store[base + d]
        }
        for (c in 0 until k) {
            val cb = c * dim
            if (counts[c] == 0) {
                val src = rng.nextInt(n) * dim
                for (d in 0 until dim) cent[cb + d] = store[src + d].toFloat()
            } else {
                val inv = 1.0 / counts[c]
                for (d in 0 until dim) cent[cb + d] = (sums[cb + d] * inv).toFloat()
            }
        }
    }

    private fun distinctIndices(n: Int, k: Int, rng: Random): IntArray {
        val picked = HashSet<Int>(k * 2)
        while (picked.size < k) picked.add(rng.nextInt(n))
        return picked.toIntArray()
    }
}
