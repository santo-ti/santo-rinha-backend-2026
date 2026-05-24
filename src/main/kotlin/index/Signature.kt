package dev.santo.index

import dev.santo.vectorization.NO_HISTORY_SENTINEL

/** Number of categorical buckets: 2^4 over the four categorical dimensions. */
const val BUCKET_COUNT = 16

/**
 * Categorical signature of a vector: a 4-bit key over the dimensions whose
 * mismatch dominates Euclidean distance (`has_history`, `is_online`,
 * `card_present`, `unknown_merchant`). The true nearest neighbors almost always
 * share this signature, which makes bucketing a cheap, exact first-level prune.
 */
fun signatureOf(v: DoubleArray): Int {
    var s = 0
    if (v[5] != NO_HISTORY_SENTINEL) s = s or 1 // has_history (indices 5 and 6)
    if (v[9] >= 0.5) s = s or 2 // is_online
    if (v[10] >= 0.5) s = s or 4 // card_present
    if (v[11] >= 0.5) s = s or 8 // unknown_merchant
    return s
}

/**
 * Minimum possible squared distance (in quantized space) between a query of
 * signature [a] and any point in a bucket of signature [b], from categorical
 * mismatch alone. Used to skip buckets that cannot hold a closer neighbor.
 * `has_history` spans two dimensions (5 and 6), so it counts double.
 */
fun categoricalLowerBoundSquared(a: Int, b: Int): Long {
    val diff = a xor b
    var weight = 0L
    if (diff and 1 != 0) weight += 2
    if (diff and 2 != 0) weight += 1
    if (diff and 4 != 0) weight += 1
    if (diff and 8 != 0) weight += 1
    return weight * QUANT_SCALE.toLong() * QUANT_SCALE.toLong()
}
