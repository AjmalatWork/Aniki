package com.aniki.anikiai.ui.feed

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aniki.anikiai.data.db.ItemType
import com.aniki.anikiai.data.db.ItemWithTags
import com.aniki.anikiai.data.db.TagEntity
import com.aniki.anikiai.data.repository.ItemRepository

@Composable
fun FeedScreen(
    repository: ItemRepository,
    onOpenDetail: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: FeedViewModel = viewModel(
        factory = viewModelFactory {
            initializer { FeedViewModel(repository, appContext) }
        }
    )
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Re-snapshot on every entry (seen-penalties from last visit reshuffle the order); bank the
    // final dwell when the Feed leaves composition.
    LaunchedEffect(Unit) { viewModel.refresh() }
    DisposableEffect(Unit) { onDispose { viewModel.onLeaveFeed() } }

    when (val s = state) {
        FeedUiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        FeedUiState.Empty -> Box(
            modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Your feed is empty. Share a link or add a note, and Aniki will resurface it here.",
                style = MaterialTheme.typography.titleMedium
            )
        }

        is FeedUiState.Content -> {
            val items = s.items
            val pagerState = rememberPagerState(pageCount = { items.size })

            LaunchedEffect(pagerState.settledPage, items) {
                items.getOrNull(pagerState.settledPage)?.let { viewModel.onPageSettled(it.item.id) }
            }

            VerticalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
                val itemWithTags = items[page]
                FeedCard(
                    itemWithTags = itemWithTags,
                    contentPadding = contentPadding,
                    onOpen = {
                        viewModel.onOpen(itemWithTags.item.id)
                        val item = itemWithTags.item
                        if (item.type == ItemType.NOTE || item.sourceUrl == null) {
                            onOpenDetail(item.id)
                        } else {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                            }
                        }
                    },
                    onToggleStar = { viewModel.onToggleStar(itemWithTags.item.id, !itemWithTags.item.isStarred) },
                    onDismiss = { viewModel.onDismiss(itemWithTags.item.id) }
                )
            }
        }
    }
}

@Composable
private fun FeedCard(
    itemWithTags: ItemWithTags,
    contentPadding: PaddingValues,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit,
    onDismiss: () -> Unit
) {
    val item = itemWithTags.item
    val isNote = item.type == ItemType.NOTE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Hero region — placeholder block (no image-loading lib yet; visual pass is later).
        Hero(
            type = item.type,
            category = item.category,
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (isNote) 0.30f else 0.42f)
                .clickable(onClick = onOpen)
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            if (isNote) {
                Text(
                    text = item.bodyText.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                Text(
                    text = item.summary ?: "No summary yet.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (itemWithTags.tags.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                TagRow(itemWithTags.tags)
            }
        }

        ActionBar(
            isStarred = item.isStarred,
            showOpenSource = !isNote && item.sourceUrl != null,
            onOpen = onOpen,
            onToggleStar = onToggleStar,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun Hero(type: String, category: String?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (type == ItemType.YOUTUBE_VIDEO) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        modifier = Modifier.height(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(text = typeEmoji(type), style = MaterialTheme.typography.displayMedium)
                }
                if (!category.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TagRow(tags: List<TagEntity>) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        tags.forEach { tag ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Text(
                    text = tag.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ActionBar(
    isStarred: Boolean,
    showOpenSource: Boolean,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (showOpenSource) {
            OutlinedButton(onClick = onOpen) { Text("Open source") }
        } else {
            OutlinedButton(onClick = onOpen) { Text("Open") }
        }
        Row {
            IconButton(onClick = onToggleStar) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = if (isStarred) "Unstar" else "Star",
                    tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
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
