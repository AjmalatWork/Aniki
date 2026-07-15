package com.aniki.anikiai.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

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
