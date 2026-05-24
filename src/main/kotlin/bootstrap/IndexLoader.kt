package dev.santo.bootstrap

import dev.santo.search.IndexReader
import dev.santo.search.IndexState
import java.io.File

/**
 * Loads the prebuilt index artifact off the request path, on a daemon thread, so
 * server startup is not blocked. Readiness flips to ready only once the artifact
 * is published. A missing or unreadable artifact leaves the instance not-ready
 * (the load balancer keeps routing elsewhere) instead of crashing.
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
        val file = File(path)
        if (!file.exists()) {
            System.err.println("Index artifact not found at $path - instance stays not-ready")
            return
        }
        try {
            val startedAt = System.nanoTime()
            val index = file.inputStream().buffered().use { IndexReader.readFrom(it) }
            state.publish(index)
            println("Index loaded from $path in ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        } catch (error: Exception) {
            System.err.println("Failed to load index artifact at $path: ${error.message}")
        }
    }
}
