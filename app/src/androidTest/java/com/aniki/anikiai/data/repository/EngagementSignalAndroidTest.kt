package com.aniki.anikiai.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aniki.anikiai.data.db.AnikiDatabase
import com.aniki.anikiai.data.db.EngagementEventType
import com.aniki.anikiai.data.db.FtsIndexer
import com.aniki.anikiai.data.db.ItemDao
import com.aniki.anikiai.data.db.ItemEntity
import com.aniki.anikiai.data.db.ItemStatus
import com.aniki.anikiai.data.db.ItemType
import com.aniki.anikiai.data.remote.SyncEngagementEventDto
import com.aniki.anikiai.data.remote.SyncItemDto
import com.aniki.anikiai.feed.EngagementRecord
import com.aniki.anikiai.feed.computeTagWeights
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val SEEDED_EVENT_COUNT = 20_000
private const val FLATNESS_BUDGET_MS = 50L

/**
 * Regression tests for the S1/S4/P2 engagement-events scalability pass: computeUserTagWeights
 * moved from scanning the full engagement_events history on every call to summing an
 * incrementally-maintained per-item engagementSignal column (see ItemDao.getTagSignalTotals,
 * ItemRepository.recordEvent, SyncRepository.mergeEngagementEvent). These tests prove the new
 * incremental path produces the same *effective* weights as the old full-scan path would have,
 * that pruning synced events doesn't shift those weights, and the two correctness hazards flagged
 * during design -- double-folding a re-delivered sync event, and a remote content overwrite
 * silently zeroing out locally-accumulated signal -- are both actually guarded against, against a
 * real in-memory SQLite/Room engine (a JVM unit test can't provide one).
 */
@RunWith(AndroidJUnit4::class)
class EngagementSignalAndroidTest {

