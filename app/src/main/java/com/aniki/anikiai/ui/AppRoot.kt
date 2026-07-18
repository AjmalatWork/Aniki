package com.aniki.anikiai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aniki.anikiai.AnikiApplication
import com.aniki.anikiai.auth.AuthManager
import com.aniki.anikiai.auth.AuthPreferences
import com.aniki.anikiai.data.remote.NetworkClient
import com.aniki.anikiai.sync.SyncWorker
import com.aniki.anikiai.ui.navigation.AnikiNavHost
import com.aniki.anikiai.ui.onboarding.OnboardingScreen
import com.aniki.anikiai.ui.onboarding.ShareTipScreen
import kotlinx.coroutines.launch

private enum class RootState { LOADING, ONBOARDING, SHARE_TIP, MAIN }

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val appContext = context.applicationContext as AnikiApplication
    val authManager = remember { AuthManager(appContext) }
    val authPreferences = remember { AuthPreferences(appContext) }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(RootState.LOADING) }

    LaunchedEffect(Unit) {
        val guestChosen = authPreferences.isGuestModeChosen()
        state = if (authManager.isSignedIn || guestChosen) RootState.MAIN else RootState.ONBOARDING
    }

    when (state) {
        RootState.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        RootState.ONBOARDING -> OnboardingScreen(
            onContinueAsGuest = {
                scope.launch {
                    authPreferences.setGuestModeChosen()
                    state = RootState.SHARE_TIP
                }
            },
            onSignedIn = {
                scope.launch {
                    // Guest -> account migration: only the first time this device signs in.
                    if (!authPreferences.hasCompletedGuestMigration()) {
                        appContext.syncRepository.markAllLocalRowsDirty()
                        authPreferences.setGuestMigrationCompleted()
                        SyncWorker.enqueueOneTime(appContext)
                    }
                    state = RootState.SHARE_TIP
                }
            }
        )

        // Only reachable via ONBOARDING above -- a cold start for an already-onboarded user
        // (signedIn || guestChosen, checked in the LaunchedEffect) always routes straight to
        // MAIN, so this step is inherently one-time per install without needing its own
        // "seen it" flag.
        RootState.SHARE_TIP -> ShareTipScreen(
            onDone = { state = RootState.MAIN }
        )

        RootState.MAIN -> AnikiNavHost(
            repository = appContext.repository,
            feedSessionState = appContext.feedSessionState,
            api = NetworkClient.api,
            isSignedIn = authManager.isSignedIn,
            userEmail = authManager.currentUser?.email,
            onSignOut = {
                scope.launch {
                    // Only a real account's local cache is safe to wipe here — that data is
                    // already on the server. A guest's local rows are the ONLY copy: clearing
                    // them on "Exit guest mode" would destroy data before the guest->account
                    // migration below ever gets a chance to push it on the next sign-in.
                    val wasSignedIn = authManager.isSignedIn
                    authManager.signOut()
                    if (wasSignedIn) {
                        appContext.syncRepository.clearLocalCache()
                    }
                    appContext.syncCursorStore.reset()
                    authPreferences.reset()
                    state = RootState.ONBOARDING
                }
            }
        )
    }
}
