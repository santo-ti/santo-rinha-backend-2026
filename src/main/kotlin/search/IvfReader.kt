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
 * offsets (`k+1` ints, cumulative real counts = row ranges per cell), ROW-MAJOR int16
 * store (`n*dim` shorts, chunked), labels (`n` bytes, 1=fraud), k1, metaCentroids
 * (`k1*dim` floats), metaOfCell (`k` ints).
 */
object IvfReader {

    fun readFrom(input: InputStream, nprobe1: Int = DEFAULT_NPROBE1, nprobe2: Int = DEFAULT_NPROBE2): IvfIndex {
        val inp = DataInputStream(input)
        require(inp.readInt() == IVF_MAGIC) { "Bad IVF artifact: magic mismatch" }
        val dim = inp.readInt()
        val k = inp.readInt()
        val n = inp.readInt()

        val centroids = FloatArray(k * dim) { inp.readFloat() }
        val offsets = IntArray(k + 1) { inp.readInt() }

        val rows = ShortArray(n * dim)
        readShortsInto(inp, rows)
        val labels = readLabels(inp, n)

        val k1 = inp.readInt()
        val metaCentroids = FloatArray(k1 * dim) { inp.readFloat() }
        val metaOfCell = IntArray(k) { inp.readInt() }

        return IvfIndex(centroids, offsets, rows, labels, dim, k, metaCentroids, k1, metaOfCell, nprobe1, nprobe2)
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

    /** Reads [count] label bytes (1 = fraud) into a BooleanArray. */
    private fun readLabels(inp: DataInputStream, count: Int): BooleanArray {
        val bytes = ByteArray(count)
        inp.readFully(bytes)
        return BooleanArray(count) { bytes[it].toInt() != 0 }
    }
}
