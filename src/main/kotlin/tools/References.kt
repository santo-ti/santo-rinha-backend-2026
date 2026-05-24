package dev.santo.tools

import dev.santo.search.LabeledVector
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

@Serializable
private class ReferenceRecord(val vector: DoubleArray, val label: String)

/** Parses the `{ vector, label }` array format used by the reference dataset. */
object References {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(jsonText: String): List<LabeledVector> =
        json.decodeFromString<List<ReferenceRecord>>(jsonText).toLabeled()

    /** Streaming parse — avoids materializing the full ~284 MB JSON as a String. */
    @OptIn(ExperimentalSerializationApi::class)
    fun parse(input: InputStream): List<LabeledVector> =
        json.decodeFromStream<List<ReferenceRecord>>(input).toLabeled()

    private fun List<ReferenceRecord>.toLabeled(): List<LabeledVector> =
        map { LabeledVector(it.vector, it.label == "fraud") }
}
