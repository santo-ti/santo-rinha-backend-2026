package dev.santo.tools

import dev.santo.search.BUCKET_COUNT
import dev.santo.search.BucketedVpTreeIndex
import dev.santo.search.INDEX_MAGIC
import java.io.DataOutputStream
import java.io.OutputStream

/**
 * Serializes a prebuilt [BucketedVpTreeIndex] to the binary artifact during the
 * offline image build. The reading half lives in `search.IndexReader`; both share
 * [INDEX_MAGIC] and [BUCKET_COUNT] as the single source of the layout.
 *
 * Layout: magic, dim, n, quantized store (`n*dim` bytes), packed label bitset,
 * then per bucket the reordered ids and node thresholds.
 */
object IndexWriter {

    fun writeTo(index: BucketedVpTreeIndex, output: OutputStream) {
        val out = DataOutputStream(output)
        val n = index.labels.size
        out.writeInt(INDEX_MAGIC)
        out.writeInt(index.dim)
        out.writeInt(n)
        out.write(index.store)
        out.write(packBits(index.labels))

        out.writeInt(BUCKET_COUNT)
        for (b in 0 until BUCKET_COUNT) {
            val tree = index.bucket(b)
            if (tree == null) {
                out.writeInt(0)
                continue
            }
            val ids = tree.orderedIds()
            val thresholds = tree.thresholds()
            out.writeInt(ids.size)
            for (id in ids) out.writeInt(id)
            for (t in thresholds) out.writeFloat(t)
        }
        out.flush()
    }

    private fun packBits(flags: BooleanArray): ByteArray {
        val bytes = ByteArray((flags.size + 7) / 8)
        for (i in flags.indices) {
            if (flags[i]) bytes[i ushr 3] = (bytes[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
        }
        return bytes
    }
}
