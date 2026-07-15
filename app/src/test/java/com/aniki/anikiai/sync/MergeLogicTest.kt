package com.aniki.anikiai.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeLogicTest {

    @Test
    fun `no local row always inserts regardless of timestamps`() {
        assertEquals(
            MergeDecision.INSERT,
            decideMerge(localExists = false, localUpdatedAt = 500, pulledUpdatedAt = 100, contentIdentical = false)
        )
    }

    @Test
    fun `pulled strictly newer overwrites local`() {
        assertEquals(
            MergeDecision.OVERWRITE,
            decideMerge(localExists = true, localUpdatedAt = 100, pulledUpdatedAt = 200, contentIdentical = false)
        )
    }

    @Test
    fun `pulled strictly older keeps local, local stays dirty`() {
        assertEquals(
            MergeDecision.KEEP_LOCAL,
            decideMerge(localExists = true, localUpdatedAt = 200, pulledUpdatedAt = 100, contentIdentical = false)
        )
    }

    @Test
    fun `tie with identical content is a true no-op`() {
        assertEquals(
            MergeDecision.NO_OP,
            decideMerge(localExists = true, localUpdatedAt = 150, pulledUpdatedAt = 150, contentIdentical = true)
        )
    }

    @Test
    fun `tie with different content overwrites — pulled wins ties per the spec`() {
        assertEquals(
            MergeDecision.OVERWRITE,
            decideMerge(localExists = true, localUpdatedAt = 150, pulledUpdatedAt = 150, contentIdentical = false)
        )
    }

    @Test
    fun `repeated merge of an already-synced row is idempotent — no-op every time`() {
        // Simulates running sync twice in a row with no intervening local or remote changes.
        val first = decideMerge(localExists = true, localUpdatedAt = 150, pulledUpdatedAt = 150, contentIdentical = true)
        val second = decideMerge(localExists = true, localUpdatedAt = 150, pulledUpdatedAt = 150, contentIdentical = true)
        assertEquals(MergeDecision.NO_OP, first)
        assertEquals(MergeDecision.NO_OP, second)
    }

    @Test
    fun `self-echo of a just-pushed row is a no-op, not a duplicate overwrite`() {
        // A device pulls back the exact row it just pushed (before its own cursor would have
        // skipped it, e.g. a slightly stale cursor) — content and updatedAt match exactly.
        assertEquals(
            MergeDecision.NO_OP,
            decideMerge(localExists = true, localUpdatedAt = 9999, pulledUpdatedAt = 9999, contentIdentical = true)
        )
    }

    @Test
    fun `contentIdentical is ignored when pulled is strictly newer — still overwrites`() {
        // Guards against a caller accidentally passing contentIdentical=true for rows that
        // actually differ in updatedAt; OVERWRITE must still win so the newer timestamp lands.
        assertEquals(
            MergeDecision.OVERWRITE,
            decideMerge(localExists = true, localUpdatedAt = 100, pulledUpdatedAt = 200, contentIdentical = true)
        )
    }
}
