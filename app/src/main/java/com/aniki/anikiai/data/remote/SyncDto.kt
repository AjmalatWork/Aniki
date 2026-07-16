package com.aniki.anikiai.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class SyncEntitiesDto(
    val people: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val dates: List<String> = emptyList()
)

@Serializable
data class SyncItemDto(
    val id: String,
    val type: String,
    val sourceUrl: String?,
    val normalizedUrl: String?,
    val title: String,
    val bodyText: String?,
    val summary: String?,
    val thumbnailUrl: String?,
    val category: String?,
    val entities: SyncEntitiesDto?,
    val eventDate: String?,
    val status: String,
    val isStarred: Boolean,
    val summaryLocked: Boolean,
    val tagsLocked: Boolean,
    val titleLocked: Boolean,
    val updatedAt: Long,
    val deletedAt: Long?,
    val seq: Long = 0 // ignored on push
)

@Serializable
data class SyncTagDto(
    val id: String,
    val label: String,
    val origin: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val seq: Long = 0
)

@Serializable
data class SyncItemTagDto(
    val itemId: String,
    val tagId: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val seq: Long = 0
)

@Serializable
data class SyncEngagementEventDto(
    val id: String,
    val itemId: String,
    val eventType: String,
    val value: Double?,
    val createdAt: Long,
    val seq: Long = 0
)

@Serializable
data class SyncPullResponse(
    val items: List<SyncItemDto>,
    val tags: List<SyncTagDto>,
    val itemTags: List<SyncItemTagDto>,
    val engagementEvents: List<SyncEngagementEventDto>,
    val nextCursor: Long,
    /** True when this page hit the server's page-size cap and there may be more rows beyond
     *  nextCursor -- the caller should pull again with since=nextCursor until this is false. */
    val hasMore: Boolean = false
)

@Serializable
data class SyncPushRequest(
    val items: List<SyncItemDto>,
    val tags: List<SyncTagDto>,
    val itemTags: List<SyncItemTagDto>,
    val engagementEvents: List<SyncEngagementEventDto>
)

@Serializable
data class SyncAckDto(
    val clientId: String,
    val id: String,
    val seq: Long
)

@Serializable
data class SyncItemTagAckDto(
    val itemId: String,
    val tagId: String,
    val seq: Long
)

@Serializable
data class SyncPushResponse(
    val itemAcks: List<SyncAckDto>,
    val tagAcks: List<SyncAckDto>,
    val itemTagAcks: List<SyncItemTagAckDto>,
    val engagementEventAcks: List<SyncAckDto>,
    val nextCursor: Long
)
