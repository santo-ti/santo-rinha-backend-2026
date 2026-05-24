package dev.santo

import dev.santo.index.BucketedVpTreeIndex
import dev.santo.index.IndexCodec
import dev.santo.index.References
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class IndexCodecTest {

    private val references = References.parse(readTestResource("/example-references.json"))

    @Test
    fun `round trip preserves fraud counts for every reference`() {
        val original = BucketedVpTreeIndex.build(references)

        val buffer = ByteArrayOutputStream()
        IndexCodec.writeTo(original, buffer)
        val restored = IndexCodec.readFrom(ByteArrayInputStream(buffer.toByteArray()))

        for (reference in references) {
            assertEquals(
                original.nearestFraudCount(reference.vector),
                restored.nearestFraudCount(reference.vector),
                "fraud count diverged after serialization round trip",
            )
        }
    }
}
