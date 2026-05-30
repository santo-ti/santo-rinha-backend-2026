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
 * The key invariant: at `nprobe == k` every cell is scanned, so IVF must reproduce
 * exact quantized brute force — any divergence is a bug in the IVF structure, not
 * quantization. Lower nprobe is the speed/recall lever, exercised faithfully on the
 * full 3M randomized-date set by the offline oracle (not here).
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
    private val exact = IvfBuilder.build(references, k = k, nprobe = k)

    private fun queries(): List<DoubleArray> {
        val fromReferences = references.map { it.vector }
        val payloads = json.decodeFromString<List<FraudScoreRequest>>(readTestResource("/example-payloads.json"))
        return fromReferences + payloads.map { vectorizer.vectorize(it) }
    }

    @Test
    fun `IVF at nprobe equal k reproduces exact quantized brute force`() {
        var mismatches = 0
        for (q in queries()) {
            if (exact.nearestFraudCount(q) != quantizedOracle.nearestFraudCount(q)) mismatches++
        }
        assertEquals(0, mismatches, "IVF (nprobe=k) diverged from quantized brute force")
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
        val restored = ByteArrayInputStream(bytes).use { IvfReader.readFrom(it, nprobe = k) }
        for (q in queries()) {
            assertEquals(exact.nearestFraudCount(q), restored.nearestFraudCount(q), "round-trip diverged")
        }
    }

    @Test
    fun `every query yields a valid fraud count`() {
        for (q in queries()) assertTrue(exact.nearestFraudCount(q) in 0..5)
    }
}
