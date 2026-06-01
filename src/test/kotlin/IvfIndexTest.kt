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
}
