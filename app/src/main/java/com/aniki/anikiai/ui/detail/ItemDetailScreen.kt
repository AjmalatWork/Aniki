package com.aniki.anikiai.ui.detail

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aniki.anikiai.data.db.ItemStatus
import com.aniki.anikiai.data.db.ItemType
import com.aniki.anikiai.data.db.ItemWithTags
import com.aniki.anikiai.data.db.TagEntity
import com.aniki.anikiai.data.repository.ItemRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    itemId: String,
    repository: ItemRepository,
    onBack: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: ItemDetailViewModel = viewModel(
        key = itemId,
        factory = viewModelFactory {
            initializer { ItemDetailViewModel(repository, appContext, itemId) }
        }
    )
    val itemWithTags by viewModel.item.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    itemWithTags?.let { current ->
                        IconButton(onClick = { viewModel.toggleStar(!current.item.isStarred) }) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = if (current.item.isStarred) "Unstar" else "Star",
                                tint = if (current.item.isStarred) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { padding ->
        val current = itemWithTags
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            ItemDetailContent(
                itemWithTags = current,
                modifier = Modifier.fillMaxSize().padding(padding),
                onSaveSummary = viewModel::saveSummary,
                onAddTag = viewModel::addTag,
                onRemoveTag = viewModel::removeTag,
                onRetry = viewModel::retry
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this item?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete(onBack)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ItemDetailContent(
    itemWithTags: ItemWithTags,
    modifier: Modifier = Modifier,
    onSaveSummary: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onRetry: () -> Unit
) {
    val item = itemWithTags.item
    val context = LocalContext.current

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        HeroPlaceholder(item.type, item.category)
        Spacer(Modifier.height(12.dp))

        Text(text = item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = DateUtils.getRelativeTimeSpanString(
                item.createdAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (item.type != ItemType.NOTE && item.sourceUrl != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                }
            }) {
                Text("Open source")
            }
        }

        Spacer(Modifier.height(20.dp))

        when (item.status) {
            ItemStatus.ENRICHED -> {
                SectionLabel("Summary")
                EditableSummary(summary = item.summary.orEmpty(), onSave = onSaveSummary)
            }
            ItemStatus.NEEDS_ATTENTION -> {
                ProcessingStatusCard(
                    message = "Couldn't process this item.",
                    actionLabel = "Retry",
                    onAction = onRetry
                )
            }
            else -> {
                ProcessingStatusCard(message = "Still processing…", actionLabel = null, onAction = null)
            }
        }

        if (item.type == ItemType.NOTE && !item.bodyText.isNullOrBlank()) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Note")
            Text(text = item.bodyText, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Tags")
        TagsEditor(tags = itemWithTags.tags, onAddTag = onAddTag, onRemoveTag = onRemoveTag)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HeroPlaceholder(type: String, category: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().height(120.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = typeEmoji(type), style = MaterialTheme.typography.displaySmall)
            if (!category.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ProcessingStatusCard(message: String, actionLabel: String?, onAction: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun EditableSummary(summary: String, onSave: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(summary) { mutableStateOf(summary) }

    if (editing) {
        Column {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onSave(draft)
                    editing = false
                }) { Text("Save") }
                TextButton(onClick = {
                    draft = summary
                    editing = false
                }) { Text("Cancel") }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = summary.ifBlank { "No summary yet." },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { editing = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit summary")
            }
        }
    }
}

@Composable
private fun TagsEditor(tags: List<TagEntity>, onAddTag: (String) -> Unit, onRemoveTag: (String) -> Unit) {
    var showAddField by remember { mutableStateOf(false) }
    var draftLabel by remember { mutableStateOf("") }

    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        tags.forEach { tag ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                    Text(
                        text = tag.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    IconButton(onClick = { onRemoveTag(tag.id) }, modifier = Modifier.width(28.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove ${tag.label}",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.width(16.dp)
                        )
                    }
                }
            }
        }
        IconButton(onClick = { showAddField = true }) {
            Icon(Icons.Default.Add, contentDescription = "Add tag")
        }
    }

    if (showAddField) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draftLabel,
                onValueChange = { draftLabel = it },
                label = { Text("New tag") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                onAddTag(draftLabel)
                draftLabel = ""
                showAddField = false
            }) { Text("Add") }
        }
    }
}

private fun typeEmoji(type: String): String = when (type) {
    ItemType.YOUTUBE_VIDEO -> "▶️"
    ItemType.WEB_ARTICLE -> "🔗"
    ItemType.NOTE -> "📝"
    else -> "•"
}
