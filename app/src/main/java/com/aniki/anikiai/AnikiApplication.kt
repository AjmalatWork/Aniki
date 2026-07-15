package com.aniki.anikiai

import android.app.Application
import com.aniki.anikiai.data.db.AnikiDatabase
import com.aniki.anikiai.data.db.FtsIndexer
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.data.repository.SyncRepository
import com.aniki.anikiai.sync.SyncCursorStore
import com.aniki.anikiai.sync.SyncWorker
import com.aniki.anikiai.work.EnrichmentScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class AnikiApplication : Application() {

    private val database by lazy { AnikiDatabase.getInstance(this) }
    private val ftsIndexer by lazy { FtsIndexer(database.itemDao()) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository by lazy { ItemRepository(database.itemDao(), ftsIndexer) }
    val syncRepository by lazy { SyncRepository(database.itemDao(), ftsIndexer) }
    val syncCursorStore by lazy { SyncCursorStore(this) }

    override fun onCreate() {
        super.onCreate()
        // Local-only (logcat), not sent anywhere — see Slice 6 observability task.
        Timber.plant(Timber.DebugTree())
        SyncWorker.enqueuePeriodic(this)
        // Self-heal installs that synced before the Slice-4 FTS gap was fixed: index any
        // non-deleted item still missing from items_fts. Idempotent, so it's safe every start.
        appScope.launch { ftsIndexer.backfillMissing() }
        // Self-heal items whose enrichment enqueue() call never ran (process killed between the
        // Room write and the enqueue, e.g. a force-stop racing a share). See EnrichmentScheduler.
        appScope.launch {
            val stuck = database.itemDao().getPendingItemIds()
            EnrichmentScheduler.reconcilePending(this@AnikiApplication, stuck)
        }
    }
}
