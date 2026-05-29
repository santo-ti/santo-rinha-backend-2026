package dev.santo.tools

import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Offline build step: `references.json[.gz]` -> compact binary index artifact.
 * Run during the image build so the runtime only loads the artifact (no JSON
 * parse, no tree build at startup). Give the JVM enough heap for ~3M vectors,
 * e.g. `java -Xmx4g -cp app.jar dev.santo.tools.BuildIndexKt refs.json.gz index.bin [maxSize]`.
 *
 * Optional [maxSize] argument caps the index to a uniform random sample. The
 * exact tree degenerates at 14 dims and cannot serve 900 rps on 0.425 CPU —
 * sampling trades a slice of detection precision (FP/FN, weight 1/3) for far
 * fewer HTTP timeouts (weight 5). See [IndexBuilder] for the rationale.
 */
fun main(args: Array<String>) {
    require(args.size in 2..3) { "Usage: BuildIndex <input.json|.json.gz> <output.bin> [maxSize]" }
    val input = File(args[0])
    val output = File(args[1])
    val maxSize = args.getOrNull(2)?.toInt() ?: Int.MAX_VALUE

    val references = input.inputStream().buffered().use { raw ->
        val stream = if (input.name.endsWith(".gz")) GZIPInputStream(raw) else raw
        References.parse(stream)
    }

    val index = IndexBuilder.build(references, maxSize = maxSize)
    output.outputStream().buffered().use { IndexWriter.writeTo(index, it) }
    val cap = if (maxSize == Int.MAX_VALUE) "no cap" else "cap $maxSize"
    println("Built index: ${references.size} references ($cap) -> ${output.length()} bytes at ${output.path}")
}
