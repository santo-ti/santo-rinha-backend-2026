package dev.santo.search

/**
 * Bounded buffer keeping the [k] smallest distances seen so far with their fraud
 * labels. Insertion-sorted: `k` is tiny (5), so this is cheaper than a heap.
 */
class KNearest(private val k: Int) {
    private val dist = DoubleArray(k) { Double.MAX_VALUE }
    private val fraud = BooleanArray(k)
    private var filled = 0

    /** k-th smallest distance so far, or `+inf` until [k] items have been offered. */
    fun worst(): Double = dist[k - 1]

    fun isFull(): Boolean = filled >= k

    fun offer(distance: Double, isFraud: Boolean) {
        if (distance >= dist[k - 1]) return
        var i = k - 1
        while (i > 0 && dist[i - 1] > distance) {
            dist[i] = dist[i - 1]
            fraud[i] = fraud[i - 1]
            i--
        }
        dist[i] = distance
        fraud[i] = isFraud
        if (filled < k) filled++
    }

    fun fraudCount(): Int {
        var count = 0
        for (i in 0 until k) if (fraud[i]) count++
        return count
    }
}
