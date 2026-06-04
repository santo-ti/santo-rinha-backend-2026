package dev.santo

import dev.santo.dto.FraudScoreRequest
import dev.santo.fraud.isApproved
import dev.santo.fraud.scoreOf
import dev.santo.search.IvfReader
import dev.santo.tools.IvfBuilder
import dev.santo.tools.IvfWriter
import dev.santo.tools.References
import dev.santo.vectorization.ReferenceResources
import dev.santo.vectorization.Vectorizer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the IVF index against the brute-force oracles on the example dataset.
 * The key invariant: the search is EXACT branch-and-bound (bbox-pruned), so it must
 * reproduce exact quantized brute force REGARDLESS of the nprobe params — any divergence
 * is a bug in the IVF structure or the bounding-box prune, not quantization. The
 * `lowNprobe` index proves recall no longer depends on the probe count.
 */
class IvfIndexTest {

    private val references = References.parse(readTestResource("/example-references.json"))
    private val vectorizer = Vectorizer(
        ReferenceResources.loadNormalization(),
        ReferenceResources.loadMccRisk(),
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val quantizedOracle = QuantizedBruteForceIndex(references)
    private val doubleOracle = BruteForceIndex(references)

    private val k = minOf(16, references.size)
    // The search is exact by construction (bbox branch-and-bound), so nprobe is irrelevant.
    private val exact = IvfBuilder.build(references, k = k, metaCells = k, nprobe1 = k, nprobe2 = k)
    // Same index built with the SMALLEST nprobe: must STILL be exact (proves the prune,
    // not a high probe count, is what delivers recall). Uses real districts (metaCells<k).
    private val lowNprobe = IvfBuilder.build(references, k = k, metaCells = minOf(4, k), nprobe1 = 1, nprobe2 = 1)

    private fun queries(): List<DoubleArray> {
        val fromReferences = references.map { it.vector }
        val payloads = json.decodeFromString<List<FraudScoreRequest>>(readTestResource("/example-payloads.json"))
        return fromReferences + payloads.map { vectorizer.vectorize(it) }
    }

    @Test
    fun `IVF reproduces exact quantized brute force`() {
        var mismatches = 0
        for (q in queries()) {
            if (exact.nearestFraudCount(q) != quantizedOracle.nearestFraudCount(q)) mismatches++
        }
        assertEquals(0, mismatches, "IVF diverged from quantized brute force")
    }

    @Test
    fun `IVF stays exact even at the smallest nprobe (bbox prune, not probe count)`() {
        var mismatches = 0
        for (q in queries()) {
            if (lowNprobe.nearestFraudCount(q) != quantizedOracle.nearestFraudCount(q)) mismatches++
        }
        assertEquals(0, mismatches, "bbox branch-and-bound was not exact at nprobe=1 — prune bug")
    }

    @Test
    fun `IVF preserves the approve or deny decision versus the double oracle`() {
        var flips = 0
        for (q in queries()) {
            if (isApproved(exact.scoreOf(q)) != isApproved(doubleOracle.scoreOf(q))) flips++
        }
        assertEquals(0, flips, "IVF changed the decision versus the float oracle")
    }

    @Test
    fun `IVF survives a binary round trip unchanged`() {
        val bytes = ByteArrayOutputStream().also { IvfWriter.writeTo(exact, it) }.toByteArray()
        val restored = ByteArrayInputStream(bytes).use { IvfReader.readFrom(it, nprobe1 = k, nprobe2 = k) }
        for (q in queries()) {
            assertEquals(exact.nearestFraudCount(q), restored.nearestFraudCount(q), "round-trip diverged")
        }
    }

    @Test
    fun `every query yields a valid fraud count`() {
        for (q in queries()) assertTrue(exact.nearestFraudCount(q) in 0..5)
    }

    /**
     * The SIMD cell scan (IVF_SIMD_SCAN) computes the full 14-dim squared distance for all 16
     * points of a block at once — it must yield the exact same fraud count as the scalar
     * early-exit path (same squared distances → same top-5). Validated on the example corpus
     * and randomized queries (incl. the -1.0 sentinel) against the quantized brute force.
     */
    @Test
    fun `approximate search at nprobe=k visits all cells and equals brute force`() {
        val approx = IvfBuilder.build(references, k = k, metaCells = k, nprobe1 = k, nprobe2 = k)
            .also { it.approxNprobe = k; it.approxNdistrict = k } // all districts + all cells = exact
        var mismatches = 0
        for (q in queries()) if (approx.nearestFraudCount(q) != quantizedOracle.nearestFraudCount(q)) mismatches++
        assertEquals(0, mismatches, "approximate at nprobe=k diverged from brute force")
    }

    @Test
    fun `approximate search yields valid fraud counts at a small nprobe`() {
        val approx = IvfBuilder.build(references, k = k, metaCells = k, nprobe1 = k, nprobe2 = k)
            .also { it.approxNprobe = 1 }
        for (q in queries()) assertTrue(approx.nearestFraudCount(q) in 0..5)
    }

    @Test
    fun `SIMD cell scan matches the scalar path and the quantized oracle`() {
        val simd = IvfBuilder.build(references, k = k, metaCells = k, nprobe1 = k, nprobe2 = k)
            .also { it.simdScan = true }
        val rng = Random(4321)
        val dim = references.first().vector.size
        var mismatches = 0
        val qs = queries() + List(2000) { DoubleArray(dim) { if (rng.nextInt(15) == 0) -1.0 else rng.nextDouble() } }
        for (q in qs) {
            if (simd.nearestFraudCount(q) != quantizedOracle.nearestFraudCount(q)) mismatches++
        }
        assertEquals(0, mismatches, "SIMD cell scan diverged from the quantized brute force")
    }

    /**
     * The per-dimension early-exit in the cell scan must stay bit-exact: it only skips
     * points whose partial squared distance has already reached the current 5th-NN (which
     * `offer` would reject anyway), so the top-5 — and the fraud count — is identical to a
     * full-distance scan. Random queries sweep the cutoff across many worst-radius values
     * (and the -1.0 no-history sentinel), exercising the early-exit far more than the tiny
     * example corpus does.
     */
    @Test
    fun `early-exit cell scan matches quantized brute force on randomized queries`() {
        val rng = Random(1234)
        val dim = references.first().vector.size
        var mismatches = 0
        repeat(2000) {
            val q = DoubleArray(dim) { if (rng.nextInt(15) == 0) -1.0 else rng.nextDouble() }
            if (exact.nearestFraudCount(q) != quantizedOracle.nearestFraudCount(q)) mismatches++
        }
        assertEquals(0, mismatches, "early-exit scan diverged from quantized brute force on random queries")
    }
}
