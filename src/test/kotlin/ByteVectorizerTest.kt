package dev.santo

import dev.santo.dto.FraudScoreRequest
import dev.santo.search.quantizeVector
import dev.santo.vectorization.ByteVectorizer
import dev.santo.vectorization.ReferenceResources
import dev.santo.vectorization.Vectorizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The byte-scan [ByteVectorizer] must be a drop-in for the DTO-based [Vectorizer]: for every
 * example payload its QUANTIZED vector (the int16 codes the search actually consumes) must be
 * bit-identical to the DTO path. Identical codes ⇒ identical k-NN ⇒ the 0-error detection of
 * the proven path is preserved by construction — not just approximately. Tested against both
 * the compact and the pretty-printed (whitespace-heavy) encodings to exercise the scanner.
 */
class ByteVectorizerTest {

    private val constants = ReferenceResources.loadNormalization()
    private val mccRisk = ReferenceResources.loadMccRisk()
    private val dtoVectorizer = Vectorizer(constants, mccRisk)
    private val byteVectorizer = ByteVectorizer(constants, mccRisk)

    private val compact = Json
    private val pretty = Json { prettyPrint = true }
    private val decoder = Json { ignoreUnknownKeys = true }

    private fun payloads() = compact
        .parseToJsonElement(readTestResource("/example-payloads.json"))
        .jsonArray

    @Test
    fun `byte-scan quantized vector matches the DTO path on every example payload (compact)`() {
        assertMatches { compact.encodeToString(it) }
    }

    @Test
    fun `byte-scan quantized vector matches the DTO path on every example payload (pretty)`() {
        assertMatches { pretty.encodeToString(it) }
    }

    private inline fun assertMatches(encode: (kotlinx.serialization.json.JsonElement) -> String) {
        var checked = 0
        for (element in payloads()) {
            val raw = encode(element)
            val bytes = raw.toByteArray(Charsets.UTF_8)
            val request = decoder.decodeFromString(FraudScoreRequest.serializer(), raw)

            val expected = quantizeVector(dtoVectorizer.vectorize(request))
            val actual = quantizeVector(byteVectorizer.vectorize(bytes, bytes.size))
            assertContentEquals(expected, actual, "quantized codes diverged for payload #$checked: $raw")
            checked++
        }
        assertEquals(payloads().count(), checked)
        assert(checked >= 40) { "expected a substantial example payload set, got $checked" }
    }
}
