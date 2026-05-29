package dev.santo.tools

import dev.santo.search.IndexReader
import java.io.File

/**
 * Offline memory probe: loads a prebuilt index artifact and reports the heap it
 * occupies once settled, so we can check whether a given index size fits the
 * native runtime's slice of the 160 MiB/instance budget. Run with the production
 * heap cap to simulate the native image, e.g. `java -Xmx140m ... index-3M.bin`.
 */
fun main(args: Array<String>) {
    val path = args.getOrElse(0) { "build/measure/index.bin" }
    val rt = Runtime.getRuntime()
    repeat(3) { System.gc(); Thread.sleep(100) }
    val before = rt.totalMemory() - rt.freeMemory()

    val t0 = System.nanoTime()
    val index = File(path).inputStream().buffered().use { IndexReader.readFrom(it, 2048) }
    val loadMs = (System.nanoTime() - t0) / 1e6

    repeat(4) { System.gc(); Thread.sleep(150) }
    val after = rt.totalMemory() - rt.freeMemory()
    val mb = 1048576.0
    println("artifact:        ${File(path).length() / mb} MB on disk")
    println("index footprint: ${(after - before) / mb} MB heap (loaded in ${loadMs}ms)")
    println("heap used:       ${after / mb} MB  (committed=${rt.totalMemory() / mb} MB, max=${rt.maxMemory() / mb} MB)")
    // Touch the index so the JIT/GC cannot drop it before the measurement prints.
    println("loaded ok:       ${index.javaClass.simpleName}")
}
