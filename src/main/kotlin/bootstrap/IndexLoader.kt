package dev.santo.bootstrap

import dev.santo.search.DEFAULT_NPROBE
import dev.santo.search.IndexState
import dev.santo.search.IvfReader
import java.io.File

/**
 * Loads the prebuilt IVF index artifact off the request path, on a daemon thread, so
 * server startup is not blocked. Readiness flips to ready only once the artifact is
 * published. A missing or unreadable artifact leaves the instance not-ready (the load
 * balancer keeps routing elsewhere) instead of crashing.
 *
 * `NPROBE` (env) overrides how many IVF cells each query probes — the recall/CPU
 * lever, tunable on the contest hardware without rebuilding the native image.
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
        val nprobe = System.getenv("NPROBE")?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_NPROBE
        val file = File(path)
        if (!file.exists()) {
            System.err.println("Index artifact not found at $path - instance stays not-ready")
            return
        }
        try {
            val startedAt = System.nanoTime()
            val index = file.inputStream().buffered().use { IvfReader.readFrom(it, nprobe) }
            state.publish(index)
            println("IVF index loaded from $path in ${(System.nanoTime() - startedAt) / 1_000_000} ms (nprobe=$nprobe)")
        } catch (error: Exception) {
            System.err.println("Failed to load index artifact at $path: ${error.message}")
        }
    }
}
