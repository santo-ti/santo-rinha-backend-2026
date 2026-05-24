package dev.santo.search

/** Squared Euclidean distance between a quantized query and a stored point at [offset]. */
fun squaredDistance(query: ByteArray, store: ByteArray, offset: Int, dim: Int): Long {
    var sum = 0L
    for (j in 0 until dim) {
        val diff = (logicalCode(query[j]) - logicalCode(store[offset + j])).toLong()
        sum += diff * diff
    }
    return sum
}

/** Squared Euclidean distance between two stored points. */
fun squaredDistance(store: ByteArray, offsetA: Int, offsetB: Int, dim: Int): Long {
    var sum = 0L
    for (j in 0 until dim) {
        val diff = (logicalCode(store[offsetA + j]) - logicalCode(store[offsetB + j])).toLong()
        sum += diff * diff
    }
    return sum
}
