package com.aniki.anikiai.ui.feed

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aniki.anikiai.data.db.ItemType
import com.aniki.anikiai.data.db.ItemWithTags
import com.aniki.anikiai.data.db.TagEntity
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.ui.theme.AnikiTheme
import com.aniki.anikiai.ui.theme.Ink
import com.aniki.anikiai.ui.theme.ItemThumbnail
import com.aniki.anikiai.ui.theme.OnDarkBody
import com.aniki.anikiai.ui.theme.Paper
import com.aniki.anikiai.ui.theme.PaperLine
import com.aniki.anikiai.ui.theme.Seal
import com.aniki.anikiai.ui.theme.SealDark
import com.aniki.anikiai.ui.theme.SealMark
import com.aniki.anikiai.ui.theme.WeightedCard

/**
 * The Feed is the one immersive dark-ground screen in the app (mockup plates 03/04) — it wraps
 * itself in AnikiTheme(darkGround = true) rather than inheriting the parchment default.
 */
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

    AnikiTheme(darkGround = true) {
        when (val s = state) {
            FeedUiState.Loading -> Box(modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SealDark)
            }

            FeedUiState.Empty -> Box(
                modifier
                    .fillMaxSize()
                    .background(Ink)
                    .padding(contentPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Your feed is empty. Share a link or add a note, and Aniki will resurface it here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnDarkBody
                )
            }

            is FeedUiState.Content -> {
                val items = s.items
                val pagerState = rememberPagerState(pageCount = { items.size })

                LaunchedEffect(pagerState.settledPage, items) {
                    items.getOrNull(pagerState.settledPage)?.let { viewModel.onPageSettled(it.item.id) }
                }

                VerticalPager(state = pagerState, modifier = modifier.fillMaxSize().background(Ink)) { page ->
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
    val savedAgo = DateUtils.getRelativeTimeSpanString(
        item.createdAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
    val sourceLabel = if (isNote) {
        "your note"
    } else {
        item.sourceUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: "note"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-bleed ground: gradient base (also the fallback while a real thumbnail loads/fails).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isNote) {
                        Brush.linearGradient(listOf(Color(0xFF20263A), Ink))
                    } else {
                        Brush.radialGradient(
                            colors = listOf(typeGlow(item.type), Ink),
                            center = Offset(0.3f, 0.25f),
                            radius = 1400f
                        )
                    }
                )
        )
        if (!isNote && !item.thumbnailUrl.isNullOrBlank()) {
            ItemThumbnail(
                thumbnailUrl = item.thumbnailUrl,
                type = item.type,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Legibility scrim, darkest at the bottom where text sits.
        if (!isNote) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Ink.copy(alpha = 0.15f),
                            0.45f to Ink.copy(alpha = 0.35f),
                            1f to Ink.copy(alpha = 0.92f)
                        )
                    )
            )
        }

        val topPad = contentPadding.calculateTopPadding() + 10.dp

        // Top pills: source + saved-time.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = topPad, start = 18.dp, end = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SourcePill(sourceLabel, showDot = !isNote)
            SourcePill(if (isNote) "resurfaced ↑" else "saved $savedAgo")
        }

        if (isNote) {
            NoteCard(
                item = item,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 18.dp)
                    .clickable(onClick = onOpen)
            )
            if (itemWithTags.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 18.dp, end = 66.dp, bottom = contentPadding.calculateBottomPadding() + 46.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemWithTags.tags.forEach { DarkTagChip(it) }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 20.dp,
                        end = 66.dp,
                        bottom = contentPadding.calculateBottomPadding() + 34.dp
                    )
            ) {
                if (!item.category.isNullOrBlank()) {
                    Text(
                        text = item.category!!.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                        color = Color(0xFFE7B9A8)
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Paper,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = item.summary ?: "Aniki is still reading this one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnDarkBody,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                if (itemWithTags.tags.isNotEmpty()) {
                    Spacer(Modifier.height(15.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemWithTags.tags.forEach { DarkTagChip(it) }
                    }
                }
            }
        }

        // Right-side action rail.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = contentPadding.calculateBottomPadding() + 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            RailAction(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                label = "Open",
                onClick = onOpen
            )
            RailAction(
                icon = Icons.Default.Star,
                label = "Star",
                on = item.isStarred,
                onClick = onToggleStar
            )
            RailAction(icon = Icons.Default.Close, label = "Dismiss", onClick = onDismiss)
        }

        // Swipe hint.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding() + 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = Paper.copy(alpha = 0.55f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "swipe up for next",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontSize = 9.sp),
                color = Paper.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun SourcePill(text: String, showDot: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Ink.copy(alpha = 0.5f))
            .pillBorder(PaperLine)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Seal)
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp), color = OnDarkBody)
    }
}

private fun Modifier.pillBorder(color: Color) = this.border(1.dp, color, RoundedCornerShape(20.dp))
private fun Modifier.circleBorder(color: Color) = this.border(1.dp, color, CircleShape)

@Composable
private fun NoteCard(item: com.aniki.anikiai.data.db.ItemEntity, modifier: Modifier = Modifier) {
    WeightedCard(
        modifier = modifier.widthIn(max = 340.dp),
        shape = RoundedCornerShape(18.dp),
        containerColor = Paper,
        ambient = 20.dp,
        contact = 6.dp
    ) {
        Box(modifier = Modifier.padding(22.dp)) {
            Column {
                Text(
                    text = (item.category?.takeIf { it.isNotBlank() } ?: "Note").uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    color = Seal
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "“${item.bodyText.orEmpty().ifBlank { item.title }}”",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 27.sp),
                    color = com.aniki.anikiai.ui.theme.Kon,
                    modifier = Modifier.padding(end = 34.dp)
                )
                // Reserves room below the quote so the seal (absolute bottom-end) never overlaps text.
                Spacer(Modifier.height(38.dp))
            }
            SealMark(
                size = 36.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
private fun DarkTagChip(tag: TagEntity) {
    Text(
        text = tag.label,
        style = MaterialTheme.typography.labelSmall,
        color = Paper.copy(alpha = 0.92f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Paper.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
private fun RailAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean = false,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (on) Seal else Ink.copy(alpha = 0.5f))
                .circleBorder(PaperLine)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Paper, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = OnDarkBody)
    }
}

/** A soft type-tinted glow at the top-left of the hero, per the mockup's radial gradients. */
private fun typeGlow(type: String): Color = when (type) {
    ItemType.YOUTUBE_VIDEO -> Color(0xFF3A456B)
    else -> Color(0xFF26324E)
}
