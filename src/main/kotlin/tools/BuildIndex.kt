package dev.santo.tools

import dev.santo.search.DEFAULT_META_CELLS
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
 * The cell count `k`, k-means `iterations`, and district count `metaCells` are
 * env-overridable (`IVF_K`, `IVF_ITERS`, `IVF_META_CELLS`) so the index granularity
 * can be tuned without code changes; `nprobe` is NOT baked here — it is a pure
 * runtime lever (`NPROBE1`/`NPROBE2` env, see bootstrap). The district count IS baked
 * (it shapes the metaCentroids), so k1=128 (the E=0 routing) needs a rebuild here.
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
    val metaCells = System.getenv("IVF_META_CELLS")?.toIntOrNull() ?: DEFAULT_META_CELLS
    val maxCellSize = System.getenv("IVF_MAX_CELL")?.toIntOrNull() ?: IvfBuilder.DEFAULT_MAX_CELL_SIZE

    val references = input.inputStream().buffered().use { raw ->
        val stream = if (input.name.endsWith(".gz")) GZIPInputStream(raw) else raw
        References.parse(stream)
    }
    val refs = if (references.size > maxSize) sample(references, maxSize) else references

    // IVF_PARALLELISM caps the k-means cores for LOCAL builds (so it doesn't freeze the
    // machine); unset on CI => the common pool uses every core for the fastest image build.
    val parallelism = System.getenv("IVF_PARALLELISM")?.toIntOrNull()
    val index = if (parallelism != null) {
        val pool = java.util.concurrent.ForkJoinPool(parallelism)
        pool.submit<dev.santo.search.IvfIndex> {
            IvfBuilder.build(refs, k = k, iterations = iterations, metaCells = metaCells, maxCellSize = maxCellSize)
        }.get()
    } else {
        IvfBuilder.build(refs, k = k, iterations = iterations, metaCells = metaCells, maxCellSize = maxCellSize)
    }
    output.outputStream().buffered().use { IvfWriter.writeTo(index, it) }
    val cap = if (maxSize == Int.MAX_VALUE) "no cap" else "cap $maxSize"
    println("Built IVF index: ${refs.size} references ($cap), k=$k iters=$iterations k1=$metaCells maxCell=$maxCellSize -> ${output.length()} bytes at ${output.path}")
}

/** Uniform random sample without replacement, deterministic seed. */
private fun sample(references: List<LabeledVector>, maxSize: Int): List<LabeledVector> {
    val rng = Random(1L)
    val picked = HashSet<Int>(maxSize)
    while (picked.size < maxSize) picked.add(rng.nextInt(references.size))
    return picked.map { references[it] }
}
