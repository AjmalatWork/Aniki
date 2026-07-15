package com.aniki.anikiai.auth

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.authDataStore by preferencesDataStore(name = "auth_prefs")
private val GUEST_MODE_KEY = booleanPreferencesKey("guest_mode_chosen")
private val MIGRATED_KEY = booleanPreferencesKey("has_completed_guest_migration")

class AuthPreferences(private val context: Context) {

    suspend fun isGuestModeChosen(): Boolean = context.authDataStore.data.first()[GUEST_MODE_KEY] ?: false

    suspend fun setGuestModeChosen() {
        context.authDataStore.edit { it[GUEST_MODE_KEY] = true }
    }

    suspend fun hasCompletedGuestMigration(): Boolean =
        context.authDataStore.data.first()[MIGRATED_KEY] ?: false

    suspend fun setGuestMigrationCompleted() {
        context.authDataStore.edit { it[MIGRATED_KEY] = true }
    }

    /** Called on sign-out so a fresh sign-in (possibly a different account) starts clean. */
    suspend fun reset() {
        context.authDataStore.edit {
            it.remove(GUEST_MODE_KEY)
            it.remove(MIGRATED_KEY)
        }
    }
}