    private lateinit var db: AnikiDatabase
    private lateinit var dao: ItemDao
    private lateinit var repository: ItemRepository
    private lateinit var syncRepository: SyncRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AnikiDatabase::class.java).build()
        dao = db.itemDao()
        val ftsIndexer = FtsIndexer(dao)
        repository = ItemRepository(dao, ftsIndexer, db)
        syncRepository = SyncRepository(dao, ftsIndexer)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertItem(id: String, createdAt: Long = 1000L): ItemEntity {
        val item = ItemEntity(
            id = id,
            type = ItemType.WEB_ARTICLE,
            sourceUrl = "https://example.com/$id",
            normalizedUrl = "https://example.com/$id",
            title = "Item $id",
            bodyText = null,
            summary = null,
            thumbnailUrl = null,
            category = null,
            eventDate = null,
            status = ItemStatus.ENRICHED,
            createdAt = createdAt,
            updatedAt = createdAt
        )
        dao.insertItem(item)
        return item
    }

    private suspend fun tagItem(itemId: String, vararg labels: String) {
        for (label in labels) repository.addUserTag(itemId, label)
    }

    /** Compares two weight maps by *effective* value (a missing key == neutral 0.0), matching how
     *  Ranking.kt's tagAffinity actually consumes this map (`weights[it] ?: 0.0`) -- the item-
     *  signal-aggregate path can legitimately include a tag as an explicit 0.0 entry (any active
     *  tag on an item with a net-zero or absent signal total) in cases the old per-event scan
     *  would have omitted the key entirely (a tag whose item only ever had zero-signal SHOWN
     *  events never entered the old scan's `raw` map at all). Both are "neutral" downstream, so
     *  this is the correctness bar that actually matters, not literal map equality. */
    private fun assertWeightsEquivalent(expected: Map<String, Double>, actual: Map<String, Double>) {
        for (key in expected.keys + actual.keys) {
            assertEquals(
                "effective weight for tag '$key' should match (missing key == neutral 0.0)",
                expected[key] ?: 0.0,
                actual[key] ?: 0.0,
                1e-9
            )
        }
    }

    // -----------------------------------------------------------------
    // S1/P2: the incremental aggregate must match a full from-scratch fold
    // -----------------------------------------------------------------

    @Test
    fun computeUserTagWeights_matchesFullEventScan_afterMixedEventAndTagChanges() = runBlocking {
        insertItem("i1")
        insertItem("i2")
        tagItem("i1", "kotlin", "android")
        tagItem("i2", "kotlin")

        repository.recordOpen("i1")           // OPENED  +1.0  on i1 -> {kotlin, android}
        repository.setStarred("i2", true)     // STARRED +2.0  on i2 -> {kotlin}
        repository.recordDwell("i1", 15_000)  // DWELL   +0.5  on i1 -> {kotlin, android}
        repository.recordDismiss("i2")        // DISMISSED -1.0 on i2 -> {kotlin}
        repository.markShown("i1")            // SHOWN    0.0  on i1 (impression only)

        // Re-tag i1 mid-stream: drop "android", add "news". Attribution happens at READ time
        // against an item's CURRENT tags (ItemDao.getTagSignalTotals joins live item_tags), not
        // at event time, exactly like the old per-event-scan implementation did (it re-fetched
        // getAllActiveItemTagLabels() once per call, not once per historical event either).
        val androidTagId = dao.getTagByLabel("android")!!.id
        repository.removeTag("i1", androidTagId)
        tagItem("i1", "news")

        repository.recordOpen("i1")           // OPENED +1.0 more on i1 -> now {kotlin, news}

        val actual = repository.computeUserTagWeights()

        // From-scratch fold via the still-tested pure event-list path (feed.computeTagWeights),
        // attributing every event to i1/i2's tags AS OF NOW -- i.e. what a full re-scan run at
        // this exact moment would produce.
        val i1TagsNow = listOf("kotlin", "news")
        val i2TagsNow = listOf("kotlin")
        val records = listOf(
            EngagementRecord(EngagementEventType.OPENED, null, i1TagsNow),
            EngagementRecord(EngagementEventType.STARRED, null, i2TagsNow),
            EngagementRecord(EngagementEventType.DWELL, 15_000.0, i1TagsNow),
            EngagementRecord(EngagementEventType.DISMISSED, null, i2TagsNow),
            EngagementRecord(EngagementEventType.SHOWN, null, i1TagsNow),
            EngagementRecord(EngagementEventType.OPENED, null, i1TagsNow)
        )
        val expected = computeTagWeights(records)

        assertWeightsEquivalent(expected, actual)
    }

    // -----------------------------------------------------------------
    // S1: pruning synced engagement_events rows must never shift tag weights
    // -----------------------------------------------------------------

    @Test
    fun pruneOldEngagementEvents_doesNotShiftTagWeights() = runBlocking {
        insertItem("i1")
        tagItem("i1", "kotlin")
        repository.recordOpen("i1")
        repository.recordDwell("i1", 30_000)

        // Simulate these events already having been pushed to the server -- pruneOldEngagementEvents
        // only ever touches dirty=0 rows (see its doc), matching the trash-purge precedent.
        dao.getDirtyEngagementEvents().forEach { dao.clearEngagementEventDirty(it.id) }
        val before = repository.computeUserTagWeights()

        // retentionMs = -1 -> cutoff = now - (-1) = now + 1ms, i.e. "prune everything already
        // recorded" without needing a real sleep to age the events past a positive window.
        val purged = repository.pruneOldEngagementEvents(retentionMs = -1)

        assertTrue("expected the synced events to actually be purged", purged > 0)
        val after = repository.computeUserTagWeights()

        assertWeightsEquivalent(before, after)
    }

    @Test
    fun pruneOldEngagementEvents_neverTouchesAnUnsyncedEvent() = runBlocking {
        insertItem("i1")
        tagItem("i1", "kotlin")
        repository.recordOpen("i1") // stays dirty=true -- never explicitly synced in this test

        val purged = repository.pruneOldEngagementEvents(retentionMs = -1)

        assertEquals("an unsynced (dirty=1) event must never be purged", 0, purged)
    }

    // -----------------------------------------------------------------
    // The rowId != -1 gate: a re-delivered sync event must not double-fold its signal
    // -----------------------------------------------------------------

    @Test
    fun mergeEngagementEvent_deliveredTwice_doesNotDoubleFoldSignal() = runBlocking {
        insertItem("i1")

        val dto = SyncEngagementEventDto(
            id = "evt-1",
            itemId = "i1",
            eventType = EngagementEventType.OPENED, // signal = TagWeightConfig().opened = 1.0
            value = null,
            createdAt = 1000L
        )

        syncRepository.mergeEngagementEvent(dto) // first delivery: real insert, signal folded in
        syncRepository.mergeEngagementEvent(dto) // re-delivered (e.g. a retried pull page)

        val item = dao.getItemById("i1")!!
        assertEquals(
            "a re-delivered duplicate event must not double-fold its signal",
            1.0,
            item.engagementSignal,
            1e-9
        )
    }

    // -----------------------------------------------------------------
    // mergeItem's whole-row REPLACE must preserve locally-accumulated engagementSignal
    // -----------------------------------------------------------------

    @Test
    fun mergeItem_preservesLocalEngagementSignal_acrossAnOverwrite() = runBlocking {
        val original = insertItem("i1", createdAt = 1000L)
        repository.recordOpen("i1") // folds +1.0 onto i1's engagementSignal locally

        val before = dao.getItemById("i1")!!
        assertEquals(1.0, before.engagementSignal, 1e-9)

        // A newer remote update to this same item's content (e.g. edited on another device) --
        // decideMerge picks OVERWRITE since the pulled updatedAt (2000) is newer than local's (1000).
        val pulled = SyncItemDto(
            id = "i1",
            type = original.type,
            sourceUrl = original.sourceUrl,
            normalizedUrl = original.normalizedUrl,
            title = "Edited Elsewhere",
            bodyText = original.bodyText,
            summary = original.summary,
            thumbnailUrl = original.thumbnailUrl,
            category = original.category,
            entities = null,
            eventDate = null,
            status = original.status,
            isStarred = original.isStarred,
            summaryLocked = false,
            tagsLocked = false,
            titleLocked = false,
            updatedAt = 2000L,
            deletedAt = null
        )
        syncRepository.mergeItem(pulled)

        val after = dao.getItemById("i1")!!
        assertEquals("Edited Elsewhere", after.title)
        assertEquals(
            "a remote content overwrite must not reset locally-accumulated engagement signal",
            1.0,
            after.engagementSignal,
            1e-9
        )
    }

    // -----------------------------------------------------------------
    // P2: computeUserTagWeights (what FeedViewModel.applyFreshSnapshot awaits during the refresh
    // pill's blur hold) must not slow down as engagement_events grows -- it no longer queries that
    // table at all (see ItemDao.getTagSignalTotals). Proven here by bulk-seeding a large,
    // deliberately *inert* engagement_events table (raw SQL inserts that bypass recordEvent, so
    // they never touch items.engagementSignal) and confirming the call stays just as fast.
    // -----------------------------------------------------------------

    @Test
    fun computeUserTagWeights_doesNotSlowDownAsEngagementEventsTableGrows() = runBlocking {
        insertItem("i1")
        tagItem("i1", "kotlin")
        repository.recordOpen("i1")

        val baselineMs = timeMillis { repository.computeUserTagWeights() }

        // Bulk-insert a large, inert engagement_events history via raw SQL (bypassing
        // recordEvent/mergeEngagementEvent's fold entirely) -- this simulates months of
        // accumulated history sitting in the table between prune cycles, without going through
        // one-by-one suspend Room inserts (slow) or affecting engagementSignal (irrelevant to what
        // this measures: whether reading weights still touches this table at all).
        val rawDb = db.openHelper.writableDatabase
        rawDb.beginTransaction()
        try {
            repeat(SEEDED_EVENT_COUNT) { i ->
                rawDb.execSQL(
                    "INSERT INTO engagement_events (id, itemId, eventType, value, createdAt, dirty) " +
                        "VALUES ('seed-$i', 'i1', 'OPENED', NULL, 1000, 0)"
                )
            }
            rawDb.setTransactionSuccessful()
        } finally {
            rawDb.endTransaction()
        }

        val afterSeedMs = timeMillis { repository.computeUserTagWeights() }

        // Both are near-instant, well under any budget that would matter for a UI blur hold; the
        // real assertion is that seeding $SEEDED_EVENT_COUNT extra event rows didn't move the
        // needle, proving the cost tracks active items/tags (unchanged: still just "i1"/"kotlin"),
        // not engagement_events row count.
        assertTrue(
            "computeUserTagWeights took ${afterSeedMs}ms after seeding $SEEDED_EVENT_COUNT inert " +
                "engagement_events rows (baseline was ${baselineMs}ms) -- expected it to stay " +
                "effectively flat since it no longer queries that table at all",
            afterSeedMs < baselineMs + FLATNESS_BUDGET_MS
        )
    }

    private suspend fun timeMillis(block: suspend () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun mergeItem_newItem_startsEngagementSignalAtZero() = runBlocking {
        val pulled = SyncItemDto(
            id = "brand-new",
            type = ItemType.NOTE,
            sourceUrl = null,
            normalizedUrl = null,
            title = "Synced from another device",
            bodyText = "body",
            summary = null,
            thumbnailUrl = null,
            category = null,
            entities = null,
            eventDate = null,
            status = ItemStatus.ENRICHED,
            isStarred = false,
            summaryLocked = false,
            tagsLocked = false,
            titleLocked = false,
            updatedAt = 1000L,
            deletedAt = null
        )
        syncRepository.mergeItem(pulled)

        val after = dao.getItemById("brand-new")!!
        assertEquals(0.0, after.engagementSignal, 1e-9)
    }
}
