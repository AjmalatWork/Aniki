package com.aniki.anikiai.ui.feed

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import com.aniki.anikiai.feed.extractPullQuote
import com.aniki.anikiai.feed.pullQuoteFontSizeSp
import com.aniki.anikiai.ui.theme.AnikiTheme
import com.aniki.anikiai.ui.theme.Ink
import com.aniki.anikiai.ui.theme.ItemThumbnail
import com.aniki.anikiai.ui.theme.OnDarkBody
import com.aniki.anikiai.ui.theme.Paper
import com.aniki.anikiai.ui.theme.PaperLine
import com.aniki.anikiai.ui.theme.Seal
import com.aniki.anikiai.ui.theme.SealDark
import com.aniki.anikiai.ui.theme.SealMark
import com.aniki.anikiai.ui.theme.Spacing
import com.aniki.anikiai.ui.theme.WeightedCard

/**
 * The Feed is the one immersive dark-ground screen in the app (mockup plates 03/04) — it wraps
 * itself in AnikiTheme(darkGround = true) rather than inheriting the parchment default.
 */

/**
 * Bottom-anchor offset shared by every card type's bottom zone (video's text+tags column, the
 * article hero's source+tags column, the note tags row) so all three clear the swipe-up hint
 * ([Spacing.sm] above [PaddingValues.calculateBottomPadding]) by the same margin instead of each
 * card type drifting to its own one-off value ("polish pass 2" item 3).
 */
