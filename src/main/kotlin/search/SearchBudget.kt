package dev.santo.search

/**
 * Per-query cap on the number of distance evaluations a search may perform, and
 * the meter that counts them. An unlimited budget yields an exact search; a
 * finite one bounds the work (and thus latency) at the cost of some recall.
 *
 * One instance is shared across a query's whole traversal (every bucket and
 * subtree), so the cap is per query rather than per tree.
 */
class SearchBudget private constructor(private val limit: Int) {
    var used: Int = 0
        private set

    /** Records one distance evaluation. */
    fun consume() {
        used++
    }

    /** True once the cap is reached; the traversal must stop descending. */
    fun exhausted(): Boolean = used >= limit

    companion object {
        fun of(limit: Int): SearchBudget = SearchBudget(limit)

        fun unlimited(): SearchBudget = SearchBudget(Int.MAX_VALUE)
    }
}
