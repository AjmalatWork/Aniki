package com.aniki.anikiai.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aniki.anikiai.AnikiApplication
import com.aniki.anikiai.data.db.ItemType
import com.aniki.anikiai.sync.SyncWorker
import com.aniki.anikiai.ui.theme.AnikiTheme
import com.aniki.anikiai.work.EnrichmentScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawSharedText = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.trim()

        if (rawSharedText.isNullOrEmpty()) {
            finish()
            return
        }

        // The onboarding "how sharing works" step (ShareTipScreen) fires a real ACTION_SEND
        // through this exact same path -- see OnboardingDemoContent for why. A demo share skips
        // ShareContentClassifier entirely and saves OnboardingDemoContent's hardcoded title/body
        // verbatim, never parsed back out of the shared text.
        val isDemo = OnboardingDemoContent.isDemoShare(rawSharedText)
        val repository = (application as AnikiApplication).repository

        // Deliberately blocking instead of lifecycleScope: this activity's only job is the
        // write, and lifecycleScope would be cancelled if the system tears this transparent
        // activity down before the coroutine resumes, risking data loss right after share.
        val (savedItem, displayType, displayTitle) = runBlocking(Dispatchers.IO) {
            if (isDemo) {
                val item = repository.saveSharedContent(
                    type = ItemType.NOTE,
                    sourceUrl = null,
                    normalizedUrl = null,
                    title = OnboardingDemoContent.title,
                    bodyText = OnboardingDemoContent.body,
                    isDemo = true
                )
                Triple(item, ItemType.NOTE, OnboardingDemoContent.title)
            } else {
                val classified = ShareContentClassifier.classify(rawSharedText)
                val item = repository.saveSharedContent(
                    type = classified.type,
                    sourceUrl = classified.sourceUrl,
                    normalizedUrl = classified.normalizedUrl,
                    title = classified.title,
                    bodyText = classified.bodyText
                )
                Triple(item, classified.type, classified.title)
            }
        }
        // The demo item is never enriched or synced -- it's not real content, and both would
        // otherwise happen on every app start via the pending-item/dirty-row reconciliation scans.
        if (!isDemo) {
            EnrichmentScheduler.enqueue(applicationContext, savedItem.id)
            SyncWorker.enqueueOneTime(applicationContext)
        }

        // The "stamp moment" confirmation sheet (replaces the old Toast) —
        // purely visual; the save above has already committed.
        setContent {
            // darkGround: the sheet floats on a dark scrim, so status icons go light.
            AnikiTheme(darkGround = true) {
                ShareConfirmationSheet(
                    itemType = displayType,
                    itemTitle = displayTitle,
                    onDone = { finish() }
                )
            }
        }
    }
}
