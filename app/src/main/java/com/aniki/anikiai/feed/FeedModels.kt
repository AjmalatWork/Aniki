package com.aniki.anikiai.feed

/**
 * The ranking inputs the pure feed module operates on — a dependency-free projection of an item,
 * so buildFeed can be unit-tested in isolation (mirrors how sync/MergeLogic.kt stays DB-free).
 */
data class FeedCandidate(
    val id: String,
    val type: String,
    val createdAt: Long,
    val lastViewedAt: Long?,
    val lastShownAt: Long?,
    val isStarred: Boolean,
    val eventDate: Long?,
    val status: String,
    val tags: List<String>
)

/** One engagement event projected with the tags of the item it targeted, for tag-weight derivation. */
data class EngagementRecord(
    val eventType: String,
    val value: Double?,
    val itemTags: List<String>
)
