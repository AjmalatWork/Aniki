package com.aniki.anikiai.ui.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniki.anikiai.share.OnboardingDemoContent
import com.aniki.anikiai.ui.theme.AnikiTheme
import com.aniki.anikiai.ui.theme.Ink
import com.aniki.anikiai.ui.theme.Kon
import com.aniki.anikiai.ui.theme.OnDarkBody
import com.aniki.anikiai.ui.theme.OnDarkMuted
import com.aniki.anikiai.ui.theme.Paper
import com.aniki.anikiai.ui.theme.PlexMono
import com.aniki.anikiai.ui.theme.SealMark
import com.aniki.anikiai.ui.theme.weightedShadow

private enum class ShareTipStep { EXPLAIN, COACHMARK }

/**
 * Onboarding's "how sharing works" step (fires right after the sign-in/guest screen, before
 * MAIN -- see AppRoot's RootState.SHARE_TIP). We can't overlay anything on the OS share sheet
 * itself (it's system-owned UI), so this primes the user with an in-app coach-mark immediately
 * before it opens, then fires a real ACTION_SEND with clearly-labeled demo content so Aniki shows
 * up as a genuine share-sheet target.
 *
 * Per the final-copy revision, there is no third "result" screen and no branching on whether the
 * demo share was actually received: tapping "Open share menu" fires the intent, and whatever
 * happens next in the OS share sheet (pinned+tapped, tapped, shared to a different app, backed
 * out, or no targets at all), [onDone] fires once control returns to us, landing the user in the
 * normal post-onboarding flow (the Feed). If the demo item *was* received, Feed gives it a
 * one-time landing animation on its own (see FeedScreen) -- that's a Feed concern, not an
 * onboarding-flow concern, so this screen doesn't need to know or care which outcome happened.
 *
 * Visually mirrors [OnboardingScreen]: same dark-ground AnikiTheme, same Kon/Ink radial glow,
 * same filled [SealMark], same Paper primary button / PlexMono skip-link styling.
 */
@Composable
fun ShareTipScreen(onDone: () -> Unit) {
    var step by remember { mutableStateOf(ShareTipStep.EXPLAIN) }

    // Fires when control returns to us after the share sheet closes, however that happened --
    // we don't need (or want) to know the outcome; every path leads straight to onDone().
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onDone()
    }

    fun openShareSheet() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, OnboardingDemoContent.shareText)
        }
        // No share targets / chooser fails to open for some reason -> skip gracefully, don't crash.
        runCatching { launcher.launch(Intent.createChooser(sendIntent, null)) }
            .onFailure { onDone() }
    }

    AnikiTheme(darkGround = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Kon, Ink),
                        center = Offset(0.5f, 0.12f),
                        radius = Float.POSITIVE_INFINITY
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Fixed (not weighted) so the seal's position never shifts between steps, no
                // matter how much copy renders below it.
                Spacer(Modifier.height(150.dp))

                SealMark(size = 80.dp, filled = true)

                Spacer(Modifier.height(30.dp))

                StepCopy(step)

                Spacer(Modifier.weight(1f))

                StepActions(step, onTryIt = { step = ShareTipStep.COACHMARK }, onOpenShareSheet = ::openShareSheet, onDone = onDone)

                Spacer(Modifier.height(26.dp))
            }
        }
    }
}

@Composable
private fun StepCopy(step: ShareTipStep) {
    when (step) {
        ShareTipStep.EXPLAIN -> {
            Text(
                text = "Share into Aniki",
                style = MaterialTheme.typography.headlineMedium,
                color = Paper,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Tap Share in any app — an article, a video, a note — and pick Aniki. " +
                    "It reads, tags, and files it for you.",
                style = MaterialTheme.typography.bodyLarge,
                color = OnDarkBody,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp)
            )
        }

        ShareTipStep.COACHMARK -> {
            Text(
                text = "Your share menu is about to open",
                style = MaterialTheme.typography.headlineSmall,
                color = Paper,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "You'll see Aniki in the list. Long-press it if you'd like to pin it to the top.",
                style = MaterialTheme.typography.bodyLarge,
                color = OnDarkBody,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp)
            )
        }
    }
}

@Composable
private fun StepActions(
    step: ShareTipStep,
    onTryIt: () -> Unit,
    onOpenShareSheet: () -> Unit,
    onDone: () -> Unit
) {
    when (step) {
        ShareTipStep.EXPLAIN -> {
            PrimaryButton("Try it", onClick = onTryIt)
            Spacer(Modifier.height(11.dp))
            SkipButton(onClick = onDone)
        }

        ShareTipStep.COACHMARK -> {
            PrimaryButton("Open share menu", onClick = onOpenShareSheet)
            Spacer(Modifier.height(11.dp))
            SkipButton(onClick = onDone)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Paper, contentColor = Ink),
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = Modifier
            .fillMaxWidth()
            .weightedShadow(RoundedCornerShape(13.dp), ambient = 12.dp, contact = 4.dp)
            .height(50.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp))
    }
}

@Composable
private fun SkipButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            "Skip",
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = PlexMono,
                fontSize = 12.sp
            ),
            color = OnDarkMuted
        )
    }
}
