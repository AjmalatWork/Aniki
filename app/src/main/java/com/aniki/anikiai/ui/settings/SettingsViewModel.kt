package com.aniki.anikiai.ui.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import com.aniki.anikiai.data.remote.AnikiApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

sealed interface ExportState {
    data object Idle : ExportState
    data object InProgress : ExportState
    data class Ready(val fileUri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}

sealed interface DeleteState {
    data object Idle : DeleteState
    data object InProgress : DeleteState
    data class Failed(val message: String) : DeleteState
    // Success isn't a state here — the caller reacts by running the same local-cleanup +
    // navigate-to-onboarding sequence sign-out already uses (see SettingsScreen/AppRoot).
}

class SettingsViewModel(
    private val api: AnikiApi,
    private val appContext: Context
) : ViewModel() {

    suspend fun exportData(): ExportState {
        return try {
            val response = api.exportAccount()
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                return ExportState.Failed("Export failed: HTTP ${response.code()}")
            }
            val json = body.string()
            val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "aniki-export-$stamp.json")
            file.writeText(json)
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
            Timber.i("account export ready: bytes=%d", json.length)
            ExportState.Ready(uri)
        } catch (e: Exception) {
            Timber.w(e, "account export failed")
            ExportState.Failed(e.message ?: "Export failed")
        }
    }

    suspend fun deleteAccount(): DeleteState {
        return try {
            val response = api.deleteAccount()
            if (!response.isSuccessful) {
                return DeleteState.Failed("Delete failed: HTTP ${response.code()}")
            }
            Timber.i("account deleted server-side")
            DeleteState.Idle // caller proceeds with local cleanup on success
        } catch (e: Exception) {
            Timber.w(e, "account deletion failed")
            DeleteState.Failed(e.message ?: "Delete failed")
        }
    }
}
