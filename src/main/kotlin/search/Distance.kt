package dev.santo.search

/** Squared Euclidean distance between a quantized query and a stored point at [offset]. */
fun squaredDistance(query: ShortArray, store: ShortArray, offset: Int, dim: Int): Long {
    var sum = 0L
    for (j in 0 until dim) {
        val diff = (query[j] - store[offset + j]).toLong()
        sum += diff * diff
    }
    return sum
}

/**
 * Squared Euclidean distance between a query given as precomputed logical codes
 * and a stored point at [offset]. Identical result to the [ShortArray] overload,
 * but hoists the query-side codes out of the traversal's innermost loop — the
 * hottest path under load. The stored `Short` is its own logical code (the int16
 * scheme reserves a negative sentinel), so no remap is needed.
 */
fun squaredDistance(queryCodes: IntArray, store: ShortArray, offset: Int, dim: Int): Long {
    var sum = 0L
    for (j in 0 until dim) {
        val diff = (queryCodes[j] - store[offset + j]).toLong()
        sum += diff * diff
    }
    return sum
}

/** Squared Euclidean distance between two stored points. */
fun squaredDistance(store: ShortArray, offsetA: Int, offsetB: Int, dim: Int): Long {
    var sum = 0L
    for (j in 0 until dim) {
        val diff = (store[offsetA + j] - store[offsetB + j]).toLong()
        sum += diff * diff
    }
    return sum
}
