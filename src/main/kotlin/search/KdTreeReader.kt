package dev.santo.search

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Reads a prebuilt [KdTreeIndex] from the binary artifact (built offline at image build).
 * Layout: magic, dim, n, root, nodeVec (`n*dim` int16, node-major, big-endian, chunked),
 * packed (`n` ints), node-label bitset (`n` bits). The writer is `tools.KdTreeWriter`;
 * both share [KDTREE_MAGIC].
 */
object KdTreeReader {

    fun readFrom(input: InputStream): KdTreeIndex {
        val inp = DataInputStream(input)
        require(inp.readInt() == KDTREE_MAGIC) { "Bad KD-tree artifact: magic mismatch" }
        val dim = inp.readInt()
        val n = inp.readInt()
        val root = inp.readInt()

        val nodeVec = ShortArray(n * dim)
        readShortsInto(inp, nodeVec)
        val packed = IntArray(n) { inp.readInt() }
        val label = unpackBits(readExactly(inp, (n + 7) / 8), n)

        return KdTreeIndex(nodeVec, label, packed, root, dim, n)
    }

    private fun readShortsInto(inp: DataInputStream, dst: ShortArray) {
        val chunkShorts = 1 shl 15
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
