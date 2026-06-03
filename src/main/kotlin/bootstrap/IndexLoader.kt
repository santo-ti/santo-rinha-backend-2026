package dev.santo.bootstrap

import dev.santo.search.DEFAULT_NPROBE1
import dev.santo.search.DEFAULT_NPROBE2
import dev.santo.search.IndexState
import dev.santo.search.IvfReader
import dev.santo.search.KDTREE_MAGIC
import dev.santo.search.KdTreeReader
import java.io.DataInputStream
import java.io.File

/**
 * Loads the prebuilt IVF index artifact off the request path, on a daemon thread, so
 * server startup is not blocked. Readiness flips to ready only once the artifact is
 * published. A missing or unreadable artifact leaves the instance not-ready (the load
 * balancer keeps routing elsewhere) instead of crashing.
 *
 * `NPROBE1`/`NPROBE2` (env) override how many districts / cells each query probes —
 * the recall/CPU levers, tunable on the contest hardware without rebuilding.
 */
object IndexLoader {
    private const val DEFAULT_PATH = "/app/index.bin"

    fun loadAsync(state: IndexState) {
        Thread({ load(state) }, "index-loader").apply {
            isDaemon = true
            start()
        }
    }

    private fun load(state: IndexState) {
        val path = System.getenv("INDEX_PATH") ?: DEFAULT_PATH
        val np1 = System.getenv("NPROBE1")?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_NPROBE1
        val np2 = System.getenv("NPROBE2")?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_NPROBE2
        val file = File(path)
        if (!file.exists()) {
            System.err.println("Index artifact not found at $path - instance stays not-ready")
            return
        }
        try {
            val startedAt = System.nanoTime()
            val index = file.inputStream().buffered().use { stream ->
                stream.mark(4)
                val magic = DataInputStream(stream).readInt()
                stream.reset()
                if (magic == KDTREE_MAGIC) KdTreeReader.readFrom(stream)
                else IvfReader.readFrom(stream, np1, np2)
            }
            state.publish(index)
            println("Index loaded from $path in ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        } catch (error: Exception) {
            System.err.println("Failed to load index artifact at $path: ${error.message}")
        }
    }
}
