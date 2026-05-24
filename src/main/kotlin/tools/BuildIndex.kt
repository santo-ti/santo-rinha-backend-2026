package dev.santo.tools

import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Offline build step: `references.json[.gz]` -> compact binary index artifact.
 * Run during the image build so the runtime only loads the artifact (no JSON
 * parse, no tree build at startup). Give the JVM enough heap for ~3M vectors,
 * e.g. `java -Xmx4g -cp app.jar dev.santo.tools.BuildIndexKt refs.json.gz index.bin`.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: BuildIndex <input.json|.json.gz> <output.bin>" }
    val input = File(args[0])
    val output = File(args[1])

    val references = input.inputStream().buffered().use { raw ->
        val stream = if (input.name.endsWith(".gz")) GZIPInputStream(raw) else raw
        References.parse(stream)
    }

    val index = IndexBuilder.build(references)
    output.outputStream().buffered().use { IndexWriter.writeTo(index, it) }
    println("Built index: ${references.size} references -> ${output.length()} bytes at ${output.path}")
}
