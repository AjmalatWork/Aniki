package com.aniki.anikiai.ui.library

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    val sortMode by viewModel.sortMode.collectAsState()
    val hasActiveFilter by viewModel.hasActiveFilter.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aniki Library") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TypeFilterRow(selectedType = selectedType, onSelectType = viewModel::setTypeFilter)
                SortMenu(sortMode = sortMode, onSelectSort = viewModel::setSortMode)
            }
            HorizontalDivider()

            if (items.isEmpty()) {
                EmptyState(hasActiveFilter = hasActiveFilter, modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.item.id }) { itemWithTags ->
                        ItemRow(itemWithTags, onOpen = onOpenItem, onRetry = viewModel::retry)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        placeholder = { Text("Search your library") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        }
    )
}

@Composable
private fun TagFilterRow(tags: List<TagEntity>, selectedTagIds: Set<String>, onToggleTag: (String) -> Unit) {
    if (tags.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tags.forEach { tag ->
            FilterChip(
                selected = tag.id in selectedTagIds,
                onClick = { onToggleTag(tag.id) },
                label = { Text(tag.label) }
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}

private data class TypeFilterOption(val label: String, val type: String?)

private val TYPE_FILTER_OPTIONS = listOf(
    TypeFilterOption("All", null),
    TypeFilterOption("Articles", ItemType.WEB_ARTICLE),
    TypeFilterOption("Videos", ItemType.YOUTUBE_VIDEO),
    TypeFilterOption("Notes", ItemType.NOTE)
)

@Composable
private fun TypeFilterRow(selectedType: String?, onSelectType: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TYPE_FILTER_OPTIONS.forEach { option ->
            FilterChip(
                selected = selectedType == option.type,
                onClick = { onSelectType(option.type) },
                label = { Text(option.label) }
            )
        }
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
            Text(label)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Sort options")
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
            if (hasActiveFilter) {
                "No results match your search and filters."
            } else {
                "Nothing saved yet. Share something into Aniki, or add a note."
            }
        )
    }
}

@Composable
private fun ItemRow(itemWithTags: ItemWithTags, onOpen: (String) -> Unit, onRetry: (String) -> Unit) {
    val item = itemWithTags.item
    val needsAttention = item.status == ItemStatus.NEEDS_ATTENTION

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(item.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = typeEmoji(item.type), modifier = Modifier.padding(end = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.title, fontWeight = FontWeight.Medium, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        item.createdAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString(),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(item.status)
                if (needsAttention) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onRetry(item.id) }) { Text("Retry") }
                }
            }
            if (item.status == ItemStatus.ENRICHED && itemWithTags.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                TagChipsRow(itemWithTags.tags)
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    when (status) {
        ItemStatus.ENRICHED -> Badge(text = "Filed", color = MaterialTheme.colorScheme.primaryContainer)
        ItemStatus.NEEDS_ATTENTION -> Badge(
            text = "Couldn't read",
            color = MaterialTheme.colorScheme.errorContainer,
            textColor = MaterialTheme.colorScheme.onErrorContainer
        )
        else -> Badge(text = "Processing…", color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun Badge(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TagChipsRow(tags: List<TagEntity>) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        tags.forEach { tag ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Text(
                    text = tag.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

private fun typeEmoji(type: String): String = when (type) {
    ItemType.YOUTUBE_VIDEO -> "▶️"
    ItemType.WEB_ARTICLE -> "🔗"
    ItemType.NOTE -> "📝"
    else -> "•"
}
