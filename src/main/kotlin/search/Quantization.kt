package dev.santo.search

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

/**
 * Quantizes one dimension straight to its logical code, equal to
 * `logicalCode(quantize(value))` but without the intermediate byte. Used on the
 * query side of a search so each dimension's logical code is computed once for
 * the whole traversal instead of per visited node.
 */
fun quantizeToLogicalCode(value: Double): Int =
    if (value == NO_HISTORY_SENTINEL) SENTINEL_LOGICAL
    else Math.round(value.coerceIn(0.0, 1.0) * QUANT_SCALE).toInt()
