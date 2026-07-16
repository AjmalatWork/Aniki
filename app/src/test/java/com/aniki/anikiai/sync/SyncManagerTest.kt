package com.aniki.anikiai.sync

import com.aniki.anikiai.data.remote.AnikiApi
import com.aniki.anikiai.data.remote.SyncPullResponse
import com.aniki.anikiai.data.remote.SyncPushRequest
import com.aniki.anikiai.data.remote.SyncPushResponse
import com.aniki.anikiai.data.repository.SyncRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * SyncManager.runSync() has one hard contract (see its own doc comment): pull -> merge ->
 * push -> apply acks -> persist cursor, with the cursor written exactly once at the very end.
 * MergeLogic itself is covered by MergeLogicTest; this covers the orchestration around it,
 * which had zero coverage before this test existed.
 */
class SyncManagerTest {

    private val api = mockk<AnikiApi>()
    private val repository = mockk<SyncRepository>(relaxed = true)
    private val cursorStore = mockk<SyncCursorStore>(relaxed = true)
    private lateinit var manager: SyncManager

    private fun emptyPull(nextCursor: Long, hasMore: Boolean = false) = SyncPullResponse(
        items = emptyList(),
        tags = emptyList(),
        itemTags = emptyList(),
        engagementEvents = emptyList(),
        nextCursor = nextCursor,
        hasMore = hasMore
    )

    private fun pullWithItems(items: List<com.aniki.anikiai.data.remote.SyncItemDto>, nextCursor: Long, hasMore: Boolean = false) =
        SyncPullResponse(
            items = items,
            tags = emptyList(),
            itemTags = emptyList(),
            engagementEvents = emptyList(),
            nextCursor = nextCursor,
            hasMore = hasMore
        )

    private fun emptyPush(nextCursor: Long) = SyncPushResponse(
        itemAcks = emptyList(),
        tagAcks = emptyList(),
        itemTagAcks = emptyList(),
        engagementEventAcks = emptyList(),
        nextCursor = nextCursor
    )

    @Before
    fun setUp() {
        manager = SyncManager(api, repository, cursorStore)
        coEvery { cursorStore.getCursor() } returns 0L
        coEvery { repository.getDirtyItemDtos() } returns emptyList()
        coEvery { repository.getDirtyTagDtos() } returns emptyList()
        coEvery { repository.getDirtyItemTagDtos() } returns emptyList()
        coEvery { repository.getDirtyEngagementEventDtos() } returns emptyList()
    }

    @Test
    fun `401 on pull returns Unauthenticated without touching the cursor`() = runTest {
        coEvery { api.pullSync(any()) } returns Response.error(401, "".toResponseBody(null))

        val result = manager.runSync()

        assertEquals(SyncResult.Unauthenticated, result)
        coVerify(exactly = 0) { cursorStore.setCursor(any()) }
    }

    @Test
    fun `non-2xx pull returns Error without touching the cursor`() = runTest {
        coEvery { api.pullSync(any()) } returns Response.error(500, "".toResponseBody(null))

        val result = manager.runSync()

        assertTrue(result is SyncResult.Error)
        coVerify(exactly = 0) { cursorStore.setCursor(any()) }
    }

    @Test
    fun `successful sync with nothing dirty persists the pull cursor once and skips push`() = runTest {
        coEvery { api.pullSync(0L) } returns Response.success(emptyPull(nextCursor = 5L))

        val result = manager.runSync()

        assertEquals(SyncResult.Success(pulled = 0, pushed = 0, conflicts = 0), result)
        coVerify(exactly = 0) { api.pushSync(any()) }
        coVerify(exactly = 1) { cursorStore.setCursor(5L) }
    }

    @Test
    fun `dirty rows trigger a push and the cursor advances to the push's nextCursor`() = runTest {
        coEvery { api.pullSync(0L) } returns Response.success(emptyPull(nextCursor = 5L))
        coEvery { repository.getDirtyTagDtos() } returns listOf(mockk(relaxed = true))
        coEvery { api.pushSync(any()) } returns Response.success(emptyPush(nextCursor = 9L))

        val result = manager.runSync()

        assertEquals(SyncResult.Success(pulled = 0, pushed = 1, conflicts = 0), result)
        coVerify(exactly = 1) { cursorStore.setCursor(9L) }
    }

    @Test
    fun `401 on push returns Unauthenticated without persisting a cursor`() = runTest {
        coEvery { api.pullSync(0L) } returns Response.success(emptyPull(nextCursor = 5L))
        coEvery { repository.getDirtyTagDtos() } returns listOf(mockk(relaxed = true))
        coEvery { api.pushSync(any()) } returns Response.error(401, "".toResponseBody(null))

        val result = manager.runSync()

        assertEquals(SyncResult.Unauthenticated, result)
        coVerify(exactly = 0) { cursorStore.setCursor(any()) }
    }

    @Test
    fun `pull is fully merged and cursor persisted before push is issued`() = runTest {
        coEvery { api.pullSync(0L) } returns Response.success(emptyPull(nextCursor = 5L))
        coEvery { repository.getDirtyTagDtos() } returns listOf(mockk(relaxed = true))
        coEvery { api.pushSync(any()) } returns Response.success(emptyPush(nextCursor = 9L))

        manager.runSync()

        // Ordering per the class doc comment: pull -> merge -> push -> acks -> cursor persisted
        // exactly once, at the very end (not before the push).
        coVerifyOrder {
            api.pullSync(0L)
            repository.getDirtyTagDtos()
            api.pushSync(any())
            cursorStore.setCursor(9L)
        }
        coVerify(exactly = 1) { cursorStore.setCursor(any()) }
    }

