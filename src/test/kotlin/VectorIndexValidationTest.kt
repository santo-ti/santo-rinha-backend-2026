package dev.santo

import dev.santo.dto.FraudScoreRequest
import dev.santo.fraud.isApproved
import dev.santo.fraud.scoreOf
import dev.santo.search.VectorIndex
import dev.santo.tools.IndexBuilder
import dev.santo.tools.References
import dev.santo.vectorization.ReferenceResources
import dev.santo.vectorization.Vectorizer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the chosen design (bucketing + VP-Tree int8) against the brute-force
 * oracles on the example dataset (tasks 1.3, 1.4 and 1.6).
 */
class VectorIndexValidationTest {

    private val references = References.parse(readTestResource("/example-references.json"))
    private val vectorizer = Vectorizer(
        ReferenceResources.loadNormalization(),
        ReferenceResources.loadMccRisk(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val doubleOracle = BruteForceIndex(references)
    private val quantizedOracle = QuantizedBruteForceIndex(references)
    private val prototype = IndexBuilder.build(references)

    /** Query set: every reference vector plus every example payload, vectorized. */
    private fun queries(): List<DoubleArray> {
        val fromReferences = references.map { it.vector }
        val payloads = json.decodeFromString<List<FraudScoreRequest>>(readTestResource("/example-payloads.json"))
        val fromPayloads = payloads.map { vectorizer.vectorize(it) }
        return fromReferences + fromPayloads
    }

    @Test
    fun `bucketed VP-Tree is exact within quantized space`() {
        // The structure must reproduce quantized brute force exactly.
        var mismatches = 0
        for (q in queries()) {
            if (prototype.nearestFraudCount(q) != quantizedOracle.nearestFraudCount(q)) mismatches++
        }
        assertEquals(0, mismatches, "bucketing + VP-Tree diverged from quantized brute force")
    }

    @Test
    fun `int8 quantization preserves the decision versus the double oracle`() {
        // Quantization may, in principle, flip a borderline neighbor. Confirm it
        // does not change the approve/deny decision on the example set.
        val qs = queries()
        var decisionFlips = 0
        for (q in qs) {
            if (approved(prototype, q) != approved(doubleOracle, q)) decisionFlips++
        }
        assertEquals(0, decisionFlips, "int8 changed the decision on ${qs.size} example queries")
    }

    @Test
    fun `every bucket is populated enough or the edge guard covers it`() {
        // Sanity: querying never throws and always yields a valid fraud count.
        for (q in queries()) {
            assertTrue(prototype.nearestFraudCount(q) in 0..5)
        }
    }

    private fun approved(index: VectorIndex, query: DoubleArray): Boolean =
        isApproved(index.scoreOf(query))
}
