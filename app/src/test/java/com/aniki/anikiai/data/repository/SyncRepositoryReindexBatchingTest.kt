package com.aniki.anikiai.data.repository

import com.aniki.anikiai.data.db.FtsIndexer
import com.aniki.anikiai.data.db.ItemDao
import com.aniki.anikiai.data.db.ItemTagCrossRef
import com.aniki.anikiai.data.remote.SyncItemDto
import com.aniki.anikiai.data.remote.SyncItemTagDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1 (maintainability audit): FtsIndexer.index() does a full getItem + deleteFts + getCrossRefs +
 * N*getTag + insert round trip -- before this pass, mergeItem and mergeItemTag each indexed
 * synchronously as a side effect of their own write, so a single pull page containing one item
 * plus T of its own tag-links triggered 1 (mergeItem) + T (mergeItemTag) independent full rebuilds
 * of that SAME item's FTS row: O(items x tags) work for what only needs O(items).
 *
 * SyncManager.runSync now collects every touched itemId from mergeItem/mergeItemTag into one Set
 * per page and calls SyncRepository.reindexItems once at the end of the page (see its doc). This
 * test drives that exact call shape directly against SyncRepository (not the full SyncManager
 * orchestration, which SyncManagerTest already covers) with a mocked ItemDao/FtsIndexer -- no real
 * SQLite needed, since mergeItem/mergeItemTag/reindexItems only orchestrate DAO calls, they don't
 * depend on real query semantics -- and asserts the item's FTS row is rebuilt exactly once, not
 * once per tag-link.
 */
class SyncRepositoryReindexBatchingTest {

    private val itemDao = mockk<ItemDao>(relaxed = true)
    private val ftsIndexer = mockk<FtsIndexer>(relaxed = true)
    private val repository = SyncRepository(itemDao, ftsIndexer)

    private fun pulledItem(id: String, updatedAt: Long) = SyncItemDto(
        id = id,
        type = "NOTE",
        sourceUrl = null,
        normalizedUrl = null,
        title = "t",
        bodyText = "b",
        summary = null,
        thumbnailUrl = null,
        category = null,
        entities = null,
        eventDate = null,
        status = "ENRICHED",
        isStarred = false,
        summaryLocked = false,
        tagsLocked = false,
        titleLocked = false,
        updatedAt = updatedAt,
        deletedAt = null
    )

    @Test
    fun oneItemWithSeveralTagLinksInOnePage_reindexesTheItemExactlyOnce() = runTest {
        // itemDao is relaxed: getItemById/getActiveCrossRefsForItem default to null/emptyList,
        // so both mergeItem and mergeItemTag take the INSERT branch for every call below --
        // exactly the "new item pulled alongside several new tag-links in the same page" shape.
        val itemOutcome = repository.mergeItem(pulledItem("item-1", updatedAt = 1000L))

        val itemsToReindex = mutableSetOf<String>()
        itemOutcome.reindexItemId?.let { itemsToReindex += it }

        listOf("tag-1", "tag-2", "tag-3").forEach { tagId ->
            val dto = SyncItemTagDto(itemId = "item-1", tagId = tagId, updatedAt = 1000L, deletedAt = null)
            repository.mergeItemTag(dto)?.let { itemsToReindex += it }
        }
        repository.reindexItems(itemsToReindex)

        coVerify(exactly = 1) { ftsIndexer.index("item-1") }
    }

    @Test
    fun aNoOpMerge_contributesNothingToTheReindexSet() = runTest {
        // itemDao relaxed -> getItemById returns null -> mergeItem's decision is always INSERT in
        // this setup, so to exercise a genuine no-op this drives mergeItemTag alone with an
        // already-identical local cross-ref (decideMerge -> NO_OP), confirming reindexItemId is
        // null and nothing gets passed to reindexItems for it.
        coEvery { itemDao.getActiveCrossRefsForItem("item-1") } returns listOf(
            ItemTagCrossRef(itemId = "item-1", tagId = "tag-1", updatedAt = 1000L, deletedAt = null)
        )

        val dto = SyncItemTagDto(itemId = "item-1", tagId = "tag-1", updatedAt = 1000L, deletedAt = null)
        val result = repository.mergeItemTag(dto)

        assertEquals(null, result)
        coVerify(exactly = 0) { ftsIndexer.index(any()) }
    }
}
