package dev.santo.tools

import dev.santo.search.quantizeVector
import dev.santo.vectorization.VECTOR_DIMENSIONS
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Offline build step: `references.json[.gz]` -> KD-tree artifact. Runs during the image
 * build so the runtime only loads the artifact. The KD-tree build is single-threaded and
 * fast (~2 s for 3M), so no CPU cap is needed. The runtime visit budget is set separately
 * via the `KD_VISIT_BUDGET` env (a pure runtime lever, tunable without a rebuild).
 *
 * Usage: java -Xmx6g -cp app.jar dev.santo.tools.BuildKdTreeKt refs.json.gz kdtree.bin [maxSize]
 */
fun main(args: Array<String>) {
    require(args.size in 2..3) { "Usage: BuildKdTree <input.json|.json.gz> <output.bin> [maxSize]" }
    val input = File(args[0])
    val output = File(args[1])
    val maxSize = args.getOrNull(2)?.toIntOrNull() ?: Int.MAX_VALUE
    val dim = VECTOR_DIMENSIONS

    val references = input.inputStream().buffered().use { raw ->
        References.parse(if (input.name.endsWith(".gz")) GZIPInputStream(raw) else raw)
    }
    val refs = if (references.size > maxSize) references.subList(0, maxSize) else references
    val n = refs.size

    val vectors = ShortArray(n * dim)
    val labels = BooleanArray(n)
    for (i in 0 until n) {
        System.arraycopy(quantizeVector(refs[i].vector), 0, vectors, i * dim, dim)
        labels[i] = refs[i].isFraud
    }

    val t0 = System.nanoTime()
    val index = KdTreeBuilder.build(vectors, labels, n, dim)
    output.outputStream().buffered().use { KdTreeWriter.writeTo(index, it) }
    println("Built KD-tree: $n references in ${(System.nanoTime() - t0) / 1_000_000} ms -> ${output.length()} bytes at ${output.path}")
}
