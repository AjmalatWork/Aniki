package com.aniki.anikiai.ui.trash

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aniki.anikiai.data.db.ItemEntity
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.ui.theme.Ink
import com.aniki.anikiai.ui.theme.InkLine
import com.aniki.anikiai.ui.theme.Muted
import com.aniki.anikiai.ui.theme.Paper
import com.aniki.anikiai.ui.theme.Seal
import com.aniki.anikiai.util.isOnline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    repository: ItemRepository,
    onBack: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: TrashViewModel = viewModel(
        factory = viewModelFactory {
            initializer { TrashViewModel(repository, appContext) }
        }
    )
    val items by viewModel.trashedItems.collectAsState()
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    // Snapshotted at the moment the dialog opens (not re-checked at confirm time) -- see
    // ItemRepository.permanentlyDeleteItem/emptyTrash's doc for the underlying offline/unsynced-
    // tombstone risk this warns about. Same risk applies to both single-item and bulk delete.
    var offlineWarning by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("Trash", style = MaterialTheme.typography.headlineSmall, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = {
                            offlineWarning = !isOnline(appContext)
                            confirmEmptyTrash = true
                        }) {
                            Text("Empty", color = Seal)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nothing in Trash.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Text(
                    text = "Items are permanently deleted 30 days after being trashed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { item ->
                        TrashRow(
                            item = item,
                            onRestore = { viewModel.restore(item.id) },
                            onDeleteForever = {
                                offlineWarning = !isOnline(appContext)
                                confirmDeleteId = item.id
                            }
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(InkLine))
                    }
                }
            }
        }
    }

    val offlineNotice = "You're offline. This will delete the item now, but it could reappear later if " +
        "you use Aniki on another device, since this device can't confirm the deletion with the server yet."

    confirmDeleteId?.let { itemId ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete forever?") },
            text = {
                Column {
                    Text("This can't be undone.")
                    if (offlineWarning) {
                        Spacer(Modifier.height(8.dp))
                        Text(offlineNotice, style = MaterialTheme.typography.bodySmall, color = Seal)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteForever(itemId)
                    confirmDeleteId = null
                }) { Text("Delete forever", color = Seal) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text("Empty Trash?") },
            text = {
                Column {
                    Text("Permanently deletes all ${items.size} item(s) in Trash. This can't be undone.")
                    if (offlineWarning) {
                        Spacer(Modifier.height(8.dp))
                        Text(offlineNotice, style = MaterialTheme.typography.bodySmall, color = Seal)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.emptyTrash()
                    confirmEmptyTrash = false
                }) { Text("Empty Trash", color = Seal) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyTrash = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TrashRow(item: ItemEntity, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    val deletedAgo = remember(item.deletedAt) {
        item.deletedAt?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }.orEmpty()
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "deleted $deletedAgo",
                style = MaterialTheme.typography.labelSmall,
                color = Muted
            )
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onRestore) {
            Text("Restore", style = MaterialTheme.typography.labelLarge, color = Ink)
        }
        IconButton(onClick = onDeleteForever) {
            Icon(Icons.Default.Delete, contentDescription = "Delete forever", tint = Seal)
        }
    }
}
