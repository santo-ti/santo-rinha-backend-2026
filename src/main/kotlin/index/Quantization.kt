package dev.santo.index

import dev.santo.vectorization.NO_HISTORY_SENTINEL

/** Real values in `[0,1]` map to `[0, QUANT_SCALE]`; the sentinel gets a reserved code. */
const val QUANT_SCALE = 254
private const val SENTINEL_UNSIGNED = 255
private const val SENTINEL_LOGICAL = -QUANT_SCALE // keeps -1.0 proportionally distant from [0,1]

/** Quantizes one dimension to a signed byte; `-1.0` becomes the reserved sentinel code. */
fun quantize(value: Double): Byte =
    if (value == NO_HISTORY_SENTINEL) {
        SENTINEL_UNSIGNED.toByte()
    } else {
        Math.round(value.coerceIn(0.0, 1.0) * QUANT_SCALE).toInt().toByte()
    }

fun quantizeVector(v: DoubleArray): ByteArray = ByteArray(v.size) { quantize(v[it]) }

/** Maps a stored byte to its logical integer position for distance computation. */
fun logicalCode(b: Byte): Int {
    val u = b.toInt() and 0xFF
    return if (u == SENTINEL_UNSIGNED) SENTINEL_LOGICAL else u
}

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
