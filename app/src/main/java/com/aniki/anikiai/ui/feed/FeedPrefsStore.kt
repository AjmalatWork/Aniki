package com.aniki.anikiai.ui.feed

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.feedPrefsDataStore by preferencesDataStore(name = "feed_prefs")
private val FIRST_OPEN_HINT_SHOWN_KEY = booleanPreferencesKey("first_open_hint_shown")

/** Persists whether the first-ever-Feed-open swipe hint has already played, so it never
 *  replays as "first run" again. Mirrors sync/SyncCursorStore's DataStore pattern. */
class FeedPrefsStore(private val context: Context) {

    suspend fun hasShownFirstOpenHint(): Boolean =
        context.feedPrefsDataStore.data.first()[FIRST_OPEN_HINT_SHOWN_KEY] ?: false

    suspend fun markFirstOpenHintShown() {
        context.feedPrefsDataStore.edit { prefs -> prefs[FIRST_OPEN_HINT_SHOWN_KEY] = true }
    }
}
