package com.aniki.anikiai.feed

import com.aniki.anikiai.data.db.EngagementEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun rec(type: String, tags: List<String>, value: Double? = null) =
    EngagementRecord(type, value, tags)

class TagWeightsTest {

    @Test
    fun `empty history yields empty weights (cold start)`() {
        assertEquals(emptyMap<String, Double>(), computeTagWeights(emptyList()))
    }

    @Test
    fun `a single opened event weights its tag at the max`() {
        val weights = computeTagWeights(listOf(rec(EngagementEventType.OPENED, listOf("kotlin"))))
        assertEquals(1.0, weights["kotlin"]!!, 1e-9)
    }

    @Test
    fun `dwell scales with duration and normalizes against a stronger signal`() {
        val records = listOf(
            rec(EngagementEventType.OPENED, listOf("android")),                    // +1.0
            rec(EngagementEventType.DWELL, listOf("kotlin"), value = 15_000.0)      // +0.5 (half of full-dwell)
        )
        val weights = computeTagWeights(records)
        assertEquals(1.0, weights["android"]!!, 1e-9)
        assertEquals(0.5, weights["kotlin"]!!, 1e-9)
    }

    @Test
    fun `dismissed tags go negative while engaged tags stay positive`() {
        val records = listOf(
            rec(EngagementEventType.OPENED, listOf("liked")),        // +1.0
            rec(EngagementEventType.DISMISSED, listOf("disliked"))   // -1.0
        )
        val weights = computeTagWeights(records)
        assertEquals(1.0, weights["liked"]!!, 1e-9)
        assertEquals(-1.0, weights["disliked"]!!, 1e-9)
        assertTrue(weights["disliked"]!! < 0.0)
    }

    @Test
    fun `weights normalize against the strongest tag`() {
        val records = listOf(
            rec(EngagementEventType.OPENED, listOf("a")),
            rec(EngagementEventType.OPENED, listOf("a")), // a = +2.0
            rec(EngagementEventType.OPENED, listOf("b"))  // b = +1.0
        )
        val weights = computeTagWeights(records)
        assertEquals(1.0, weights["a"]!!, 1e-9)
        assertEquals(0.5, weights["b"]!!, 1e-9)
    }

    @Test
    fun `shown-only events produce no weights`() {
        val weights = computeTagWeights(listOf(rec(EngagementEventType.SHOWN, listOf("x"))))
        assertEquals(emptyMap<String, Double>(), weights)
    }

    // -----------------------------------------------------------------
    // signalForEvent / normalizeTagWeights: extracted so ItemRepository can fold each event's
    // signal into an item's running engagementSignal total incrementally (S1/P2 scalability pass)
    // instead of recomputing from a full event scan. computeTagWeights itself is built on top of
    // both and is unchanged above -- these just cover the extracted pieces directly.
    // -----------------------------------------------------------------

    @Test
    fun `signalForEvent matches TagWeightConfig defaults per event type`() {
        val c = TagWeightConfig()
        assertEquals(c.opened, signalForEvent(EngagementEventType.OPENED, null), 1e-9)
        assertEquals(c.starred, signalForEvent(EngagementEventType.STARRED, null), 1e-9)
        assertEquals(c.dismissed, signalForEvent(EngagementEventType.DISMISSED, null), 1e-9)
        assertEquals(c.swipedFast, signalForEvent(EngagementEventType.SWIPED_FAST, null), 1e-9)
        assertEquals(0.0, signalForEvent(EngagementEventType.SHOWN, null), 1e-9)
    }

    @Test
    fun `signalForEvent clamps DWELL at the full-dwell ceiling`() {
        val c = TagWeightConfig()
        assertEquals(c.dwellMax, signalForEvent(EngagementEventType.DWELL, c.dwellFullMs * 3), 1e-9)
        assertEquals(c.dwellMax / 2, signalForEvent(EngagementEventType.DWELL, c.dwellFullMs / 2), 1e-9)
    }

    @Test
    fun `normalizeTagWeights matches computeTagWeights' own normalization for the same raw totals`() {
        val raw = mapOf("a" to 2.0, "b" to 1.0, "c" to -1.0)
        val normalized = normalizeTagWeights(raw)
        assertEquals(1.0, normalized["a"]!!, 1e-9)
        assertEquals(0.5, normalized["b"]!!, 1e-9)
        assertEquals(-0.5, normalized["c"]!!, 1e-9)
    }

    @Test
    fun `normalizeTagWeights of an empty map is empty (cold start)`() {
        assertEquals(emptyMap<String, Double>(), normalizeTagWeights(emptyMap()))
    }
}
