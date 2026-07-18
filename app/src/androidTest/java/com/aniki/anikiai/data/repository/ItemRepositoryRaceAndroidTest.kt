package com.aniki.anikiai.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aniki.anikiai.data.db.AnikiDatabase
import com.aniki.anikiai.data.db.FtsIndexer
import com.aniki.anikiai.data.db.ItemDao
import com.aniki.anikiai.data.db.ItemEntity
import com.aniki.anikiai.data.db.ItemStatus
import com.aniki.anikiai.data.db.ItemType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the R1/R2/R3/R4 data-integrity audit findings: ItemRepository used to do
 * getItemById() -> entity.copy(...) -> updateItem(wholeRow) for nearly every mutation, so a
 * concurrent operation landing in the read-write gap had its own change silently clobbered by the
 * other write's stale copy of that field -- most seriously, applyEnrichment (re-)writing a whole
 * row could revert a star/delete, or overwrite titleEditedByUser back to false, defeating the
 * title edit lock. Fixed by switching to targeted single-purpose column writes (see ItemDao's
 * "Targeted single-purpose column writes" section) that never mention a column they don't
 * legitimately own. These tests exercise the exact scenarios the audit described (as deterministic
 * orderings rather than timing-dependent thread races -- the bug was deterministic given the right
 * order, not a rare interleaving, so a reliable non-flaky test doesn't need real concurrency to
 * prove the fix), against a real in-memory SQLite/Room engine (a JVM unit test can't provide one).
 */
@RunWith(AndroidJUnit4::class)
class ItemRepositoryRaceAndroidTest {

    private lateinit var db: AnikiDatabase
    private lateinit var dao: ItemDao
    private lateinit var repository: ItemRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AnikiDatabase::class.java).build()
        dao = db.itemDao()
        repository = ItemRepository(dao, FtsIndexer(dao), db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertItem(
        id: String = "i1",
        isStarred: Boolean = false,
        titleEditedByUser: Boolean = false,
        summaryEditedByUser: Boolean = false,
        tagsEditedByUser: Boolean = false,
        deletedAt: Long? = null,
        dirty: Boolean = false,
        status: String = ItemStatus.PENDING,
        title: String = "Original Title"
    ): ItemEntity {
        val item = ItemEntity(
            id = id,
            type = ItemType.WEB_ARTICLE,
            sourceUrl = "https://example.com",
            normalizedUrl = "https://example.com",
            title = title,
            bodyText = null,
            summary = null,
            thumbnailUrl = null,
            category = null,
            eventDate = null,
            status = status,
            isStarred = isStarred,
            summaryEditedByUser = summaryEditedByUser,
            tagsEditedByUser = tagsEditedByUser,
            titleEditedByUser = titleEditedByUser,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = deletedAt,
            dirty = dirty
        )
        dao.insertItem(item)
        return item
    }

    // -----------------------------------------------------------------
    // R2: applyEnrichment must never clobber fields it doesn't own
    // -----------------------------------------------------------------

    @Test
    fun applyEnrichment_doesNotRevertAStarThatWasSetBeforeEnrichmentRan() = runBlocking {
        insertItem(isStarred = false)
        repository.setStarred("i1", true) // user stars the item

        repository.applyEnrichment(
            itemId = "i1", title = "New Title", summary = "New summary", category = "tech",
            thumbnailUrl = null, entitiesJson = null, eventDate = null, tags = listOf("a")
        )

        val after = dao.getItemById("i1")!!
        assertTrue("star set before enrichment must survive enrichment", after.isStarred)
    }

    @Test
    fun applyEnrichment_doesNotUndoADeleteThatHappenedBeforeEnrichmentRan() = runBlocking {
        insertItem()
        repository.deleteItem("i1") // user trashes the item

        repository.applyEnrichment(
            itemId = "i1", title = "New Title", summary = "New summary", category = "tech",
            thumbnailUrl = null, entitiesJson = null, eventDate = null, tags = listOf("a")
        )

        val after = dao.getItemById("i1")!!
        assertNotNull("a delete before enrichment must survive enrichment", after.deletedAt)
    }

    @Test
    fun applyEnrichment_doesNotOverwriteATitleEditThatHappenedBeforeEnrichmentRan() = runBlocking {
        insertItem(title = "Original Title")
        repository.updateTitle("i1", "User's Own Title") // user edits the title, locking it

        repository.applyEnrichment(
            itemId = "i1", title = "Server-Generated Title", summary = "s", category = "tech",
            thumbnailUrl = null, entitiesJson = null, eventDate = null, tags = listOf("a")
        )

        val after = dao.getItemById("i1")!!
        assertEquals("User's Own Title", after.title)
        assertTrue("the edit lock itself must survive enrichment too", after.titleEditedByUser)
    }

    @Test
    fun applyEnrichment_overwritesTitle_whenUserHasNotEditedIt() = runBlocking {
        insertItem(title = "Original Title", titleEditedByUser = false)

        repository.applyEnrichment(
            itemId = "i1", title = "Server-Generated Title", summary = "s", category = "tech",
            thumbnailUrl = null, entitiesJson = null, eventDate = null, tags = emptyList()
        )

        val after = dao.getItemById("i1")!!
        assertEquals("Server-Generated Title", after.title)
    }

    @Test
    fun applyEnrichment_respectsTagsEditedByUser_neverReplacesTagsOnceUserHasEditedThem() = runBlocking {
        insertItem(tagsEditedByUser = true)

        repository.applyEnrichment(
            itemId = "i1", title = "t", summary = "s", category = "tech",
            thumbnailUrl = null, entitiesJson = null, eventDate = null, tags = listOf("should-not-be-added")
        )

        val crossRefs = dao.getActiveCrossRefsForItem("i1")
        assertTrue("tagsEditedByUser must block AI tag replacement", crossRefs.isEmpty())
    }

    @Test
    fun applyEnrichment_stillAppliesAiTags_whenUserHasNotEditedTags() = runBlocking {
        insertItem(tagsEditedByUser = false)

        repository.applyEnrichment(
            itemId = "i1", title = "t", summary = "s", category = "tech",
            thumbnailUrl = null, entitiesJson = null, eventDate = null, tags = listOf("news", "tech")
        )

        val labels = dao.getActiveCrossRefsForItem("i1").mapNotNull { dao.getTagById(it.tagId)?.label }.toSet()
        assertEquals(setOf("news", "tech"), labels)
    }

    @Test
    fun applyEnrichment_clearsAPriorFailureState_onSuccess() = runBlocking {
        insertItem(status = ItemStatus.NEEDS_ATTENTION)
        repository.markNeedsAttention("i1", errorCode = "FETCH_FAILED", errorMessage = "boom")

        repository.applyEnrichment(
            itemId = "i1", title = "t", summary = "s", category = "tech",
            thumbnailUrl = null, entitiesJson = null, eventDate = null, tags = emptyList()
        )

        val after = dao.getItemById("i1")!!
        assertEquals(ItemStatus.ENRICHED, after.status)
        assertNull(after.errorCode)
        assertNull(after.errorMessage)
    }

    // -----------------------------------------------------------------
    // R1: rapid star/unstar never corrupts unrelated fields
    // -----------------------------------------------------------------

    @Test
    fun rapidStarToggle_leavesOtherFieldsUntouched() = runBlocking {
        val original = insertItem(title = "Keep Me")
        repeat(5) {
            repository.setStarred("i1", true)
            repository.setStarred("i1", false)
        }
        repository.setStarred("i1", true)

        val after = dao.getItemById("i1")!!
        assertTrue(after.isStarred)
        assertEquals(original.title, after.title)
        assertEquals(original.status, after.status)
        assertNull(after.deletedAt)
    }

    // -----------------------------------------------------------------
    // R4: a stale flush after the debounced write already landed is a harmless no-op
    // -----------------------------------------------------------------

    @Test
    fun updateTitle_calledTwiceWithSameValue_isIdempotent() = runBlocking {
        insertItem(title = "Original")
        repository.updateTitle("i1", "Edited Title") // the "real" debounced save
        repository.updateTitle("i1", "Edited Title") // a late flush() racing it, same value

        val after = dao.getItemById("i1")!!
        assertEquals("Edited Title", after.title)
        assertTrue(after.titleEditedByUser)
    }

    @Test
    fun starToggle_survivesAConcurrentNoteBodyEdit_andViceVersa() = runBlocking {
        insertItem(status = ItemStatus.PENDING)
        repository.setStarred("i1", true)
        repository.updateNoteBody("i1", "a fresh body edit")

        val after = dao.getItemById("i1")!!
        assertTrue("body edit must not revert an earlier star", after.isStarred)
        assertEquals("a fresh body edit", after.bodyText)
    }

    // -----------------------------------------------------------------
    // R3: the 30-day auto-purge must never destroy an item restored in the gap
    // between its candidate SELECT and its DELETE
    // -----------------------------------------------------------------

    @Test
    fun purgeExpiredTrash_doesNotDeleteAnItemRestoredAfterTheCandidateScanButBeforeTheDelete() = runBlocking {
        val retentionMs = 30L * 24 * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        insertItem(deletedAt = now - retentionMs - 1000, dirty = false) // trashed, synced, expired

        // Simulate purgeExpiredTrash's own candidate SELECT having already run and picked up
        // "i1", then a restore committing before the per-id DELETE step actually executes.
        val cutoff = now - retentionMs
        val candidateIds = dao.getExpiredTrashItemIds(cutoff)
        assertEquals(listOf("i1"), candidateIds)

        repository.restoreItem("i1") // races in right here, between SELECT and DELETE

        val deletedRows = dao.hardDeleteExpiredTrashItem("i1", cutoff)

        assertEquals("the guarded DELETE must match zero rows once the item is restored", 0, deletedRows)
        val after = dao.getItemById("i1")
        assertNotNull("the restored item must still exist", after)
        assertNull("the restored item must still be un-trashed", after!!.deletedAt)
    }

    @Test
    fun purgeExpiredTrash_stillPurgesAGenuinelyExpiredUnrestoredItem() = runBlocking {
        val retentionMs = 30L * 24 * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        insertItem(deletedAt = now - retentionMs - 1000, dirty = false)

        val purged = repository.purgeExpiredTrash(retentionMs)

        assertEquals(1, purged)
        assertNull(dao.getItemById("i1"))
    }

    @Test
    fun purgeExpiredTrash_neverTouchesAnUnsyncedTombstone() = runBlocking {
        val retentionMs = 30L * 24 * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        insertItem(deletedAt = now - retentionMs - 1000, dirty = true) // tombstone not yet pushed

        val purged = repository.purgeExpiredTrash(retentionMs)

        assertEquals(0, purged)
        assertNotNull(dao.getItemById("i1"))
    }

    @Test
    fun purgeExpiredTrash_leavesAStillTrashedButNotYetExpiredItemAlone() = runBlocking {
        val retentionMs = 30L * 24 * 60 * 60 * 1000
        insertItem(deletedAt = System.currentTimeMillis() - 1000, dirty = false) // trashed a second ago

        val purged = repository.purgeExpiredTrash(retentionMs)

        assertEquals(0, purged)
        assertNotNull(dao.getItemById("i1"))
    }
}
