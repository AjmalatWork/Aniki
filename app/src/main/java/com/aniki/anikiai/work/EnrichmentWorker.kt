package com.aniki.anikiai.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.aniki.anikiai.AnikiApplication
import com.aniki.anikiai.data.remote.EnrichEntitiesDto
import com.aniki.anikiai.data.remote.EnrichRequestDto
import com.aniki.anikiai.data.remote.NetworkClient
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.sync.SyncWorker
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

private const val MAX_ATTEMPTS = 5

class EnrichmentWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return@withContext WorkResult.failure()
        val repository = (applicationContext as AnikiApplication).repository
        val item = repository.getItemById(itemId) ?: return@withContext WorkResult.success()

        val request = EnrichRequestDto(
            id = item.id,
            type = item.type,
            sourceUrl = item.sourceUrl,
            bodyText = item.bodyText
        )

        val start = System.currentTimeMillis()
        val response = try {
            NetworkClient.api.enrich(request)
        } catch (e: Exception) {
            Timber.w(e, "enrichment failed (network) itemId=%s attempt=%d", itemId, runAttemptCount + 1)
            return@withContext giveUpOrRetry(repository, itemId)
        }

        val body = response.body()
        if (!response.isSuccessful || body == null) {
            Timber.w(
                "enrichment failed (http %d) itemId=%s attempt=%d",
                response.code(), itemId, runAttemptCount + 1
            )
            return@withContext giveUpOrRetry(repository, itemId)
        }

        repository.applyEnrichment(
            itemId = itemId,
            title = body.title,
            summary = body.summary,
            category = body.category,
            thumbnailUrl = body.thumbnailUrl,
            entitiesJson = Json.encodeToString(EnrichEntitiesDto.serializer(), body.entities),
            eventDate = parseEventDate(body.eventDate),
            tags = body.tags
        )
        SyncWorker.enqueueOneTime(applicationContext)
        Timber.i("enrichment succeeded itemId=%s latencyMs=%d", itemId, System.currentTimeMillis() - start)
        WorkResult.success()
    }

    private suspend fun giveUpOrRetry(repository: ItemRepository, itemId: String): WorkResult {
        repository.markNeedsAttention(itemId)
        SyncWorker.enqueueOneTime(applicationContext)
        val exhausted = runAttemptCount + 1 >= MAX_ATTEMPTS
        if (exhausted) Timber.w("enrichment gave up itemId=%s after %d attempts", itemId, runAttemptCount + 1)
        return if (exhausted) WorkResult.failure() else WorkResult.retry()
    }

    private fun parseEventDate(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val KEY_ITEM_ID = "itemId"
    }
}