private val BOTTOM_ZONE_INSET = 46.dp
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
    val showHint by viewModel.showHint.collectAsState()
    val hintTrigger by viewModel.hintTrigger.collectAsState()
    val context = LocalContext.current

    // Re-snapshot on every entry (seen-penalties from last visit reshuffle the order); bank the
    // final dwell when the Feed leaves composition.
    LaunchedEffect(Unit) { viewModel.refresh() }
    DisposableEffect(Unit) {
        viewModel.onFeedVisible()
        onDispose {
            viewModel.onLeaveFeed()
            viewModel.onFeedHidden()
        }
    }

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
                    // No "swipe up for next" text/animation on the last item -- there's nothing
                    // further to swipe to (this also covers the single-item Feed, where the only
                    // page is always the last one).
                    val isLastPage = page == items.lastIndex
                    FeedCard(
                        itemWithTags = itemWithTags,
                        contentPadding = contentPadding,
                        showHint = showHint && page == pagerState.settledPage && !isLastPage,
                        hintTrigger = hintTrigger,
                        playLandingAnimation = itemWithTags.item.id == s.demoLandingItemId,
                        onLandingAnimationPlayed = viewModel::onLandingAnimationPlayed,
                        onInteraction = viewModel::onInteraction,
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
    showHint: Boolean,
    hintTrigger: Int,
    playLandingAnimation: Boolean,
    onLandingAnimationPlayed: () -> Unit,
    onInteraction: () -> Unit,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit,
    onDismiss: () -> Unit
) {
    val item = itemWithTags.item
    val isNote = item.type == ItemType.NOTE
    val isVideo = item.type == ItemType.YOUTUBE_VIDEO
    // WEB_ARTICLE (and any future non-video, non-note type) gets the typographic-hero treatment:
    // no image, the pull-quote itself is the visual. Video keeps its thumbnail hero unchanged.
    val isArticle = !isNote && !isVideo
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

    // Peek-and-settle: the whole card nudges up ~24dp then springs back, echoing the swipe
    // direction. Keyed off hintTrigger (not showHint) so a second trigger while the first is
    // still settling restarts cleanly rather than being a no-op on an unchanged boolean.
    val peekOffset = remember { Animatable(0f) }
    LaunchedEffect(hintTrigger) {
        if (hintTrigger == 0 || !showHint) return@LaunchedEffect
        peekOffset.snapTo(0f)
        peekOffset.animateTo(-24f, animationSpec = tween(durationMillis = 220))
        peekOffset.animateTo(
            0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        )
    }

    // One-time "you just shared this" confirmation for the onboarding demo item: a brief Seal
    // glow pulse over the whole card, under a second, using the same Animatable idiom as the
    // peek-and-settle hint above rather than a new animation system. Keyed on the item's id (not
    // just the boolean) so it can't be mistaken for a replay if this composable is reused for a
    // different item at the same pager position.
    val landingGlow = remember { Animatable(0f) }
    LaunchedEffect(itemWithTags.item.id, playLandingAnimation) {
        if (!playLandingAnimation) return@LaunchedEffect
        landingGlow.animateTo(0.35f, animationSpec = tween(durationMillis = 220))
        landingGlow.animateTo(0f, animationSpec = tween(durationMillis = 500))
        onLandingAnimationPlayed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { androidx.compose.ui.unit.IntOffset(0, peekOffset.value.dp.roundToPx()) }
            // Observes touches without consuming them, so the pager's own drag handling is
            // untouched -- this exists purely to dismiss the hint / reset the idle clock.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onInteraction()
                }
            }
    ) {
        // Full-bleed ground: gradient base. Articles keep a quiet gradient (no image, no scrim --
        // the pull-quote is the visual, per the brief: "do not add an image/OG hero here").
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    when {
                        isNote -> Brush.linearGradient(listOf(Color(0xFF20263A), Ink))
                        isArticle -> Brush.radialGradient(
                            colors = listOf(typeGlow(item.type), Ink),
                            center = Offset(0.3f, 0.2f),
                            radius = 1600f
                        )
                        else -> Brush.radialGradient(
                            colors = listOf(typeGlow(item.type), Ink),
                            center = Offset(0.3f, 0.25f),
                            radius = 1400f
                        )
                    }
                )
        )
        if (isVideo && !item.thumbnailUrl.isNullOrBlank()) {
            ItemThumbnail(
                thumbnailUrl = item.thumbnailUrl,
                type = item.type,
                sourceUrl = item.sourceUrl,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Legibility scrim, darkest at the bottom where text sits -- video only, since it's the
        // only type still layering text over an image.
        if (isVideo) {
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
                        .padding(start = 18.dp, end = 66.dp, bottom = contentPadding.calculateBottomPadding() + BOTTOM_ZONE_INSET)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemWithTags.tags.forEach { DarkTagChip(it) }
                }
            }
        } else if (isArticle) {
            ArticleHeroCard(
                item = item,
                tags = itemWithTags.tags,
                sourceLabel = sourceLabel,
                topPad = topPad,
                contentPadding = contentPadding,
                onOpen = onOpen
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 20.dp,
                        end = 66.dp,
                        bottom = contentPadding.calculateBottomPadding() + BOTTOM_ZONE_INSET
                    )
            ) {
                if (!item.category.isNullOrBlank()) {
                    Text(
                        text = item.category!!.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                        color = Color(0xFFE7B9A8)
                    )
                    Spacer(Modifier.height(Spacing.md))
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Paper,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = item.summary ?: "Aniki is still reading this one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnDarkBody,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                if (itemWithTags.tags.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.lg))
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

        // Swipe hint: an affordance nudge, not permanent chrome -- see FeedViewModel for when
        // it's eligible (first-ever Feed open, plus idle re-trigger during that same session only).
        AnimatedVisibility(
            visible = showHint,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + Spacing.sm),
            enter = fadeIn(animationSpec = tween(durationMillis = 260)),
            exit = fadeOut(animationSpec = tween(durationMillis = 260))
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

        if (landingGlow.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SealDark.copy(alpha = landingGlow.value))
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
    // Prefer the AI summary's pull-quote once enrichment has run (same extraction as articles,
    // for a consistent typographic voice); a not-yet-enriched note falls back to quoting its own
    // body text verbatim, same as before.
    val quote = remember(item.summary, item.bodyText, item.title) {
        if (!item.summary.isNullOrBlank()) {
            extractPullQuote(item.summary, item.title)
        } else {
            item.bodyText.orEmpty().ifBlank { item.title }
        }
    }

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
                    text = "“$quote”",
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

/**
 * Article typographic hero (brief #3): no image/OG hero here, deliberately -- the pull-quote
 * itself fills the card's main area, large, with a corner seal stamp and small domain+tags at
 * the bottom. Mirrors NoteCard's now-shared pull-quote extraction so articles and (enriched)
 * notes read with one consistent typographic voice.
 */
@Composable
private fun ArticleHeroCard(
    item: com.aniki.anikiai.data.db.ItemEntity,
    tags: List<TagEntity>,
    sourceLabel: String,
    topPad: androidx.compose.ui.unit.Dp,
    contentPadding: PaddingValues,
    onOpen: () -> Unit
) {
    val quote = remember(item.summary, item.title) { extractPullQuote(item.summary, item.title) }
    val fontSizeSp = remember(quote) { pullQuoteFontSizeSp(quote) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onOpen)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 26.dp, vertical = 90.dp)
        ) {
            if (!item.category.isNullOrBlank()) {
                Text(
                    text = item.category!!.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                    color = Color(0xFFE7B9A8)
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = "“$quote”",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.3).sp
                ),
                color = Paper
            )
        }

        // Seal stamp, clear of the top pill row -- same "read/filed" motif as everywhere else.
        SealMark(
            size = 28.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = topPad + 48.dp, end = 20.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 66.dp, bottom = contentPadding.calculateBottomPadding() + BOTTOM_ZONE_INSET)
        ) {
            Text(text = sourceLabel, style = MaterialTheme.typography.labelMedium, color = OnDarkBody)
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.lg))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tags.forEach { DarkTagChip(it) }
                }
            }
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
