package dev.santo.index

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Binary serialization for a prebuilt [BucketedVpTreeIndex]. Built offline (image
 * build) and loaded as-is at startup, so the runtime never parses JSON nor
 * rebuilds any tree.
 *
 * Layout: magic, dim, n, quantized store (`n*dim` bytes), packed label bitset,
 * then per bucket the reordered ids and node thresholds.
 */
object IndexCodec {
    private const val MAGIC = 0x46534931 // "FSI1"

    fun writeTo(index: BucketedVpTreeIndex, output: OutputStream) {
        val out = DataOutputStream(output)
        val n = index.labels.size
        out.writeInt(MAGIC)
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

    fun readFrom(input: InputStream): BucketedVpTreeIndex {
        val inp = DataInputStream(input)
        require(inp.readInt() == MAGIC) { "Bad index artifact: magic mismatch" }
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

    private fun packBits(flags: BooleanArray): ByteArray {
        val bytes = ByteArray((flags.size + 7) / 8)
        for (i in flags.indices) {
            if (flags[i]) bytes[i ushr 3] = (bytes[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
        }
        return bytes
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
