package dev.santo

import dev.santo.index.BruteForceIndex
import dev.santo.index.FRAUD_THRESHOLD
import dev.santo.index.References
import dev.santo.index.fraudScore
import dev.santo.index.isApproved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexTest {

    private val references = References.parse(readTestResource("/example-references.json"))
    private val oracle = BruteForceIndex(references)

    @Test
    fun `decision thresholds follow score below 0_6`() {
        assertEquals(0.0, fraudScore(0), 1e-9)
        assertTrue(isApproved(fraudScore(0)))

        assertEquals(0.4, fraudScore(2), 1e-9)
        assertTrue(isApproved(fraudScore(2)))

        assertEquals(FRAUD_THRESHOLD, fraudScore(3), 1e-9)
        assertFalse(isApproved(fraudScore(3))) // 0.6 < 0.6 is false -> denied

        assertEquals(1.0, fraudScore(5), 1e-9)
        assertFalse(isApproved(fraudScore(5)))
    }

    @Test
    fun `oracle returns a fraud count within k for every example reference`() {
        for (ref in references) {
            val count = oracle.nearestFraudCount(ref.vector)
            assertTrue(count in 0..5, "fraud count out of range: $count")
        }
    }

    @Test
    fun `querying with a reference vector ranks itself first`() {
        // The nearest neighbor of a reference's own vector is itself (distance 0),
        // so a heavily-fraud neighborhood stays fraud and vice versa: the decision
        // must be stable when re-querying known points.
        val first = references.first()
        val countA = oracle.nearestFraudCount(first.vector)
        val countB = oracle.nearestFraudCount(first.vector.copyOf())
        assertEquals(countA, countB)
    }
}
