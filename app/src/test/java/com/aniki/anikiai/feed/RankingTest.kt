package com.aniki.anikiai.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 86_400_000L
private const val NOW = 1_800_000_000_000L // fixed reference "now" so tests are deterministic

private fun candidate(
    id: String,
    type: String = "WEB_ARTICLE",
    createdAt: Long = NOW,
    lastViewedAt: Long? = null,
    lastShownAt: Long? = null,
    isStarred: Boolean = false,
    eventDate: Long? = null,
    status: String = "ENRICHED",
    tags: List<String> = emptyList()
) = FeedCandidate(id, type, createdAt, lastViewedAt, lastShownAt, isStarred, eventDate, status, tags)

private fun assertMaxRun(types: List<String>, maxRun: Int) {
    var run = 1
    for (i in 1 until types.size) {
        run = if (types[i] == types[i - 1]) run + 1 else 1
        assertTrue("type run exceeded $maxRun at index $i: $types", run <= maxRun)
    }
}

class RankingTest {

    @Test
    fun `recent items outrank older ones when both are unseen`() {
        val fresh = candidate("fresh", createdAt = NOW - 1 * DAY)
        val older = candidate("older", createdAt = NOW - 5 * DAY)
        val feed = buildFeed(listOf(older, fresh), emptyMap(), NOW)
        assertEquals(listOf("fresh", "older"), feed.map { it.id })
    }

    @Test
    fun `old unviewed item resurfaces above a recently viewed middling item`() {
        val oldUnviewed = candidate("old", createdAt = NOW - 40 * DAY, lastViewedAt = null)
        val recentlyViewed = candidate("seen", createdAt = NOW - 8 * DAY, lastViewedAt = NOW - 1 * DAY)
        val feed = buildFeed(listOf(recentlyViewed, oldUnviewed), emptyMap(), NOW)
        assertEquals(listOf("old", "seen"), feed.map { it.id })
    }

    @Test
    fun `imminent eventDate jumps an item to the top over a fresh save`() {
        val fresh = candidate("fresh", createdAt = NOW)
        val eventItem = candidate("event", createdAt = NOW - 20 * DAY, eventDate = NOW + 2 * DAY)
        val feed = buildFeed(listOf(fresh, eventItem), emptyMap(), NOW)
        assertEquals("event", feed.first().id)
    }

    @Test
    fun `a just-shown item is suppressed below an unshown peer`() {
        val shownNow = candidate("shown", createdAt = NOW - 1 * DAY, lastShownAt = NOW)
        val unshown = candidate("unshown", createdAt = NOW - 1 * DAY, lastShownAt = null)
        val feed = buildFeed(listOf(shownNow, unshown), emptyMap(), NOW)
        assertEquals(listOf("unshown", "shown"), feed.map { it.id })
    }

    @Test
    fun `cold start with empty weights does not crash and ignores tags`() {
        val tagged = candidate("tagged", createdAt = NOW - 1 * DAY, tags = listOf("kotlin", "android"))
        val untagged = candidate("untagged", createdAt = NOW - 2 * DAY)
        val feed = buildFeed(listOf(untagged, tagged), emptyMap(), NOW)
        // Pure recency (tagged is newer); affinity contributed nothing with no engagement history.
        assertEquals(listOf("tagged", "untagged"), feed.map { it.id })
    }

    @Test
    fun `tag affinity lifts an item whose tags the user engages with`() {
        val liked = candidate("liked", createdAt = NOW - 3 * DAY, tags = listOf("kotlin"))
        val neutral = candidate("neutral", createdAt = NOW) // fresher, so higher recency
        val weights = mapOf("kotlin" to 1.0)
        val feed = buildFeed(listOf(neutral, liked), weights, NOW)
        assertEquals("liked", feed.first().id) // affinity overturns the recency edge
    }

    @Test
    fun `non-ENRICHED items are excluded from the feed`() {
        val enriched = candidate("ok", status = "ENRICHED")
        val pending = candidate("pending", status = "PENDING")
        val needs = candidate("needs", status = "NEEDS_ATTENTION")
        val feed = buildFeed(listOf(enriched, pending, needs), emptyMap(), NOW)
        assertEquals(listOf("ok"), feed.map { it.id })
    }

    @Test
    fun `diversity pass breaks up a run of same-type items`() {
        val items = listOf(
            candidate("a1", type = "WEB_ARTICLE"),
            candidate("a2", type = "WEB_ARTICLE"),
            candidate("a3", type = "WEB_ARTICLE"),
            candidate("a4", type = "WEB_ARTICLE"),
            candidate("n1", type = "NOTE")
        )
        val feed = buildFeed(items, emptyMap(), NOW)
        assertMaxRun(feed.map { it.type }, 2)
        assertEquals(
            listOf("WEB_ARTICLE", "WEB_ARTICLE", "NOTE", "WEB_ARTICLE", "WEB_ARTICLE"),
            feed.map { it.type }
        )
    }

    @Test
    fun `diversity holds even when one type dominates the score order`() {
        // The realistic failure case: a burst of same-type saves (4 articles) that all outscore or
        // tie the few breakers (2 notes, 1 video). A naive deferral strands the articles in one run;
        // the feasibility-aware pass must still keep every run <= 2.
        val items = buildList {
            repeat(4) { add(candidate("a$it", type = "WEB_ARTICLE", createdAt = NOW - it * 1000L)) }
            repeat(2) { add(candidate("n$it", type = "NOTE", createdAt = NOW - it * 1000L)) }
            add(candidate("v0", type = "YOUTUBE_VIDEO", createdAt = NOW))
        }
        val feed = buildFeed(items, emptyMap(), NOW)
        assertEquals(7, feed.size)
        assertMaxRun(feed.map { it.type }, 2)
    }

    @Test
    fun `null lastViewed and lastShown are handled without error`() {
        val item = candidate("x", createdAt = NOW - 3 * DAY)
        val feed = buildFeed(listOf(item), emptyMap(), NOW)
        assertEquals(listOf("x"), feed.map { it.id })
    }
}
