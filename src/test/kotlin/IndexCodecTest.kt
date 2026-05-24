package dev.santo

import dev.santo.search.IndexReader
import dev.santo.tools.IndexBuilder
import dev.santo.tools.IndexWriter
import dev.santo.tools.References
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class IndexCodecTest {

    private val references = References.parse(readTestResource("/example-references.json"))

    @Test
    fun `round trip preserves fraud counts for every reference`() {
        val original = IndexBuilder.build(references)

        val buffer = ByteArrayOutputStream()
        IndexWriter.writeTo(original, buffer)
        val restored = IndexReader.readFrom(ByteArrayInputStream(buffer.toByteArray()))

        for (reference in references) {
            assertEquals(
                original.nearestFraudCount(reference.vector),
                restored.nearestFraudCount(reference.vector),
                "fraud count diverged after serialization round trip",
            )
        }
    }
}
