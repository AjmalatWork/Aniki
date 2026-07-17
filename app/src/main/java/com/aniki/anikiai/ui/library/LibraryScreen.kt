package com.aniki.anikiai.ui.library

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import com.aniki.anikiai.ui.theme.MatchaInk
import com.aniki.anikiai.ui.theme.MatchaWash
import com.aniki.anikiai.ui.theme.Muted
import com.aniki.anikiai.ui.theme.Paper
import com.aniki.anikiai.ui.theme.Paper2
import com.aniki.anikiai.ui.theme.PulseDot
import com.aniki.anikiai.ui.theme.Seal
import com.aniki.anikiai.ui.theme.SealMark
import com.aniki.anikiai.ui.theme.ShimmerBox
import com.aniki.anikiai.ui.theme.TypeIcon
import com.aniki.anikiai.ui.theme.ItemThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: ItemRepository,
    onOpenItem: (String) -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { LibraryViewModel(repository, appContext) }
        }
    )
    val items by viewModel.items.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val availableTags by viewModel.availableTags.collectAsState()
    val selectedTagIds by viewModel.selectedTagIds.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val starredOnly by viewModel.starredOnly.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val hasActiveFilter by viewModel.hasActiveFilter.collectAsState()

    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Library", style = MaterialTheme.typography.headlineMedium, color = Ink)
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = "${items.size} items",
                            style = MaterialTheme.typography.labelMedium,
                            color = Muted,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Muted)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Paper)
                .padding(padding)
                .padding(bottom = contentPadding.calculateBottomPadding())
        ) {
            SearchBar(query = searchQuery, onQueryChange = viewModel::setSearchQuery)

            TagFilterRow(
                tags = availableTags,
                selectedTagIds = selectedTagIds,
                onToggleTag = viewModel::toggleTag
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TypeFilterRow(
                    selectedType = selectedType,
                    starredOnly = starredOnly,
                    onSelectType = viewModel::setTypeFilter,
                    onSelectStarred = { viewModel.setStarredOnly(true) },
                    modifier = Modifier.weight(1f)
                )
                SortMenu(sortMode = sortMode, onSelectSort = viewModel::setSortMode)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(InkLine))

            if (items.isEmpty()) {
                EmptyState(hasActiveFilter = hasActiveFilter, modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.item.id }) { itemWithTags ->
                        ItemRow(itemWithTags, onOpen = onOpenItem, onRetry = viewModel::retry)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(InkLine))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .clip(shape)
            .background(Paper2)
            .border(1.dp, InkLine, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search your library", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Seal),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Muted)
            }
        }
    }
}

@Composable
private fun TagFilterRow(tags: List<TagEntity>, selectedTagIds: Set<String>, onToggleTag: (String) -> Unit) {
    if (tags.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        tags.forEach { tag ->
            MonoChip(
                label = tag.label,
                selected = tag.id in selectedTagIds,
                onClick = { onToggleTag(tag.id) }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

private data class TypeFilterOption(val label: String, val type: String?)

private val TYPE_FILTER_OPTIONS = listOf(
    TypeFilterOption("All", null),
    // Display label only -- ItemType.WEB_ARTICLE (the stored/synced value) is unchanged;
    // testers save a wide variety of URL types here, not just long-form articles, so "Links"
    // describes the bucket more accurately than "Articles" did.
    TypeFilterOption("Links", ItemType.WEB_ARTICLE),
    TypeFilterOption("Videos", ItemType.YOUTUBE_VIDEO),
    TypeFilterOption("Notes", ItemType.NOTE)
)

@Composable
private fun TypeFilterRow(
    selectedType: String?,
    starredOnly: Boolean,
    onSelectType: (String?) -> Unit,
    onSelectStarred: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Scrollable so the extra "Starred" chip never crowds the sort menu on a narrow screen.
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        TYPE_FILTER_OPTIONS.forEach { option ->
            MonoChip(
                label = option.label,
                // Type chips deselect while Starred is active (they're one mutually-exclusive row).
                selected = !starredOnly && selectedType == option.type,
                onClick = { onSelectType(option.type) }
            )
        }
        MonoChip(label = "Starred", selected = starredOnly, onClick = onSelectStarred)
    }
}

/** The mockup's `.chip`/`.chip.active`: mono text, kon outline, kon fill when active. */
@Composable
private fun MonoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Kon else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) Kon else InkLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
            color = if (selected) Paper else Kon
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortMenu(sortMode: SortMode, onSelectSort: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (sortMode) {
        SortMode.DATE_SAVED -> "Date saved"
        SortMode.LAST_VIEWED -> "Last viewed"
        SortMode.ALPHABETICAL -> "A–Z"
    }

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Kon)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Sort options", tint = Kon)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Date saved") },
                onClick = { onSelectSort(SortMode.DATE_SAVED); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("Last viewed") },
                onClick = { onSelectSort(SortMode.LAST_VIEWED); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("Alphabetical") },
                onClick = { onSelectSort(SortMode.ALPHABETICAL); expanded = false }
            )
        }
    }
}

@Composable
private fun EmptyState(hasActiveFilter: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = if (hasActiveFilter) {
                "No results match your search and filters."
            } else {
                "Nothing saved yet. Share something into Aniki, or add a note."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Kon,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

@Composable
private fun ItemRow(itemWithTags: ItemWithTags, onOpen: (String) -> Unit, onRetry: (String) -> Unit) {
    val item = itemWithTags.item
    val needsAttention = item.status == ItemStatus.NEEDS_ATTENTION
    val enriched = item.status == ItemStatus.ENRICHED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(item.id) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Thumb: shimmer while processing, type-tinted gradient once enriched/needs-attention.
        // The type icon sits below the image (not overlaid) for article/video; notes skip it --
        // their thumbnail glyph already communicates type on its own ("polish pass 2" item 1/2).
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(58.dp)) {
                if (item.status == ItemStatus.PENDING) {
                    ShimmerBox(modifier = Modifier.fillMaxSize())
                } else {
                    ItemThumbnail(
                        thumbnailUrl = item.thumbnailUrl,
                        type = item.type,
                        sourceUrl = item.sourceUrl,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(11.dp))
                    )
                }
            }
            if (item.status != ItemStatus.PENDING && item.type != ItemType.NOTE) {
                Spacer(Modifier.height(7.dp))
                TypeIcon(item.type, size = 12.dp, tint = Muted)
            }
        }
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))

            if (item.status == ItemStatus.PENDING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(size = 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Aniki is reading this…",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Seal
                    )
                }
            } else {
                item.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = Kon,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(7.dp))

            if (needsAttention) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Couldn't read",
                        style = MaterialTheme.typography.labelSmall,
                        color = Seal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Seal.copy(alpha = 0.10f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onRetry(item.id) }) {
                        Text("Retry", style = MaterialTheme.typography.labelLarge, color = Kon)
                    }
                }
            } else if (enriched && itemWithTags.tags.isNotEmpty()) {
                TagChipsRow(itemWithTags.tags)
            }
        }

        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (enriched) {
                SealMark(size = 24.dp)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = DateUtils.getRelativeTimeSpanString(
                    item.createdAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                color = Muted
            )
        }
    }
}

@Composable
private fun TagChipsRow(tags: List<TagEntity>) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        tags.forEach { tag ->
            Text(
                text = tag.label,
                style = MaterialTheme.typography.labelSmall,
                color = MatchaInk,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MatchaWash)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
