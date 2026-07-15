package com.aniki.anikiai.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

object ItemType {
    const val WEB_ARTICLE = "WEB_ARTICLE"
    const val YOUTUBE_VIDEO = "YOUTUBE_VIDEO"
    const val NOTE = "NOTE"
}

object ItemStatus {
    const val PENDING = "PENDING"
    const val ENRICHED = "ENRICHED"
    const val NEEDS_ATTENTION = "NEEDS_ATTENTION"
}

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val id: String,             // UUID, generated client-side
    val type: String,                       // "WEB_ARTICLE" | "YOUTUBE_VIDEO" | "NOTE"
    val sourceUrl: String?,
    val normalizedUrl: String?,             // lowercased, stripped tracking params, no trailing slash
    val title: String,
    val bodyText: String?,                  // note body, or null for links pre-enrichment
    val summary: String?,                   // null in this slice
    val thumbnailUrl: String?,              // null in this slice
    val category: String?,                  // null in this slice
    val entities: String? = null,           // raw JSON {people,places,dates} from enrichment; synced as-is
    val eventDate: Long?,                   // null in this slice
    val status: String,                     // "PENDING" | "ENRICHED" | "NEEDS_ATTENTION" — always PENDING in this slice
    val isStarred: Boolean = false,
    val summaryEditedByUser: Boolean = false,
    val tagsEditedByUser: Boolean = false,
    val createdAt: Long,                    // epoch millis
    val lastViewedAt: Long? = null,
    val lastShownAt: Long? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val dirty: Boolean = false              // reserved for future sync; just set true on write
)
