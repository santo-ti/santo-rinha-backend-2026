package dev.santo

import dev.santo.search.BlockDistance
import dev.santo.search.squaredDistance
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The SIMD block kernel must reproduce the scalar squared distance bit-exactly —
 * any divergence would silently change k-NN rankings. The same point is laid out
 * both AoS (scalar) and SoA-16 (kernel) from one source so a lane-mapping or
 * widening-cascade bug shows up as a mismatch. Runs on the JVM (HotSpot vectorizes
 * the Vector API where the CPU allows; the result is identical either way).
 */
class BlockDistanceTest {

    private val dim = 14
    private val scale = 16000

    @Test
    fun `SIMD block kernel matches scalar squared distance bit-exact`() {
        val rng = Random(7)
        val blocksCount = 32
        val block = BlockDistance.BLOCK
        val n = blocksCount * block
        val aos = ShortArray(n * dim)
        val blocks = ShortArray(blocksCount * dim * block)
        for (pi in 0 until n) {
            val b = pi / block
            val slot = pi % block
            for (d in 0 until dim) {
                var code = rng.nextInt(2 * scale + 1) - scale
                if (rng.nextInt(20) == 0) code = -scale // sprinkle the no-history sentinel
                aos[pi * dim + d] = code.toShort()
                blocks[b * dim * block + d * block + slot] = code.toShort()
            }
        }
        val q = IntArray(dim) { rng.nextInt(2 * scale + 1) - scale }

        val out = LongArray(block)
        for (b in 0 until blocksCount) {
            BlockDistance.distances(q, blocks, b * dim * block, dim, out)
            for (slot in 0 until block) {
                val pi = b * block + slot
                val expected = squaredDistance(q, aos, pi * dim, dim)
                assertEquals(expected, out[slot], "block=$b slot=$slot diverged from scalar")
            }
        }
    }
}
