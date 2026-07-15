package com.aniki.anikiai.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.syncDataStore by preferencesDataStore(name = "sync_prefs")
private val CURSOR_KEY = longPreferencesKey("cursor")

/** The replication cursor (max seq fully accounted for) — lives in DataStore, not the items table. */
class SyncCursorStore(private val context: Context) {

    suspend fun getCursor(): Long = context.syncDataStore.data.first()[CURSOR_KEY] ?: 0L

    suspend fun setCursor(value: Long) {
        context.syncDataStore.edit { prefs -> prefs[CURSOR_KEY] = value }
    }

    /** Called on sign-out / guest reset so the next sign-in starts a clean pull from 0. */
    suspend fun reset() {
        context.syncDataStore.edit { prefs -> prefs.remove(CURSOR_KEY) }
    }
}
