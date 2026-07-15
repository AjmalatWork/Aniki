package com.aniki.anikiai.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aniki.anikiai.AnikiApplication
import com.aniki.anikiai.sync.SyncWorker
import com.aniki.anikiai.ui.theme.AnikiTheme
import com.aniki.anikiai.work.EnrichmentScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.trim()

        if (sharedText.isNullOrEmpty()) {
            finish()
            return
        }

        val classified = ShareContentClassifier.classify(sharedText)
        val repository = (application as AnikiApplication).repository

        // Deliberately blocking instead of lifecycleScope: this activity's only job is the
        // write, and lifecycleScope would be cancelled if the system tears this transparent
        // activity down before the coroutine resumes, risking data loss right after share.
        val savedItem = runBlocking(Dispatchers.IO) {
            repository.saveSharedContent(
                type = classified.type,
                sourceUrl = classified.sourceUrl,
                normalizedUrl = classified.normalizedUrl,
                title = classified.title,
                bodyText = classified.bodyText
            )
        }
        EnrichmentScheduler.enqueue(applicationContext, savedItem.id)
        SyncWorker.enqueueOneTime(applicationContext)

        // The "stamp moment" confirmation sheet (replaces the old Toast) —
        // purely visual; the save above has already committed.
        setContent {
            // darkGround: the sheet floats on a dark scrim, so status icons go light.
            AnikiTheme(darkGround = true) {
                ShareConfirmationSheet(
                    itemType = classified.type,
                    itemTitle = classified.title,
                    onDone = { finish() }
                )
            }
        }
    }
}