    @Test
    fun `an exception anywhere is caught and surfaced as Error, cursor untouched`() = runTest {
        coEvery { api.pullSync(any()) } throws java.io.IOException("network down")

        val result = manager.runSync()

        assertTrue(result is SyncResult.Error)
        assertEquals("network down", (result as SyncResult.Error).message)
        coVerify(exactly = 0) { cursorStore.setCursor(any()) }
    }

    @Test
    fun `push request only includes dirty rows, not pulled ones`() = runTest {
        val dirtyTag = mockk<com.aniki.anikiai.data.remote.SyncTagDto>(relaxed = true)
        coEvery { api.pullSync(0L) } returns Response.success(emptyPull(nextCursor = 5L))
        coEvery { repository.getDirtyTagDtos() } returns listOf(dirtyTag)
        val requestSlot = io.mockk.slot<SyncPushRequest>()
        coEvery { api.pushSync(capture(requestSlot)) } returns Response.success(emptyPush(nextCursor = 9L))

        manager.runSync()

        assertEquals(listOf(dirtyTag), requestSlot.captured.tags)
        assertTrue(requestSlot.captured.items.isEmpty())
    }

    // -----------------------------------------------------------------
    // Pull pagination (S3): the server may cap a single pull response and set hasMore=true.
    // -----------------------------------------------------------------

    @Test
    fun `hasMore true loops the pull with the previous page's nextCursor until hasMore is false`() = runTest {
        coEvery { api.pullSync(0L) } returns Response.success(emptyPull(nextCursor = 5L, hasMore = true))
        coEvery { api.pullSync(5L) } returns Response.success(emptyPull(nextCursor = 9L, hasMore = true))
        coEvery { api.pullSync(9L) } returns Response.success(emptyPull(nextCursor = 9L, hasMore = false))

        val result = manager.runSync()

        assertEquals(SyncResult.Success(pulled = 0, pushed = 0, conflicts = 0), result)
        coVerifyOrder {
            api.pullSync(0L)
            api.pullSync(5L)
            api.pullSync(9L)
        }
        coVerify(exactly = 1) { cursorStore.setCursor(9L) }
    }

    @Test
    fun `pulled count in the result sums items across every page`() = runTest {
        val item1 = mockk<com.aniki.anikiai.data.remote.SyncItemDto>(relaxed = true)
        val item2 = mockk<com.aniki.anikiai.data.remote.SyncItemDto>(relaxed = true)
        val item3 = mockk<com.aniki.anikiai.data.remote.SyncItemDto>(relaxed = true)
        coEvery { api.pullSync(0L) } returns Response.success(pullWithItems(listOf(item1, item2), nextCursor = 5L, hasMore = true))
        coEvery { api.pullSync(5L) } returns Response.success(pullWithItems(listOf(item3), nextCursor = 8L, hasMore = false))

        val result = manager.runSync()

        assertEquals(SyncResult.Success(pulled = 3, pushed = 0, conflicts = 0), result)
    }

    @Test
    fun `cursor is still persisted exactly once even across multiple pull pages`() = runTest {
        coEvery { api.pullSync(0L) } returns Response.success(emptyPull(nextCursor = 5L, hasMore = true))
        coEvery { api.pullSync(5L) } returns Response.success(emptyPull(nextCursor = 9L, hasMore = false))

        manager.runSync()

        coVerify(exactly = 1) { cursorStore.setCursor(any()) }
        coVerify(exactly = 0) { cursorStore.setCursor(5L) } // must not persist an intermediate page's cursor
        coVerify(exactly = 1) { cursorStore.setCursor(9L) }
    }

    @Test
    fun `401 on a later page still returns Unauthenticated without persisting a cursor`() = runTest {
        coEvery { api.pullSync(0L) } returns Response.success(emptyPull(nextCursor = 5L, hasMore = true))
        coEvery { api.pullSync(5L) } returns Response.error(401, "".toResponseBody(null))

        val result = manager.runSync()

        assertEquals(SyncResult.Unauthenticated, result)
        coVerify(exactly = 0) { cursorStore.setCursor(any()) }
    }

    @Test
    fun `each pulled page is merged before moving to the next page`() = runTest {
        val item1 = mockk<com.aniki.anikiai.data.remote.SyncItemDto>(relaxed = true)
        val item2 = mockk<com.aniki.anikiai.data.remote.SyncItemDto>(relaxed = true)
        coEvery { api.pullSync(0L) } returns Response.success(pullWithItems(listOf(item1), nextCursor = 5L, hasMore = true))
        coEvery { api.pullSync(5L) } returns Response.success(pullWithItems(listOf(item2), nextCursor = 9L, hasMore = false))

        manager.runSync()

        coVerifyOrder {
            api.pullSync(0L)
            repository.mergeItem(item1)
            api.pullSync(5L)
            repository.mergeItem(item2)
        }
    }
}
