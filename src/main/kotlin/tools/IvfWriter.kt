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
 * offsets (`k+1` ints, cumulative real counts), blockOffsets (`k+1` ints), SoA-16
 * block store (`totalBlocks*dim*BLOCK` shorts), packed block-label bitset
 * (`totalBlocks*BLOCK` bits), k1, metaCentroids (`k1*dim` floats), metaOfCell (`k` ints).
 */
object IvfWriter {

    fun writeTo(index: IvfIndex, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(IVF_MAGIC)
        out.writeInt(index.dim)
        out.writeInt(index.k)
        out.writeInt(index.offsets[index.k]) // n (= total real points)

        for (f in index.centroids) out.writeFloat(f)
        for (o in index.offsets) out.writeInt(o)
        for (b in index.blockOffsets) out.writeInt(b)
        out.write(shortsToBytes(index.blocks))
        out.write(packBits(index.blockLabels))

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

    private fun packBits(flags: BooleanArray): ByteArray {
        val bytes = ByteArray((flags.size + 7) / 8)
        for (i in flags.indices) {
            if (flags[i]) bytes[i ushr 3] = (bytes[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
        }
        return bytes
    }
}
