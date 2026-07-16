package com.aniki.anikiai.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against a real in-memory SQLite/FTS4 engine (a JVM unit test can't provide one — Room's
 * FTS4 support needs the platform's SQLite). Covers the two riskiest, previously-untested things
 * in the search path: FtsIndexer keeping items_fts in sync (index/remove/backfillMissing), and
 * ItemDao.searchItemsWithTags' UNION-ALL trick for title-priority ranking without a second MATCH
 * in one SELECT (SQLite forbids that — see the comment on that query).
 */
@RunWith(AndroidJUnit4::class)
class FtsIndexerAndroidTest {

    private lateinit var db: AnikiDatabase
    private lateinit var dao: ItemDao
    private lateinit var indexer: FtsIndexer

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AnikiDatabase::class.java).build()
        dao = db.itemDao()
        indexer = FtsIndexer(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun item(
        id: String,
        title: String,
        body: String = "",
        summary: String? = null,
        deletedAt: Long? = null,
        createdAt: Long = 1000L
    ) = ItemEntity(
        id = id,
        type = ItemType.NOTE,
        sourceUrl = null,
        normalizedUrl = null,
        title = title,
        bodyText = body,
        summary = summary,
        thumbnailUrl = null,
        category = null,
        eventDate = null,
        status = ItemStatus.ENRICHED,
        createdAt = createdAt,
        updatedAt = 1000L,
        deletedAt = deletedAt
    )

    @Test
    fun index_createsSearchableFtsRow() = runBlocking {
        dao.insertItem(item("i1", title = "Kotlin Coroutines Guide", body = "structured concurrency"))
        indexer.index("i1")

        val results = dao.searchItemsWithTags("coroutines*").first()

        assertEquals(1, results.size)
        assertEquals("i1", results[0].item.id)
    }

    @Test
    fun index_isIdempotent_reindexingDoesNotDuplicateRows() = runBlocking {
        dao.insertItem(item("i1", title = "Kotlin Guide"))
        indexer.index("i1")
        indexer.index("i1")
        indexer.index("i1")

        val results = dao.searchItemsWithTags("kotlin*").first()

        assertEquals(1, results.size)
    }

    @Test
    fun index_onTombstonedItem_removesRatherThanIndexes() = runBlocking {
        dao.insertItem(item("i1", title = "Deleted Note", deletedAt = 2000L))
        indexer.index("i1")

        val results = dao.searchItemsWithTags("deleted*").first()

        assertTrue(results.isEmpty())
    }

    @Test
    fun remove_deletesTheFtsRow() = runBlocking {
        dao.insertItem(item("i1", title = "Removable Note"))
        indexer.index("i1")
        assertEquals(1, dao.searchItemsWithTags("removable*").first().size)

        indexer.remove("i1")

        assertTrue(dao.searchItemsWithTags("removable*").first().isEmpty())
    }

    @Test
    fun backfillMissing_indexesItemsNeverIndexed_leavesAlreadyIndexedAlone() = runBlocking {
        dao.insertItem(item("i1", title = "Already Indexed"))
        indexer.index("i1")
        dao.insertItem(item("i2", title = "Never Indexed"))
        // i2 deliberately never gets indexer.index() called — simulates a pulled-but-unindexed row.

        indexer.backfillMissing()

        assertEquals(1, dao.searchItemsWithTags("already*").first().size)
        assertEquals(1, dao.searchItemsWithTags("never*").first().size)
    }

    @Test
    fun search_titleMatch_isRankedBeforeBodyOnlyMatch() = runBlocking {
        // "gradient" only in the body of i1, but in the title of i2 -- title priority (the
        // UNION-ALL of two single-MATCH subqueries, see ItemDao.searchItemsWithTags) must put
        // i2 first regardless of insertion/createdAt order.
        dao.insertItem(item("i1", title = "Cooking notes", body = "a gradient of flavors", createdAt = 9999L))
        dao.insertItem(item("i2", title = "Gradient descent explained", body = "", createdAt = 1L))
        indexer.index("i1")
        indexer.index("i2")

        val results = dao.searchItemsWithTags("gradient*").first()

        assertEquals(2, results.size)
        assertEquals("i2", results[0].item.id) // title match ranked first despite being older
        assertEquals("i1", results[1].item.id)
    }

    @Test
    fun search_doesNotReturnTombstonedItems() = runBlocking {
        dao.insertItem(item("i1", title = "Live Note"))
        indexer.index("i1")
        dao.insertItem(item("i2", title = "Tombstoned Note", deletedAt = 5000L))
        indexer.index("i2") // no-ops per index()'s own tombstone check, but be explicit either way

        val results = dao.searchItemsWithTags("note*").first()

        assertEquals(1, results.size)
        assertEquals("i1", results[0].item.id)
    }
}
