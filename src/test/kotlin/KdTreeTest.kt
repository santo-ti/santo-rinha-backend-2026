package dev.santo

import dev.santo.dto.FraudScoreRequest
import dev.santo.search.KdTreeReader
import dev.santo.search.quantizeVector
import dev.santo.tools.KdTreeBuilder
import dev.santo.tools.KdTreeWriter
import dev.santo.tools.References
import dev.santo.vectorization.ReferenceResources
import dev.santo.vectorization.VECTOR_DIMENSIONS
import dev.santo.vectorization.Vectorizer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the KD-tree against the quantized brute-force oracle on the example dataset.
 * At an unbounded visit budget the BBF branch-and-bound is EXACT, so it must reproduce the
 * quantized brute-force 5-NN fraud count exactly (E=0) — any divergence is a tree/search bug.
 */
class KdTreeTest {

    private val references = References.parse(readTestResource("/example-references.json"))
    private val vectorizer = Vectorizer(ReferenceResources.loadNormalization(), ReferenceResources.loadMccRisk())
    private val json = Json { ignoreUnknownKeys = true }
    private val quantizedOracle = QuantizedBruteForceIndex(references)
    private val dim = VECTOR_DIMENSIONS

    private val index = run {
        val n = references.size
        val vectors = ShortArray(n * dim)
        val labels = BooleanArray(n)
        for (i in 0 until n) {
            System.arraycopy(quantizeVector(references[i].vector), 0, vectors, i * dim, dim)
            labels[i] = references[i].isFraud
        }
        KdTreeBuilder.build(vectors, labels, n, dim)
    }

    private fun queries(): List<DoubleArray> {
        val fromReferences = references.map { it.vector }
        val payloads = json.decodeFromString<List<FraudScoreRequest>>(readTestResource("/example-payloads.json"))
        return fromReferences + payloads.map { vectorizer.vectorize(it) }
    }

    @Test
    fun `exact KD-tree reproduces quantized brute force`() {
        var mismatches = 0
        for (q in queries()) {
            if (index.nearestFraudCount(q) != quantizedOracle.nearestFraudCount(q)) mismatches++
        }
        assertEquals(0, mismatches, "KD-tree (exact budget) diverged from quantized brute force")
    }

    @Test
    fun `exact KD-tree matches brute force on randomized queries`() {
        val rng = Random(99)
        var mismatches = 0
        repeat(2000) {
            val q = DoubleArray(dim) { if (rng.nextInt(15) == 0) -1.0 else rng.nextDouble() }
            if (index.nearestFraudCount(q) != quantizedOracle.nearestFraudCount(q)) mismatches++
        }
        assertEquals(0, mismatches, "KD-tree diverged on randomized queries")
    }

    @Test
    fun `KD-tree survives a binary round trip unchanged`() {
        val bytes = ByteArrayOutputStream().also { KdTreeWriter.writeTo(index, it) }.toByteArray()
        val restored = ByteArrayInputStream(bytes).use { KdTreeReader.readFrom(it) }
        for (q in queries()) {
            assertEquals(index.nearestFraudCount(q), restored.nearestFraudCount(q), "round-trip diverged")
        }
    }

    @Test
    fun `every query yields a valid fraud count`() {
        for (q in queries()) assertTrue(index.nearestFraudCount(q) in 0..5)
    }
}
