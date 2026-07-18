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
    // order; status backs the pending-items reconciliation scan; deletedAt backs the
    // `WHERE deletedAt IS NULL`/`IS NOT NULL` filter present in nearly every hot read query
    // (observeAllItems, observeAllItemsWithTags, search, getAllItemsWithTags) plus the trash
    // purge scan (getExpiredTrashItemIds) -- previously unindexed despite being the single most
    // common predicate in the whole query set (S3 of the maintainability audit).
    indices = [
        Index(value = ["normalizedUrl"]),
        Index(value = ["createdAt"]),
        Index(value = ["status"]),
        Index(value = ["deletedAt"])
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
    // is true; see FeedViewModel.applyFreshSnapshot(), which sets this the moment it decides to animate the
    // item so a later refresh() (re-entering Feed) or a fresh app launch never replays it.
    val demoLandingAnimationShown: Boolean = false,
    // Local-only (not part of SyncItemDto/synced) -- why the last enrichment attempt landed on
    // NEEDS_ATTENTION, if it did. errorCode is the server's machine-readable EnrichmentErrorCode
    // (RATE_LIMITED | QUOTA_EXCEEDED | FETCH_FAILED | EXTRACTION_FAILED | GENERIC), or null for a
    // failure with no server response at all (a raw network exception -- see EnrichmentWorker,
    // which deliberately doesn't distinguish those further). errorMessage is the server's own
    // readable message, shown as-is in place of the old hardcoded "Couldn't process this item."
    // Both cleared back to null the moment the item next reaches ENRICHED or PENDING (see
    // ItemRepository.applyEnrichment/updateNoteBody) so a stale failure never lingers past the
    // outcome it described. Kept local-only rather than synced: it's diagnostic metadata about one
    // device's attempt, not user content, and the underlying `status` (which IS synced) already
    // tells another device an item needs attention even without the detail.
    val errorCode: String? = null,
    val errorMessage: String? = null,
    // Local-only (not part of SyncItemDto/synced) -- running total of this item's engagement
    // signal (see feed.signalForEvent), incrementally folded in by ItemRepository.recordEvent
    // (own events) and SyncRepository.mergeEngagementEvent (pulled events) as each event lands,
    // rather than recomputed from the full engagement_events history on every feed refresh (see
    // ItemRepository.computeUserTagWeights). Each device maintains its own total independently,
    // like every other local-only column -- SyncRepository.mergeItem explicitly preserves it
    // across a pulled item's whole-row replace (see its doc) so a remote content update can't
    // silently zero out signal this device already folded in.
    val engagementSignal: Double = 0.0
)
