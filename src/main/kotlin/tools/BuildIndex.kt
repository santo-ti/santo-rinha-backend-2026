package dev.santo.tools

import dev.santo.search.LabeledVector
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/**
 * Offline build step: `references.json[.gz]` -> compact binary IVF artifact. Runs
 * during the image build so the runtime only loads the artifact (no JSON parse, no
 * k-means at startup). Give the JVM enough heap for ~3M vectors, e.g.
 * `java -Xmx6g -cp app.jar dev.santo.tools.BuildIndexKt refs.json.gz index.bin [maxSize]`.
 *
 * The cell count `k` and k-means `iterations` are env-overridable (`IVF_K`,
 * `IVF_ITERS`) so the index granularity can be tuned without code changes; `nprobe`
 * is NOT baked here — it is a pure runtime lever (`NPROBE` env, see bootstrap).
 * Optional [maxSize] uniformly samples the references first (experiments only — the
 * full 3M is the detection lever).
 */
fun main(args: Array<String>) {
    require(args.size in 2..3) { "Usage: BuildIndex <input.json|.json.gz> <output.bin> [maxSize]" }
    val input = File(args[0])
    val output = File(args[1])
    val maxSize = args.getOrNull(2)?.toIntOrNull() ?: Int.MAX_VALUE
    val k = System.getenv("IVF_K")?.toIntOrNull() ?: IvfBuilder.DEFAULT_CENTROIDS
    val iterations = System.getenv("IVF_ITERS")?.toIntOrNull() ?: 18

    val references = input.inputStream().buffered().use { raw ->
        val stream = if (input.name.endsWith(".gz")) GZIPInputStream(raw) else raw
        References.parse(stream)
    }
    val refs = if (references.size > maxSize) sample(references, maxSize) else references

    val index = IvfBuilder.build(refs, k = k, iterations = iterations)
    output.outputStream().buffered().use { IvfWriter.writeTo(index, it) }
    val cap = if (maxSize == Int.MAX_VALUE) "no cap" else "cap $maxSize"
    println("Built IVF index: ${refs.size} references ($cap), k=$k iters=$iterations -> ${output.length()} bytes at ${output.path}")
}

/** Uniform random sample without replacement, deterministic seed. */
private fun sample(references: List<LabeledVector>, maxSize: Int): List<LabeledVector> {
    val rng = Random(1L)
    val picked = HashSet<Int>(maxSize)
    while (picked.size < maxSize) picked.add(rng.nextInt(references.size))
    return picked.map { references[it] }
}
