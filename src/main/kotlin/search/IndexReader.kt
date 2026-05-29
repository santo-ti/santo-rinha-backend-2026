package dev.santo.search

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Reads a prebuilt [BucketedVpTreeIndex] from the binary artifact built offline
 * (image build). Loaded as-is at startup, so the runtime never parses JSON nor
 * rebuilds any tree. The writing half lives in `tools.IndexWriter`; both share
 * [INDEX_MAGIC] and [BUCKET_COUNT] as the single source of the layout.
 *
 * Layout: magic, dim, n, int16 store (`n*dim` shorts, big-endian), packed label
 * bitset, then per bucket its point count and node thresholds. Points are stored
 * contiguously in tree order (id = bucketBase + position), so no id array is read.
 */
object IndexReader {

    fun readFrom(input: InputStream, searchBudget: Int = Int.MAX_VALUE): BucketedVpTreeIndex {
        val inp = DataInputStream(input)
        require(inp.readInt() == INDEX_MAGIC) { "Bad index artifact: magic mismatch" }
        val dim = inp.readInt()
        val n = inp.readInt()

        val store = ShortArray(n * dim)
        readShortsInto(inp, store) // chunked: avoids a 2nd full-size temp buffer at load
        val labels = unpackBits(readExactly(inp, (n + 7) / 8), n)

        val bucketCount = inp.readInt()
        require(bucketCount == BUCKET_COUNT) { "Bad index artifact: bucket count $bucketCount" }
        val bucketSizes = IntArray(BUCKET_COUNT)
        val bucketThresholds = arrayOfNulls<FloatArray>(BUCKET_COUNT)
        for (b in 0 until BUCKET_COUNT) {
            val size = inp.readInt()
            bucketSizes[b] = size
            if (size == 0) continue
            bucketThresholds[b] = FloatArray(size) { inp.readFloat() }
        }
        return BucketedVpTreeIndex.fromParts(store, labels, dim, bucketSizes, bucketThresholds, searchBudget)
    }

    private fun unpackBits(bytes: ByteArray, count: Int): BooleanArray {
        val flags = BooleanArray(count)
        for (i in 0 until count) {
            flags[i] = (bytes[i ushr 3].toInt() and (1 shl (i and 7))) != 0
        }
        return flags
    }

    /** Reads big-endian shorts into [dst] in small chunks (peak buffer 64KB), so a
     *  3M×14 int16 store never needs a second full-size byte buffer at startup. */
    private fun readShortsInto(inp: DataInputStream, dst: ShortArray) {
        val chunkShorts = 1 shl 15 // 32K shorts = 64KB
        val buf = ByteArray(chunkShorts * 2)
        var i = 0
        while (i < dst.size) {
            val count = minOf(chunkShorts, dst.size - i)
            inp.readFully(buf, 0, count * 2)
            ByteBuffer.wrap(buf, 0, count * 2).asShortBuffer().get(dst, i, count)
            i += count
        }
    }

    private fun readExactly(inp: DataInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        inp.readFully(bytes)
        return bytes
    }
}
