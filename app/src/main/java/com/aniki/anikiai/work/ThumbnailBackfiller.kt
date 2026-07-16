package com.aniki.anikiai.work

import com.aniki.anikiai.data.remote.AnikiApi
import com.aniki.anikiai.data.remote.ExtractThumbnailRequestDto
import com.aniki.anikiai.data.repository.ItemRepository
import timber.log.Timber

private const val BATCH_SIZE = 5

/**
 * Lazy backfill for articles saved before OG-image extraction existed (or where it found
 * nothing): re-extracts *only* the thumbnail via /extract-thumbnail, which never touches Gemini
 * (thumbnailUrl has only ever come from extraction, see EnrichResponse's construction on the
 * server) -- so this has zero cost against the daily call cap, unlike a full re-enrichment would.
 *
 * Throttled to a small batch per call and marks every attempt (success or failure) so a
 * dead/unreachable URL is never retried forever; triggered after each successful sync (see
 * SyncWorker), so a device with a large backlog converges over a handful of sync cycles rather
 * than firing a burst of requests all at once.
 */
object ThumbnailBackfiller {

    suspend fun run(repository: ItemRepository, api: AnikiApi) {
        val candidates = repository.getArticlesNeedingThumbnailBackfill().take(BATCH_SIZE)
        if (candidates.isEmpty()) return

        var succeeded = 0
        for (item in candidates) {
            val sourceUrl = item.sourceUrl ?: continue
            val thumbnailUrl = runCatching {
                val response = api.extractThumbnail(ExtractThumbnailRequestDto(sourceUrl))
                if (response.isSuccessful) response.body()?.thumbnailUrl else null
            }.getOrNull()

            repository.applyThumbnailBackfill(item.id, thumbnailUrl)
            if (thumbnailUrl != null) succeeded++
        }
        Timber.i("thumbnail backfill: attempted=%d found=%d", candidates.size, succeeded)
    }
}
