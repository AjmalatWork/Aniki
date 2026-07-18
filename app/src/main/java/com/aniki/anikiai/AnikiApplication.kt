package com.aniki.anikiai

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.aniki.anikiai.data.db.AnikiDatabase
import com.aniki.anikiai.data.db.FtsIndexer
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.data.repository.SyncRepository
import com.aniki.anikiai.sync.SyncCursorStore
import com.aniki.anikiai.sync.SyncWorker
import com.aniki.anikiai.ui.feed.FeedSessionState
import com.aniki.anikiai.work.EnrichmentScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class AnikiApplication : Application(), SingletonImageLoader.Factory {

    private val database by lazy { AnikiDatabase.getInstance(this) }
    private val ftsIndexer by lazy { FtsIndexer(database.itemDao()) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository by lazy { ItemRepository(database.itemDao(), ftsIndexer, database) }
    val syncRepository by lazy { SyncRepository(database.itemDao(), ftsIndexer) }
    val syncCursorStore by lazy { SyncCursorStore(this) }

    // M1 (maintainability audit): FeedViewModel's process-scoped session state (frozen feed
    // order, last-settled card, swipe-hint flags), previously anonymous companion-object statics
    // -- now a real, single app-lifetime instance threaded explicitly through
    // AppRoot -> AnikiNavHost -> FeedScreen -> FeedViewModel's constructor, same as
    // repository/syncRepository above. See FeedSessionState's own doc for the full rationale.
    val feedSessionState by lazy { FeedSessionState() }

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

    // Coil3's automatic ServiceLoader-based discovery of coil-network-okhttp isn't reliable on
    // Android, so the network fetcher is wired explicitly here (the documented pattern for
    // Android integration) rather than left to auto-registration.
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()
    }
}
