package com.aniki.anikiai.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.aniki.anikiai.AnikiApplication
import com.aniki.anikiai.data.db.ItemType
import com.aniki.anikiai.data.remote.EnrichEntitiesDto
import com.aniki.anikiai.data.remote.EnrichErrorDto
import com.aniki.anikiai.data.remote.EnrichRequestDto
import com.aniki.anikiai.data.remote.NetworkClient
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.sync.SyncWorker
import com.aniki.anikiai.util.EnrichmentErrorCode
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

private const val MAX_ATTEMPTS = 5

// A note whose trimmed body is shorter than this has nothing for Gemini to meaningfully summarize
// or tag -- e.g. an accidental blank save, or a stray one-word tap. Deliberately low: the goal is
// filtering near-empty content, not second-guessing genuinely short intentional notes ("buy milk"
// is 8 chars and should still enrich normally).
private const val MIN_NOTE_BODY_LENGTH = 5

private val errorJson = Json { ignoreUnknownKeys = true }

class EnrichmentWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return@withContext WorkResult.failure()
        val repository = (applicationContext as AnikiApplication).repository
        val item = repository.getItemById(itemId) ?: return@withContext WorkResult.success()

        if (item.type == ItemType.NOTE && (item.bodyText?.trim()?.length ?: 0) < MIN_NOTE_BODY_LENGTH) {
            repository.markEnrichmentSkipped(itemId)
            SyncWorker.enqueueOneTime(applicationContext)
            Timber.i("enrichment skipped (note too short) itemId=%s", itemId)
            return@withContext WorkResult.success()
        }

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
            // No server response at all -- nothing to classify beyond the existing generic message
            // (distinguishing timeout vs. other network failure is explicitly out of scope here).
            return@withContext giveUpOrRetry(repository, itemId, errorCode = null, errorMessage = null)
        }

        val body = response.body()
        if (!response.isSuccessful || body == null) {
            val parsedError = parseErrorBody(response.errorBody()?.string())
            Timber.w(
                "enrichment failed (http %d, code=%s) itemId=%s attempt=%d",
                response.code(), parsedError?.code, itemId, runAttemptCount + 1
            )
            return@withContext giveUpOrRetry(repository, itemId, parsedError?.code, parsedError?.error)
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

    /** Best-effort decode of the server's `{error, code}` body -- null on anything unparseable
     *  (e.g. a proxy/500 page instead of real JSON), which just falls back to the generic message. */
    private fun parseErrorBody(raw: String?): EnrichErrorDto? {
        if (raw.isNullOrBlank()) return null
        return try {
            errorJson.decodeFromString(EnrichErrorDto.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun giveUpOrRetry(
        repository: ItemRepository,
        itemId: String,
        errorCode: String?,
        errorMessage: String?
    ): WorkResult {
        repository.markNeedsAttention(itemId, errorCode, errorMessage)
        SyncWorker.enqueueOneTime(applicationContext)

        // Quota only resets at UTC midnight server-side -- the normal exponential backoff (30s/
        // 60s/120s/...) would just burn all 5 attempts in minutes and land on this same failure.
        // Stop the rapid-retry loop here and let EnrichmentScheduler come back once, timed for just
        // after the reset, instead of WorkManager's own backoff continuing on top of this.
        if (errorCode == EnrichmentErrorCode.QUOTA_EXCEEDED) {
            EnrichmentScheduler.enqueueAfterQuotaReset(applicationContext, itemId)
            Timber.w("enrichment quota exceeded itemId=%s -- deferred retry scheduled", itemId)
            return WorkResult.failure()
        }

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
