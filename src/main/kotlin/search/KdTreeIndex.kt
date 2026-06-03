package dev.santo.search

import dev.santo.fraud.K_NEIGHBORS

/**
 * Approximate k-NN via a balanced KD-tree with BEST-FIRST branch-and-bound (arthurd3's BBF,
 * rank ~33) and a visit budget. A min-heap of subtrees keyed by their slab lower bound drives
 * exploration — the most promising subtree is always expanded next, so a modest budget reaches
 * the true 5-NN with far fewer node visits than a depth-first scan (offline over 3M: E=0 at a
 * ~10k-visit budget, FLAT — no tail). Unlike the IVF there is NO routing phase: each visit is a
 * single distance with early-exit. That's how it gets low p99 while keeping ~zero error.
 *
 * Memory-lean flat layout (fits the 160 MB budget): nodes are stored in pre-order, so a node's
 * LEFT child is always `node+1` (implicit). [packed] holds, per node, the split dimension, a
 * has-left flag, and the RIGHT child id — one int each. Vectors are int16, node-major.
 */
class KdTreeIndex internal constructor(
    internal val nodeVec: ShortArray,    // n*dim int16, node-major
    internal val nodeLabel: BooleanArray,
    internal val packed: IntArray,       // per node: splitDim<<23 | hasLeft<<22 | (rightId+1)
    internal val root: Int,
    internal val dim: Int,
    internal val n: Int,
) : VectorIndex {

    /** Visit budget (env `KD_VISIT_BUDGET`, 0/unset = exact). The near-best subtree order means
     *  a budget of ~10k reaches the true 5-NN at E≈0 while capping the high-dim tail → low p99. */
    @Volatile
    var visitBudget = System.getenv("KD_VISIT_BUDGET")?.toIntOrNull()?.takeIf { it > 0 } ?: Int.MAX_VALUE

    private val scratch = ThreadLocal.withInitial { Scratch(dim) }

    /** Visited-node count of the last search on this thread (the p99 proxy, for offline probes). */
    val lastVisits = ThreadLocal.withInitial { IntArray(1) }

    override fun nearestFraudCount(query: DoubleArray): Int {
        val s = scratch.get()
        val q = s.codes
        for (d in 0 until dim) q[d] = quantizeToLogicalCode(query[d])
        val knn = s.knn.also { it.reset() }
        s.visits = 0
        val budget = visitBudget
        val heap = s.heap
        heap.clear()
        heap.add(pack(0L, root))
        while (heap.size > 0 && s.visits < budget) {
            val top = heap.poll()
            val bound = top ushr NODE_BITS
            if (bound >= knn.worst()) break       // best remaining subtree can't beat the 5th-NN
            var node = (top and NODE_MASK).toInt()
            while (node >= 0 && s.visits < budget) {
                s.visits++
                val base = node * dim
                var sum = 0L
                val worst = knn.worst()
                var d = 0
                while (d < dim) {
                    val diff = (q[d] - nodeVec[base + d].toInt()).toLong()
                    sum += diff * diff
                    if (sum >= worst) break
                    d++
                }
                if (d == dim) knn.offer(sum.toDouble(), nodeLabel[node])

                val pk = packed[node]
                val sd = pk ushr 23
                val delta = (q[sd] - nodeVec[base + sd].toInt()).toLong()
                val hasLeft = (pk ushr 22) and 1
                val rightId = (pk and 0x3FFFFF) - 1
                val left = if (hasLeft == 1) node + 1 else -1
                val near: Int; val far: Int
                if (delta < 0) { near = left; far = rightId } else { near = rightId; far = left }
                val farBound = bound + delta * delta
                if (far >= 0 && farBound < knn.worst()) heap.add(pack(farBound, far))
                node = near
            }
        }
        lastVisits.get()[0] = s.visits
        return knn.fraudCount()
    }

    private class Scratch(dim: Int) {
        val codes = IntArray(dim)
        val knn = KNearest(K_NEIGHBORS)
        var visits = 0
        val heap = LongMinHeap()   // packed (slabBound<<NODE_BITS | node); primitive = zero-alloc hot path
    }

    /**
     * Array-backed primitive min-heap of packed longs — no boxing (a `PriorityQueue<Long>`
     * would allocate per push, churning the young gen on the hot path and inflating the p99
     * tail). Packed values are positive (bound in the high bits), so natural long order = by
     * bound. Pooled per thread and [clear]ed per query.
     */
    private class LongMinHeap {
        private var a = LongArray(64)
        var size = 0; private set
        fun clear() { size = 0 }
        fun add(v: Long) {
            if (size == a.size) a = a.copyOf(a.size * 2)
            var i = size++
            a[i] = v
            while (i > 0) { val p = (i - 1) ushr 1; if (a[p] <= a[i]) break; val t = a[p]; a[p] = a[i]; a[i] = t; i = p }
        }
        fun poll(): Long {
            val top = a[0]
            val last = a[--size]
            if (size > 0) {
                a[0] = last
                var i = 0
                while (true) {
                    val l = 2 * i + 1; val r = l + 1; var m = i
                    if (l < size && a[l] < a[m]) m = l
                    if (r < size && a[r] < a[m]) m = r
                    if (m == i) break
                    val t = a[m]; a[m] = a[i]; a[i] = t; i = m
                }
            }
            return top
        }
    }

    private companion object {
        const val NODE_BITS = 23
        const val NODE_MASK = (1L shl NODE_BITS) - 1
        fun pack(bound: Long, node: Int): Long = (bound shl NODE_BITS) or node.toLong()
    }
}
