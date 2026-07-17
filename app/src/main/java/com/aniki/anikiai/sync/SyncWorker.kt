package com.aniki.anikiai.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aniki.anikiai.AnikiApplication
import com.aniki.anikiai.data.remote.NetworkClient
import com.aniki.anikiai.work.NoteTitleBackfiller
import com.aniki.anikiai.work.ThumbnailBackfiller
import com.aniki.anikiai.work.TrashPurger
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult {
        val app = applicationContext as AnikiApplication
        val syncManager = SyncManager(NetworkClient.api, app.syncRepository, app.syncCursorStore)
        return when (val result = syncManager.runSync()) {
            is SyncResult.Success -> {
                // All three piggyback on a successful sync (the approved "next sync/open"
                // trigger) rather than their own worker/schedule.
                ThumbnailBackfiller.run(app.repository, NetworkClient.api) // zero Gemini cost
                NoteTitleBackfiller.run(applicationContext, app.repository) // throttled, real cost
                TrashPurger.run(app.repository) // local-only, no network/Gemini cost
                WorkResult.success()
            }
            // Guest mode / signed out: nothing to sync yet, not a failure.
            is SyncResult.Unauthenticated -> WorkResult.success()
            is SyncResult.Error -> WorkResult.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "sync-worker"
        private const val PERIODIC_UNIQUE_NAME = "sync-worker-periodic"

        /**
         * Triggered sync: app foreground, after a local write, or opportunistically whenever
         * something changed. KEEP (not REPLACE) is the debounce — if a run is already
         * pending/in-flight, redundant triggers are no-ops rather than restarting it, and
         * WorkManager's CONNECTED constraint means a call made while offline naturally fires as
         * soon as connectivity returns.
         */
        fun enqueueOneTime(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Periodic background safety net. 15 minutes is WorkManager's minimum periodic interval. */
        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
