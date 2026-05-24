package dev.santo.search

import java.io.DataInputStream
import java.io.InputStream

/**
 * Reads a prebuilt [BucketedVpTreeIndex] from the binary artifact built offline
 * (image build). Loaded as-is at startup, so the runtime never parses JSON nor
 * rebuilds any tree. The writing half lives in `tools.IndexWriter`; both share
 * [INDEX_MAGIC] and [BUCKET_COUNT] as the single source of the layout.
 *
 * Layout: magic, dim, n, quantized store (`n*dim` bytes), packed label bitset,
 * then per bucket the reordered ids and node thresholds.
 */
object IndexReader {

    fun readFrom(input: InputStream): BucketedVpTreeIndex {
        val inp = DataInputStream(input)
        require(inp.readInt() == INDEX_MAGIC) { "Bad index artifact: magic mismatch" }
        val dim = inp.readInt()
        val n = inp.readInt()

        val store = ByteArray(n * dim)
        inp.readFully(store)
        val labels = unpackBits(readExactly(inp, (n + 7) / 8), n)

        val bucketCount = inp.readInt()
        require(bucketCount == BUCKET_COUNT) { "Bad index artifact: bucket count $bucketCount" }
        val bucketIds = arrayOfNulls<IntArray>(BUCKET_COUNT)
        val bucketThresholds = arrayOfNulls<FloatArray>(BUCKET_COUNT)
        for (b in 0 until BUCKET_COUNT) {
            val size = inp.readInt()
            if (size == 0) continue
            val ids = IntArray(size) { inp.readInt() }
            val thresholds = FloatArray(size) { inp.readFloat() }
            bucketIds[b] = ids
            bucketThresholds[b] = thresholds
        }
        return BucketedVpTreeIndex.fromParts(store, labels, dim, bucketIds, bucketThresholds)
    }

    private fun unpackBits(bytes: ByteArray, count: Int): BooleanArray {
        val flags = BooleanArray(count)
        for (i in 0 until count) {
            flags[i] = (bytes[i ushr 3].toInt() and (1 shl (i and 7))) != 0
        }
        return flags
    }

    private fun readExactly(inp: DataInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        inp.readFully(bytes)
        return bytes
    }
}
