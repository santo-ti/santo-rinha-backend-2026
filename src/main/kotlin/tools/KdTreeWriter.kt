package dev.santo.tools

import dev.santo.search.KDTREE_MAGIC
import dev.santo.search.KdTreeIndex
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** Serializes a [KdTreeIndex] to the binary artifact read by `search.KdTreeReader`. */
object KdTreeWriter {

    fun writeTo(index: KdTreeIndex, out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(KDTREE_MAGIC)
        d.writeInt(index.dim)
        d.writeInt(index.n)
        d.writeInt(index.root)
        writeShorts(d, index.nodeVec)
        for (p in index.packed) d.writeInt(p)
        writeBits(d, index.nodeLabel)
        d.flush()
    }

    private fun writeShorts(d: DataOutputStream, src: ShortArray) {
        val chunkShorts = 1 shl 15
        val buf = ByteArray(chunkShorts * 2)
        var i = 0
        while (i < src.size) {
            val count = minOf(chunkShorts, src.size - i)
            ByteBuffer.wrap(buf, 0, count * 2).asShortBuffer().put(src, i, count)
            d.write(buf, 0, count * 2)
            i += count
        }
    }

    private fun writeBits(d: DataOutputStream, flags: BooleanArray) {
        val bytes = ByteArray((flags.size + 7) / 8)
        for (i in flags.indices) if (flags[i]) bytes[i ushr 3] = (bytes[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
        d.write(bytes)
    }
}
