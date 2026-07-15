package com.aniki.anikiai.ui.detail

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aniki.anikiai.data.db.ItemStatus
import com.aniki.anikiai.data.db.ItemType
import com.aniki.anikiai.data.db.ItemWithTags
import com.aniki.anikiai.data.db.TagEntity
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.ui.theme.Ink
import com.aniki.anikiai.ui.theme.InkLine
import com.aniki.anikiai.ui.theme.Kon
import com.aniki.anikiai.ui.theme.Matcha
import com.aniki.anikiai.ui.theme.MatchaInk
import com.aniki.anikiai.ui.theme.MatchaWash
import com.aniki.anikiai.ui.theme.Muted
import com.aniki.anikiai.ui.theme.Paper
import com.aniki.anikiai.ui.theme.Paper2
import com.aniki.anikiai.ui.theme.PulseDot
import com.aniki.anikiai.ui.theme.Seal
import com.aniki.anikiai.ui.theme.SealMark
import com.aniki.anikiai.ui.theme.typeThumbBrush
import com.aniki.anikiai.ui.theme.weightedShadow

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

    Scaffold(containerColor = Paper) { padding ->
        val current = itemWithTags
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Seal)
            }
        } else {
            ItemDetailContent(
                itemWithTags = current,
                modifier = Modifier.fillMaxSize(),
                topInset = padding.calculateTopPadding(),
                bottomInset = padding.calculateBottomPadding(),
                onBack = onBack,
                onToggleStar = viewModel::toggleStar,
                onDeleteRequest = { showDeleteConfirm = true },
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
                }) { Text("Delete", color = Seal) }
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
    topInset: androidx.compose.ui.unit.Dp,
    bottomInset: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onToggleStar: (Boolean) -> Unit,
    onDeleteRequest: () -> Unit,
    onSaveSummary: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onRetry: () -> Unit
) {
    val item = itemWithTags.item
    val context = LocalContext.current
    val hasSource = item.type != ItemType.NOTE && item.sourceUrl != null

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Hero(item.type, item.category, topInset, onBack, onToggleStar, item.isStarred, item.status)

            Column(modifier = Modifier.padding(18.dp)) {
                    if (!item.category.isNullOrBlank()) {
                        Text(
                            text = item.category!!.uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                            color = Seal
                        )
                        Spacer(Modifier.height(9.dp))
                    }
                    Text(text = item.title, style = MaterialTheme.typography.headlineSmall, color = Ink)
                    Spacer(Modifier.height(10.dp))

                    MetaRow(item.type, item.sourceUrl, item.createdAt)
                    Spacer(Modifier.height(20.dp))

                    when (item.status) {
                        ItemStatus.ENRICHED -> {
                            SummaryBlock(summary = item.summary.orEmpty(), onSave = onSaveSummary)
                        }
                        ItemStatus.NEEDS_ATTENTION -> {
                            ProcessingStatusCard(
                                message = "Couldn't process this item.",
                                actionLabel = "Retry",
                                onAction = onRetry,
                                isError = true
                            )
                        }
                        else -> {
                            ProcessingStatusCard(message = "Aniki is reading this…", actionLabel = null, onAction = null)
                        }
                    }

                    if (item.type == ItemType.NOTE && !item.bodyText.isNullOrBlank()) {
                        Spacer(Modifier.height(18.dp))
                        SectionLabel("Note")
                        Text(text = item.bodyText, style = MaterialTheme.typography.bodyMedium, color = Kon)
                    }

                    Spacer(Modifier.height(18.dp))
                    SectionLabel("Tags")
                    TagsEditor(tags = itemWithTags.tags, onAddTag = onAddTag, onRemoveTag = onRemoveTag)
                    Spacer(Modifier.height(90.dp)) // clears the floating action bar
                }
        }

        // Fixed action bar, fading up from the parchment ground.
        ActionBar(
            hasSource = hasSource,
            onOpenSource = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                }
            },
            isStarred = item.isStarred,
            onToggleStar = { onToggleStar(!item.isStarred) },
            onDelete = onDeleteRequest,
            bottomInset = bottomInset,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun Hero(
    type: String,
    category: String?,
    topInset: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onToggleStar: (Boolean) -> Unit,
    isStarred: Boolean,
    status: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(172.dp)
            .background(typeThumbBrush(type))
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(top = topInset + 12.dp, start = 16.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Ink.copy(alpha = 0.45f))
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Paper)
        }

        if (status == ItemStatus.ENRICHED) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Paper.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                SealMark(size = 26.dp)
            }
        }
    }
}

