package dev.santo.search

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Reads a prebuilt two-level [IvfIndex] from the binary artifact built offline
 * (image build). Loaded as-is at startup; the runtime never parses JSON nor runs
 * k-means. The writing half lives in `tools.IvfWriter`; both share [IVF_MAGIC] as
 * the layout's single source of truth.
 *
 * Layout: magic, dim, k, n, centroids (`k*dim` floats, centroid-major, big-endian),
 * offsets (`k+1` ints, cumulative real counts), blockOffsets (`k+1` ints), SoA-16
 * block store (`totalBlocks*dim*BLOCK` shorts, chunked), packed block-label bitset
 * (`totalBlocks*BLOCK` bits), k1, metaCentroids (`k1*dim` floats), metaOfCell (`k` ints).
 */
object IvfReader {

    fun readFrom(input: InputStream, nprobe1: Int = DEFAULT_NPROBE1, nprobe2: Int = DEFAULT_NPROBE2): IvfIndex {
        val inp = DataInputStream(input)
        require(inp.readInt() == IVF_MAGIC) { "Bad IVF artifact: magic mismatch" }
        val dim = inp.readInt()
        val k = inp.readInt()
        inp.readInt() // n (= offsets[k]); kept in the header for inspection

        val centroids = FloatArray(k * dim) { inp.readFloat() }
        val offsets = IntArray(k + 1) { inp.readInt() }
        val blockOffsets = IntArray(k + 1) { inp.readInt() }

        val block = BlockDistance.BLOCK
        val totalBlocks = blockOffsets[k]
        val blocks = ShortArray(totalBlocks * dim * block)
        readShortsInto(inp, blocks)
        val blockSlots = totalBlocks * block
        val blockLabels = unpackBits(readExactly(inp, (blockSlots + 7) / 8), blockSlots)

        val k1 = inp.readInt()
        val metaCentroids = FloatArray(k1 * dim) { inp.readFloat() }
        val metaOfCell = IntArray(k) { inp.readInt() }

        return IvfIndex(centroids, offsets, blocks, blockOffsets, blockLabels, dim, k, metaCentroids, k1, metaOfCell, nprobe1, nprobe2)
    }

    /** Reads big-endian shorts into [dst] in 64KB chunks (no second full-size buffer). */
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

    private fun unpackBits(bytes: ByteArray, count: Int): BooleanArray {
        val flags = BooleanArray(count)
        for (i in 0 until count) flags[i] = (bytes[i ushr 3].toInt() and (1 shl (i and 7))) != 0
        return flags
    }

    private fun readExactly(inp: DataInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        inp.readFully(bytes)
        return bytes
    }
}
