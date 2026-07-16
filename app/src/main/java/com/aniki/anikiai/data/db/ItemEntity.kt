package com.aniki.anikiai.data.db

import androidx.room.Entity
import androidx.room.Index
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

@Entity(
    tableName = "items",
    // normalizedUrl backs the dedupe lookup on every save; createdAt backs the default sort
    // order; status backs the pending-items reconciliation scan. All previously unindexed.
    indices = [
        Index(value = ["normalizedUrl"]),
        Index(value = ["createdAt"]),
        Index(value = ["status"])
    ]
)
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
    val titleEditedByUser: Boolean = false,
    // Local-only (not synced) -- caps the note-title backfill (work/NoteTitleBackfiller.kt) to
    // one real Gemini attempt per note per device, so a note whose generated title stays stuck
    // (malformed/empty LLM response) doesn't get re-billed against the daily cap every sync cycle.
    val titleBackfillAttempted: Boolean = false,
    val createdAt: Long,                    // epoch millis
    val lastViewedAt: Long? = null,
    val lastShownAt: Long? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val dirty: Boolean = false,             // reserved for future sync; just set true on write
    // Local-only (not part of SyncItemDto/synced) -- each device backfills its own thumbnail
    // lazily and independently; a shared/synced flag would just mean one device's failed attempt
    // permanently stops every other device from ever trying. See ItemRepository.backfillThumbnails.
    val thumbnailBackfillAttempted: Boolean = false,
    // Local-only (not part of SyncItemDto/synced) -- flags the onboarding "how sharing works"
    // demo item. It's otherwise a normal, visible, user-deletable Library/Feed/search item (so
    // the user can see what the demo share actually did); isDemo only gates sync (never pushed)
    // and enrichment (never real-enriched -- see share/OnboardingDemoContent.kt and
    // ui/onboarding/ShareTipScreen.kt). Only ever set true by ItemRepository.saveSharedContent's
    // isDemo branch.
    val isDemo: Boolean = false,
    // Local-only (not part of SyncItemDto/synced) -- whether the Feed's one-time "you just shared
    // this" landing animation has already played for this item. Only ever meaningful when isDemo
    // is true; see FeedViewModel.refresh(), which sets this the moment it decides to animate the
    // item so a later refresh() (re-entering Feed) or a fresh app launch never replays it.
    val demoLandingAnimationShown: Boolean = false
)