@Composable
private fun MetaRow(type: String, sourceUrl: String?, createdAt: Long) {
    val host = remember(sourceUrl) { sourceUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() } }
    val saved = DateUtils.getRelativeTimeSpanString(
        createdAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (type != ItemType.NOTE && host != null) {
            Text("$host", style = MaterialTheme.typography.labelMedium, color = Kon)
            Text("·", style = MaterialTheme.typography.labelMedium, color = Kon)
        }
        Text("saved $saved", style = MaterialTheme.typography.labelMedium, color = Kon)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
        color = Muted
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ProcessingStatusCard(
    message: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    isError: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Paper2)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isError) {
                PulseDot(size = 7.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) Seal else Kon
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, color = Kon) }
        }
    }
}

@Composable
private fun SummaryBlock(summary: String, onSave: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(summary) { mutableStateOf(summary) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Paper2)
            .padding(14.dp)
    ) {
        Text(
            text = "ANIKI'S SUMMARY",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            color = Matcha
        )
        Spacer(Modifier.height(7.dp))

        if (editing) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Kon),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = InkLine,
                    unfocusedIndicatorColor = InkLine,
                    cursorColor = Seal
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(draft); editing = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Kon, contentColor = Paper)
                ) { Text("Save") }
                TextButton(onClick = { draft = summary; editing = false }) {
                    Text("Cancel", color = Kon)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = summary.ifBlank { "No summary yet." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Kon,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { editing = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit summary", tint = Muted)
                }
            }
        }
    }
}

@Composable
private fun TagsEditor(tags: List<TagEntity>, onAddTag: (String) -> Unit, onRemoveTag: (String) -> Unit) {
    var showAddField by remember { mutableStateOf(false) }
    var draftLabel by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tags.forEach { tag ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MatchaWash)
                    .padding(start = 10.dp)
            ) {
                Text(text = tag.label, style = MaterialTheme.typography.labelSmall, color = MatchaInk)
                IconButton(onClick = { onRemoveTag(tag.id) }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove ${tag.label}",
                        tint = MatchaInk,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        val dashShape = RoundedCornerShape(6.dp)
        Text(
            text = "+ add",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            modifier = Modifier
                .clip(dashShape)
                .border(1.dp, InkLine, dashShape)
                .clickable { showAddField = true }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }

    if (showAddField) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = draftLabel,
                onValueChange = { draftLabel = it },
                placeholder = { Text("New tag") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Kon),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Paper2,
                    unfocusedContainerColor = Paper2,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Seal
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                onAddTag(draftLabel)
                draftLabel = ""
                showAddField = false
            }) { Text("Add", color = Kon) }
        }
    }
}

@Composable
private fun ActionBar(
    hasSource: Boolean,
    onOpenSource: () -> Unit,
    isStarred: Boolean,
    onToggleStar: () -> Unit,
    onDelete: () -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to Paper,
                    1f to Paper
                )
            )
            .padding(horizontal = 18.dp)
            .padding(top = 16.dp, bottom = 16.dp + bottomInset),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (hasSource) {
            Button(
                onClick = onOpenSource,
                colors = ButtonDefaults.buttonColors(containerColor = Kon, contentColor = Paper),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .weightedShadow(RoundedCornerShape(12.dp), ambient = 10.dp, contact = 3.dp)
                    .height(46.dp)
            ) {
                Text("Open source", style = MaterialTheme.typography.labelLarge)
            }
        }
        ActionIconButton(
            icon = Icons.Default.Star,
            contentDescription = if (isStarred) "Unstar" else "Star",
            tint = if (isStarred) Seal else Kon,
            onClick = onToggleStar
        )
        ActionIconButton(
            icon = Icons.Default.Delete,
            contentDescription = "Delete",
            tint = Kon,
            onClick = onDelete
        )
    }
}

@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .weightedShadow(RoundedCornerShape(12.dp), ambient = 8.dp, contact = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Paper2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}
