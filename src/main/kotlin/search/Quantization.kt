package dev.santo.search

import dev.santo.vectorization.NO_HISTORY_SENTINEL

/**
 * int16 quantization. Real values in `[0,1]` map to `[0, QUANT_SCALE]`; the
 * no-history sentinel (`-1.0`) gets a reserved negative code that stays
 * proportionally distant from the `[0,1]` range. QUANT_SCALE fits a signed
 * `Short`, so the stored code IS the logical value — no unsigned remap needed.
 *
 * 129× finer than the former int8 scale (254): measured offline, int8 flipped
 * ~68 borderline k-NN decisions over the 54100 official queries vs the float
 * oracle, while int16 flips zero — lifting the detection ceiling to ~exact.
 */
const val QUANT_SCALE = 32766
private const val SENTINEL_CODE = -QUANT_SCALE // -1.0 maps here

/** Quantizes one dimension to a signed short; `-1.0` becomes the reserved sentinel. */
fun quantize(value: Double): Short =
    if (value == NO_HISTORY_SENTINEL) SENTINEL_CODE.toShort()
    else Math.round(value.coerceIn(0.0, 1.0) * QUANT_SCALE).toShort()

fun quantizeVector(v: DoubleArray): ShortArray = ShortArray(v.size) { quantize(v[it]) }

/**
 * Quantizes one dimension straight to its logical code (an `Int`), equal to
 * `quantize(value).toInt()`. Used on the query side of a search so each
 * dimension's code is computed once for the whole traversal instead of per node.
 */
fun quantizeToLogicalCode(value: Double): Int =
    if (value == NO_HISTORY_SENTINEL) SENTINEL_CODE
    else Math.round(value.coerceIn(0.0, 1.0) * QUANT_SCALE).toInt()
