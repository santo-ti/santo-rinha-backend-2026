package dev.santo.index

/**
 * Holds the search index once it finishes loading. Readiness (`GET /ready`) is
 * gated on [isReady], so the load balancer never routes `POST /fraud-score` to
 * an instance whose index is not yet queryable.
 */
class IndexState {
    @Volatile
    private var index: VectorIndex? = null

    val isReady: Boolean get() = index != null

    fun current(): VectorIndex? = index

    fun publish(index: VectorIndex) {
        this.index = index
    }
}
