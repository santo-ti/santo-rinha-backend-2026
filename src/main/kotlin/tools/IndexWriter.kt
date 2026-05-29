package dev.santo.tools

import dev.santo.search.BUCKET_COUNT
import dev.santo.search.BucketedVpTreeIndex
import dev.santo.search.INDEX_MAGIC
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Serializes a prebuilt [BucketedVpTreeIndex] to the binary artifact during the
 * offline image build. The reading half lives in `search.IndexReader`; both share
 * [INDEX_MAGIC] and [BUCKET_COUNT] as the single source of the layout.
 *
 * Layout: magic, dim, n, int16 store (`n*dim` shorts, big-endian), packed label
 * bitset, then per bucket its point count and node thresholds. Points are stored
 * contiguously in tree order, so a point's id is `bucketBase + position` — no id
 * array is serialized.
 */
object IndexWriter {

    fun writeTo(index: BucketedVpTreeIndex, output: OutputStream) {
        val out = DataOutputStream(output)
        val n = index.labels.size
        out.writeInt(INDEX_MAGIC)
        out.writeInt(index.dim)
        out.writeInt(n)
        out.write(shortsToBytes(index.store))
        out.write(packBits(index.labels))

        out.writeInt(BUCKET_COUNT)
        for (b in 0 until BUCKET_COUNT) {
            val tree = index.bucket(b)
            if (tree == null) {
                out.writeInt(0)
                continue
            }
            // Points are stored contiguously in tree order, so only the per-bucket
            // size and node thresholds are needed — no id array (it is base+position).
            out.writeInt(tree.size)
            for (t in tree.thresholds()) out.writeFloat(t)
        }
        out.flush()
    }

    /** Big-endian byte image of the int16 store (2 bytes per dimension). */
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
