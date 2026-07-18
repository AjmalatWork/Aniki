package com.aniki.anikiai.ui.feed

import com.aniki.anikiai.data.db.ItemEntity
import com.aniki.anikiai.data.db.ItemStatus
import com.aniki.anikiai.data.db.ItemType
import com.aniki.anikiai.data.db.ItemWithTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 (maintainability audit): FeedSessionState used to be anonymous `private var`s in
 * FeedViewModel's companion object -- untestable in isolation, since there was no way to construct
 * a fresh instance or reset it between tests without reflection. Extracted to a plain class, this
 * is the test that extraction was for: a fresh FeedSessionState() per test, defaults verified
 * directly, no static state to leak between test runs.
 */
class FeedSessionStateTest {

    private fun item(id: String, status: String = ItemStatus.ENRICHED, isStarred: Boolean = false) = ItemWithTags(
        item = ItemEntity(
            id = id,
            type = ItemType.NOTE,
            sourceUrl = null,
            normalizedUrl = null,
            title = "t",
            bodyText = "b",
            summary = null,
            thumbnailUrl = null,
            category = null,
            eventDate = null,
            status = status,
            isStarred = isStarred,
            createdAt = 1000L,
            updatedAt = 1000L
        ),
        tags = emptyList()
    )

    @Test
    fun aFreshInstance_startsWithEveryFieldAtItsColdStartDefault() {
        val state = FeedSessionState()

        assertFalse(state.prefsLoaded)
        assertFalse(state.isFirstSessionEver)
        assertNull(state.orderIds)
        assertNull(state.lastSettledItemId)
        assertNull(state.fingerprint)
    }

    @Test
    fun twoIndependentInstances_doNotShareState() {
        // The exact thing companion-object statics couldn't do: two independently constructed
        // instances (e.g. one per test, or a real second FeedViewModel if that were ever needed)
        // must not see each other's mutations.
        val a = FeedSessionState()
        val b = FeedSessionState()

        a.orderIds = listOf("item-1", "item-2")
        a.lastSettledItemId = "item-1"
        a.prefsLoaded = true

        assertEquals(listOf("item-1", "item-2"), a.orderIds)
        assertNull(b.orderIds)
        assertNull(b.lastSettledItemId)
        assertFalse(b.prefsLoaded)
    }

    @Test
    fun fingerprintOf_includesOnlyEnrichedItems_asIdColonStarredPairs() {
        val items = listOf(
            item("i1", status = ItemStatus.ENRICHED, isStarred = true),
            item("i2", status = ItemStatus.ENRICHED, isStarred = false),
            item("i3", status = ItemStatus.PENDING, isStarred = true) // excluded: not ENRICHED
        )

        val fingerprint = FeedSessionState.fingerprintOf(items)

        assertEquals(setOf("i1:true", "i2:false"), fingerprint)
    }

    @Test
    fun fingerprintOf_changesWhenAnEnrichedItemsStarFlagChanges() {
        val before = FeedSessionState.fingerprintOf(listOf(item("i1", isStarred = false)))
        val after = FeedSessionState.fingerprintOf(listOf(item("i1", isStarred = true)))

        assertTrue(before != after)
    }

    @Test
    fun fingerprintOf_ofAnEmptyLibrary_isAnEmptySet() {
        assertEquals(emptySet<String>(), FeedSessionState.fingerprintOf(emptyList()))
    }
}
