package dev.santo.search;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD squared-distance kernel over a SoA block of {@link #BLOCK} points. Points
 * are stored dimension-major within a block — {@code blocks[blockBase + d*BLOCK + slot]}
 * is dimension {@code d} of the point in {@code slot} — so one {@link ShortVector}
 * load holds the same dimension across all 16 points and the squared difference is
 * computed for 16 points at once (the champion's "scan_blocks" trick).
 *
 * <p>Written in Java rather than Kotlin so the {@code jdk.incubator.vector} module
 * flags stay confined to {@code JavaCompile} (Kotlin never imports the incubator
 * module). The hot path: per dimension, load 16 int16 codes, widen to two int32
 * vectors, subtract the broadcast query code, square (fits int32 because
 * {@code QUANT_SCALE=16000} keeps {@code (2*scale)^2 < 2^31}), then widen to four
 * int64 lanes and accumulate. Proven bit-exact vs the scalar loop and ~6.5× faster
 * in a GraalVM native image built with {@code -march=x86-64-v3} (AVX2) — but ONLY
 * at v3: an SSE-only target slow-emulates the Vector API (~230-300× slower), so the
 * native build must target AVX2 for this to pay off.
 */
public final class BlockDistance {

    /** Points per SoA block — matches the 256-bit ShortVector lane count (AVX2). */
    public static final int BLOCK = 16;

    private static final VectorSpecies<Short> S = ShortVector.SPECIES_256;   // 16 lanes
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_256;   // 8 lanes
    private static final VectorSpecies<Long> L = LongVector.SPECIES_256;     // 4 lanes

    private BlockDistance() {}

    /**
     * Writes the squared Euclidean distances from the query [codes] to the 16 points
     * of the block at [blockBase] into [out]{@code [0..15]} (out[slot] = distance to
     * the point in that slot). [dim] is the vector dimensionality.
     */
    public static void distances(int[] codes, short[] blocks, int blockBase, int dim, long[] out) {
        LongVector a0 = LongVector.zero(L), a1 = LongVector.zero(L),
                   a2 = LongVector.zero(L), a3 = LongVector.zero(L);
        for (int d = 0; d < dim; d++) {
            ShortVector pts = ShortVector.fromArray(S, blocks, blockBase + d * BLOCK);
            int q = codes[d];
            IntVector lo = (IntVector) pts.convertShape(VectorOperators.S2I, I, 0);
            IntVector hi = (IntVector) pts.convertShape(VectorOperators.S2I, I, 1);
            IntVector dlo = lo.sub(q), dhi = hi.sub(q);
            IntVector slo = dlo.mul(dlo), shi = dhi.mul(dhi);
            a0 = a0.add((LongVector) slo.convertShape(VectorOperators.I2L, L, 0));
            a1 = a1.add((LongVector) slo.convertShape(VectorOperators.I2L, L, 1));
            a2 = a2.add((LongVector) shi.convertShape(VectorOperators.I2L, L, 0));
            a3 = a3.add((LongVector) shi.convertShape(VectorOperators.I2L, L, 1));
        }
        a0.intoArray(out, 0);
        a1.intoArray(out, 4);
        a2.intoArray(out, 8);
        a3.intoArray(out, 12);
    }
}
