package com.aniki.anikiai.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aniki.anikiai.data.remote.AnikiApi
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    api: AnikiApi,
    isSignedIn: Boolean,
    userEmail: String?,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onAccountDeleted: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(api, appContext) }
        }
    )
    val scope = rememberCoroutineScope()

    var exportState by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    var deleteState by remember { mutableStateOf<DeleteState>(DeleteState.Idle) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Fire the system share sheet once export finishes, then go back to Idle so it doesn't refire.
    LaunchedEffect(exportState) {
        val state = exportState
        if (state is ExportState.Ready) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, state.fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export Aniki data"))
            exportState = ExportState.Idle
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isSignedIn) userEmail ?: "Signed in" else "Using Aniki as a guest",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text("Your data", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isSignedIn) {
                    "Download a JSON copy of everything Aniki has saved for your account: items, tags, and engagement history."
                } else {
                    "Sign in to enable data export and account deletion — guest data isn't stored on the server."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    exportState = ExportState.InProgress
                    scope.launch { exportState = viewModel.exportData() }
                },
                enabled = isSignedIn && exportState !is ExportState.InProgress,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (exportState is ExportState.InProgress) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                } else {
                    Text("Export my data")
                }
            }
            if (exportState is ExportState.Failed) {
                Text(
                    text = (exportState as ExportState.Failed).message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text("Danger zone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Permanently deletes your account and everything in it — items, tags, and engagement history, on this device and the server. This cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                enabled = isSignedIn && deleteState !is DeleteState.InProgress,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete my account")
            }
            if (deleteState is DeleteState.Failed) {
                Text(
                    text = (deleteState as DeleteState.Failed).message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))
            Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text(if (isSignedIn) "Sign out" else "Exit guest mode")
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "This permanently deletes your account and everything in it — items, tags, and " +
                        "engagement history — on this device and the server. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    deleteState = DeleteState.InProgress
                    scope.launch {
                        val result = viewModel.deleteAccount()
                        if (result is DeleteState.Failed) {
                            deleteState = result
                        } else {
                            onAccountDeleted()
                        }
                    }
                }) { Text("Delete permanently") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
