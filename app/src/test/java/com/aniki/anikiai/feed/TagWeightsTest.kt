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
}
