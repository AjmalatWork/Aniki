package com.aniki.anikiai.ui.feed

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import com.aniki.anikiai.ui.theme.Kon
import com.aniki.anikiai.ui.theme.Seal
import com.aniki.anikiai.ui.theme.SealDark
import com.aniki.anikiai.ui.theme.SealMark
import com.aniki.anikiai.ui.theme.Spacing
import com.aniki.anikiai.ui.theme.StarMark
import com.aniki.anikiai.ui.theme.WeightedCard
import com.aniki.anikiai.ui.theme.weightedShadow

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

/** Duration of the refresh-pill blur-in leg (see the blurRadius/RefreshPill.onClick comments in
 *  FeedScreen) -- the delay before the reorder is applied is matched exactly to this. */
private const val BLUR_IN_MS = 150

@Composable
fun FeedScreen(
    repository: ItemRepository,
    feedSessionState: FeedSessionState,
    onOpenDetail: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: FeedViewModel = viewModel(
        factory = viewModelFactory {
            initializer { FeedViewModel(repository, appContext, feedSessionState) }
        }
    )
    val state by viewModel.state.collectAsState()
    val showHint by viewModel.showHint.collectAsState()
    val hintTrigger by viewModel.hintTrigger.collectAsState()
    val refreshAvailable by viewModel.refreshAvailable.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Slice 2, item 1: entering the Feed reuses the frozen per-session order (re-projecting current
    // data onto it) rather than re-ranking, so a Detail round-trip lands on the same card. The order
    // only recomputes on an explicit refresh (first open, or the "Feed updated" pill). Bank the
    // final dwell when the Feed leaves composition.
    LaunchedEffect(Unit) { viewModel.onEnter() }
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
                // Restore the card the user last settled on when re-entering the Feed after a Detail
                // round-trip (item 1). initialPage is only read on first composition, so a plain
                // return lands here; an explicit refresh separately scrolls to 0 (below).
                val initialPage = remember(items) { viewModel.resumePageIn(items).coerceAtLeast(0) }
                val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { items.size })

                // Brief blur while a pill-driven refresh reshuffles the stack and jumps to the top,
                // so the reorder isn't visible mid-flight — it "reveals" the new order once settled
                // (item 4). Blur is a no-op below API 31 (feedBlur); the jump still happens.
                //
                // The blur-in and blur-out legs use different (asymmetric) tweens on purpose: the
                // RefreshPill's onClick below awaits BLUR_IN_MS of blur-in before it lets the reorder
                // actually reach the pager (via viewModel.refreshFeed()), specifically so the swap is
                // never visible even partially-blurred. The blur-out, by contrast, has nothing to hide
                // and can fade at a more comfortable, slightly slower pace.
                var refreshing by remember { mutableStateOf(false) }
                val blurRadius by animateDpAsState(
                    targetValue = if (refreshing) 16.dp else 0.dp,
                    animationSpec = tween(if (refreshing) BLUR_IN_MS else 220),
                    label = "feedBlur"
                )

                // Keyed on settledPage ALONE, deliberately not also on `items`: `items` changes on
                // every background reprojection (a star toggled elsewhere, a reprojectFrozen after
                // returning from a tab switch) even when the user hasn't swiped at all. Co-keying on
                // it used to re-fire this effect on those unrelated updates too, and during the
                // transient window where `items` had already changed shape but the pager hadn't
                // caught up (e.g. right around a dismiss shrinking the list), `items.getOrNull(
                // settledPage)` could momentarily resolve to a different item than the one actually
                // showing -- silently corrupting sessionLastSettledItemId with the wrong id, which
                // would only surface later, intermittently, as the Feed resuming on the wrong card.
                // `items` is still read fresh inside the effect body (LaunchedEffect closures always
                // see the latest composed values), so this only changes *when* it re-runs, not what
                // it reads.
                LaunchedEffect(pagerState.settledPage) {
                    items.getOrNull(pagerState.settledPage)?.let { viewModel.onPageSettled(it.item.id) }
                }

                // Safety net for the effect above: it only updates sessionLastSettledItemId when
                // settledPage actually changes, which leaves a real gap if the Feed is torn down
                // (tab switch away) before that update has had a chance to land -- e.g. a swipe's
                // fling/snap settling right as the user taps the bottom nav, or any other timing
                // where "the pager visibly moved" and "the settle callback ran" don't quite land in
                // the same frame. This force-syncs the true current page directly from pagerState at
                // the exact moment this Content view is actually torn down, closing that gap.
                // rememberUpdatedState is required, not a plain closure over `items`: this effect is
                // keyed on `pagerState` (stable across recompositions), so its onDispose lambda is
                // captured once and would otherwise see whichever `items` was current back when the
                // effect first entered composition, not the latest one.
                val latestItems by rememberUpdatedState(items)
                DisposableEffect(pagerState) {
                    onDispose {
                        latestItems.getOrNull(pagerState.settledPage)?.let { viewModel.onPageSettled(it.item.id) }
                    }
                }

                Box(modifier.fillMaxSize().background(Ink)) {
                    VerticalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().feedBlur(blurRadius)
                    ) { page ->
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

                    // The refresh-available pill floats over the whole stack, fixed at the top —
                    // not attached to any card (item 4). Sits below the source/saved-time pill row
                    // (topPad in FeedCard below, same +10.dp base) rather than sharing its band, so
                    // the two never overlap -- +56.dp clears that row's own height plus a gap.
                    RefreshPill(
                        visible = refreshAvailable,
                        onClick = {
                            // Owns the whole sequence directly (rather than reacting to a
                            // ViewModel-driven counter -- see the blurRadius comment above): blur in
                            // first, wait for it to actually be established, ONLY THEN let the
                            // reordered data reach the pager, THEN scroll to the top card -- still
                            // fully blurred throughout -- and only unblur once that scroll finishes.
                            // Nothing is visible mid-swap because the swap can't happen until the blur
                            // already has; the scroll stays a real, visible-through-the-blur glide
                            // (not an instant jump) since that motion is itself part of the "refreshing"
                            // feel, not just an implementation detail to hide.
                            coroutineScope.launch {
                                refreshing = true
                                delay(BLUR_IN_MS.toLong())
                                viewModel.refreshFeed() // suspends until the new order is in `state`
                                // Same reasoning as the pre-refactor version: an explicit tween (not
                                // the default spring) so the coroutine resumes right as the scroll
                                // visually stops, not after an imperceptible spring settling tail.
                                pagerState.animateScrollToPage(0, animationSpec = tween(durationMillis = 380))
                                refreshing = false
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = contentPadding.calculateTopPadding() + 56.dp)
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
        // The whole video hero opens the source on tap (parity with the article hero, which is
        // fully tappable) — not just the small "Open" rail action. Low in the z-order, so the rail
        // actions / star mark layered above still take their own taps first.
        if (isVideo) {
            Box(Modifier.fillMaxSize().clickable(onClick = onOpen))
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
                onOpen = onOpen,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 18.dp)
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

        // Persistent star mark (item 3), top-left of the full card area, mirroring the top-right 兄
        // seal -- same position for every item type (note, article, video), rendered once here at
        // the full-bleed outer Box rather than inside each type's own inner content (a note's mark
        // used to be anchored to its parchment card instead of the screen, which put it in a
        // different place than articles/videos; fixed by always positioning it here). Tapping it
        // unstars (with the ink-fade dissolve). Stamp-down animation plays on the star transition.
        StarStamp(
            starred = item.isStarred,
            onDark = true,
            onClick = onToggleStar,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = topPad + 48.dp, start = 20.dp)
        )

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
private fun NoteCard(
    item: com.aniki.anikiai.data.db.ItemEntity,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Slice 2, item 2: the Feed note card shows the note's REAL content, not the AI summary/
    // pull-quote (that voice is for links). Fall back to the title only if the body is blank.
    val body = remember(item.bodyText, item.title) {
        item.bodyText?.takeIf { it.isNotBlank() } ?: item.title
    }
    var truncated by remember(body) { mutableStateOf(false) }

    WeightedCard(
        modifier = modifier.widthIn(max = 340.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        containerColor = Paper,
        ambient = 20.dp,
        contact = 6.dp
    ) {
        Box(modifier = Modifier.padding(20.dp)) {
            Column {
                // Reserve a top band so the heading text always sits clear of the 兄 seal overlay
                // (top-right). The star mark itself now lives outside this card entirely -- see
                // FeedCard, which positions it at the full card area's top-left for every item type
                // (item 3's fix: it used to be anchored here instead, inconsistent with articles/videos).
                Spacer(Modifier.height(30.dp))
                Text(
                    text = (item.category?.takeIf { it.isNotBlank() } ?: "Note").uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    color = Seal
                )
                Spacer(Modifier.height(14.dp))

                // Real content, truncated to fit with a bottom fade + "tap to read full note" when
                // it overflows; short notes render whole. Generic fade/affordance shell (see
                // FadingTruncatedContent) so a future checklist note type reuses it with a list slot.
                FadingTruncatedContent(
                    truncated = truncated,
                    fadeColor = Paper,
                    affordance = "tap to read full note"
                ) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 28.sp),
                        color = Kon,
                        maxLines = NOTE_CARD_MAX_LINES,
                        overflow = TextOverflow.Clip,
                        onTextLayout = { truncated = it.hasVisualOverflow }
                    )
                }
            }

            // 兄 seal, top-right -- positioned absolutely so it never reflows the content below.
            SealMark(size = 26.dp, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Max note-body lines shown on a Feed card before it truncates with a fade (item 2). */
private const val NOTE_CARD_MAX_LINES = 9

/**
 * The reusable truncation shell for Feed note content (item 2): renders [content], and when the
 * caller reports it overflowed ([truncated]), overlays a bottom fade to [fadeColor] and appends a
 * tap [affordance]. Deliberately content-agnostic — the caller owns overflow detection and the
 * content slot, so a future checklist note type can plug a list of items in where prose sits today.
 */
@Composable
private fun FadingTruncatedContent(
    truncated: Boolean,
    fadeColor: Color,
    affordance: String,
    content: @Composable () -> Unit
) {
    Column {
        Box {
            content()
            if (truncated) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(0.55f to Color.Transparent, 1f to fadeColor)
                        )
                )
            }
        }
        if (truncated) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = affordance,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
                    color = Seal
                )
                Spacer(Modifier.width(5.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = Seal,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

/**
 * The star stamp (item 3): the persistent oxblood [StarMark] plus its stamp-down / ink-fade
 * animation. On the transition to starred it appears oversized, squashes, then settles, with an
 * oxblood ink-bloom radiating out; on the transition away it dissolves (fade + slight upward
 * drift). A card that first composes already-starred just shows the resting mark (no replay). The
 * mark is tappable to unstar. The whole thing is visual — [onClick] toggles the data, the ordering
 * never moves (item 1/3).
 */
@Composable
private fun StarStamp(
    starred: Boolean,
    onDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    val presence = remember { Animatable(if (starred) 1f else 0f) }
    val scale = remember { Animatable(1f) }
    val ripple = remember { Animatable(0f) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(starred) {
        if (!initialized) {
            initialized = true
            presence.snapTo(if (starred) 1f else 0f)
            return@LaunchedEffect
        }
        if (starred) {
            presence.snapTo(1f)
            ripple.snapTo(0f)
            scale.snapTo(1.5f)
            launch { ripple.animateTo(1f, tween(520)) }
            scale.animateTo(0.86f, animationSpec = tween(110))
            scale.animateTo(
                1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
            )
        } else {
            scale.snapTo(1f)
            presence.animateTo(0f, animationSpec = tween(360))
        }
    }

    // Fully dissolved and not starred: nothing to draw (and no dead click target).
    if (!starred && presence.value <= 0.001f) return

    Box(
        modifier = modifier
            .size(size)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val markColor = if (onDark) SealDark else Seal
        if (ripple.value > 0f && ripple.value < 1f) {
            Box(
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        val s = 1f + ripple.value * 2.4f
                        scaleX = s
                        scaleY = s
                        alpha = (1f - ripple.value) * 0.45f
                    }
                    .clip(CircleShape)
                    .background(markColor)
            )
        }
        Box(
            modifier = Modifier.graphicsLayer {
                alpha = presence.value
                scaleX = scale.value
                scaleY = scale.value
                translationY = -(1f - presence.value) * 10.dp.toPx()
            }
        ) {
            StarMark(size = size, onDark = onDark)
        }
    }
}

/** The "Feed updated" pill (item 4): oxblood, fixed at the top of the Feed, springs down into view
 *  when the session snapshot goes stale. Tapping it re-ranks and scrolls to the top. */
@Composable
private fun RefreshPill(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        ) { full -> -full * 2 } + fadeIn(animationSpec = tween(200)),
        exit = slideOutVertically { full -> -full } + fadeOut(animationSpec = tween(160))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weightedShadow(RoundedCornerShape(20.dp), ambient = 12.dp, contact = 3.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Seal)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = Paper, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                text = "Feed updated",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = Paper
            )
        }
    }
}

/** Blur only where the platform supports it (API 31+); a no-op below, so the refresh still scrolls. */
private fun Modifier.feedBlur(radius: Dp): Modifier =
    if (radius > 0.dp && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        this.blur(radius)
    } else {
        this
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
