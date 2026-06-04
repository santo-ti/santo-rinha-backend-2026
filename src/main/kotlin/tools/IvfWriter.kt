package dev.santo.tools

import dev.santo.search.IVF_MAGIC
import dev.santo.search.IvfIndex
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Serializes a prebuilt two-level [IvfIndex] to the binary artifact during the
 * offline image build. The reading half lives in `search.IvfReader`; both share
 * [IVF_MAGIC] as the layout's single source of truth.
 *
 * Layout: magic, dim, k, n, centroids (`k*dim` floats, centroid-major, big-endian),
 * offsets (`k+1` ints, cumulative real counts = row ranges per cell), ROW-MAJOR int16
 * store (`n*dim` shorts), labels (`n` bytes, 1=fraud), k1, metaCentroids (`k1*dim` floats),
 * metaOfCell (`k` ints).
 */
object IvfWriter {

    fun writeTo(index: IvfIndex, output: OutputStream) {
        val out = DataOutputStream(output)
        val n = index.offsets[index.k]
        out.writeInt(IVF_MAGIC)
        out.writeInt(index.dim)
        out.writeInt(index.k)
        out.writeInt(n) // n (= total real points)

        for (f in index.centroids) out.writeFloat(f)
        for (o in index.offsets) out.writeInt(o)
        out.write(shortsToBytes(index.rows))
        out.write(labelBytes(index.labels))

        out.writeInt(index.k1)
        for (f in index.metaCentroids) out.writeFloat(f)
        for (m in index.metaOfCell) out.writeInt(m)
        out.flush()
    }

    private fun shortsToBytes(store: ShortArray): ByteArray {
        val bytes = ByteArray(store.size * 2)
        ByteBuffer.wrap(bytes).asShortBuffer().put(store)
        return bytes
    }

    private fun labelBytes(flags: BooleanArray): ByteArray =
        ByteArray(flags.size) { if (flags[it]) 1 else 0 }
}
