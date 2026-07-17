package com.aniki.anikiai.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

// Buffer past the exact UTC-midnight boundary the server's daily cap resets on (callLimiter.ts's
// utcDayKey()) -- avoids landing the retry a few seconds early on clock skew and hitting the same
// still-exhausted quota. Purely cosmetic if it's late; the point is never being early.
private val QUOTA_RESET_BUFFER_MS = TimeUnit.MINUTES.toMillis(5)

object EnrichmentScheduler {

    /**
     * itemId is the unique work name: re-sharing/re-saving the same item, or a manual
     * tap-to-retry, replaces any pending/failed attempt instead of stacking duplicates.
     */
    fun enqueue(context: Context, itemId: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<EnrichmentWorker>()
            .setInputData(Data.Builder().putString(EnrichmentWorker.KEY_ITEM_ID, itemId).build())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(itemId, policy, request)
    }

    /**
     * Quota-exceeded needs a completely different retry shape than every other failure: the
     * server's daily Gemini cap only resets at UTC midnight, so the normal 30s/60s/120s... backoff
     * (EnrichmentWorker.giveUpOrRetry) just burns all 5 attempts within minutes and lands on the
     * same failure anyway. This schedules exactly one fresh attempt timed for just past the next
     * reset, via setInitialDelay -- WorkManager supports multi-hour delays natively, no polling
     * involved. REPLACE is correct here (not KEEP): this call only happens from inside
     * doWork() right as that same unique work name's current run is finishing, so there is nothing
     * legitimate left in flight to clobber.
     */
    fun enqueueAfterQuotaReset(context: Context, itemId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<EnrichmentWorker>()
            .setInputData(Data.Builder().putString(EnrichmentWorker.KEY_ITEM_ID, itemId).build())
            .setConstraints(constraints)
            .setInitialDelay(millisUntilNextUtcMidnight() + QUOTA_RESET_BUFFER_MS, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(itemId, ExistingWorkPolicy.REPLACE, request)
    }

    private fun millisUntilNextUtcMidnight(): Long {
        val now = Instant.now()
        val nextMidnight = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        return (nextMidnight.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0)
    }

    /**
     * Cold-start safety net: an item can be committed to Room but never get its enqueue() call
     * (process killed between the write and the enqueue, e.g. a force-stop racing a share) —
     * unlike sync, PENDING items have no periodic worker to catch this, so they'd otherwise stay
     * stuck forever. KEEP (not REPLACE) so this never clobbers a job already legitimately in
     * flight/backing off. Idempotent, safe to call on every app start.
     */
    fun reconcilePending(context: Context, itemIds: List<String>) {
        itemIds.forEach { enqueue(context, it, ExistingWorkPolicy.KEEP) }
    }
}
