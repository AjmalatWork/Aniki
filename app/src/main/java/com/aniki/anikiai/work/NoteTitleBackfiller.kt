package com.aniki.anikiai.work

import android.content.Context
import com.aniki.anikiai.data.repository.ItemRepository
import timber.log.Timber

private const val BATCH_SIZE = 3

/**
 * Throttled backfill for notes enriched before Gemini-generated titles existed: re-runs the
 * *normal* enrichment path (a real, billable Gemini call -- title generation genuinely needs the
 * LLM, unlike the thumbnail backfill) for a small batch of eligible notes per sync cycle, so a
 * device with many old notes converges over several sync cycles instead of bursting the daily cap
 * all at once. Each candidate is marked attempted the moment it's enqueued (see
 * ItemDao.markTitleBackfillAttempted's doc) so a note whose title generation keeps failing is
 * only ever billed once per device, not retried forever.
 */
object NoteTitleBackfiller {

    suspend fun run(context: Context, repository: ItemRepository) {
        val candidates = repository.getNotesNeedingTitleBackfill().take(BATCH_SIZE)
        if (candidates.isEmpty()) return

        for (item in candidates) {
            repository.markTitleBackfillAttempted(item.id)
            EnrichmentScheduler.enqueue(context, item.id)
        }
        Timber.i("note title backfill: enqueued=%d", candidates.size)
    }
}
