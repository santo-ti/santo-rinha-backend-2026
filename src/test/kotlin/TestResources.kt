package dev.santo

import dev.santo.bootstrap.AppComponents
import dev.santo.search.VectorIndex

/** Reads a classpath resource from `src/test/resources` as text. */
fun readTestResource(path: String): String =
    object {}.javaClass.getResourceAsStream(path)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("Test resource not found on classpath: $path")

/** Builds wired application components, optionally with an already-ready index. */
fun testComponents(index: VectorIndex? = null): AppComponents {
    val components = AppComponents.create()
    if (index != null) components.indexState.publish(index)
    return components
}

/** Test index reporting a fixed number of fraud neighbors, to drive the decision rule. */
class FixedFraudIndex(private val fraudNeighbors: Int) : VectorIndex {
    override fun nearestFraudCount(query: DoubleArray): Int = fraudNeighbors
}

/** Test index that always throws, to exercise the never-5xx fallback path. */
object ThrowingIndex : VectorIndex {
    override fun nearestFraudCount(query: DoubleArray): Int = throw IllegalStateException("boom")
}
