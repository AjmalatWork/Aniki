package com.aniki.anikiai.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Shared material treatments — defined ONCE here so every screen reuses the
// same physical language instead of re-deriving shadows/seals per screen.
// ---------------------------------------------------------------------------

/**
 * The weighted-object shadow combo from the mockup: a soft, wide ambient
 * shadow plus a tighter contact shadow. Ink-based (somber), never a colored
 * glow. Apply before clip/background.
 */
fun Modifier.weightedShadow(
    shape: Shape,
    ambient: Dp = 16.dp,
    contact: Dp = 5.dp
): Modifier = this
    .shadow(
        elevation = ambient,
        shape = shape,
        clip = false,
        ambientColor = Ink.copy(alpha = 0.55f),
        spotColor = Ink.copy(alpha = 0.35f)
    )
    .shadow(
        elevation = contact,
        shape = shape,
        clip = false,
        ambientColor = Ink.copy(alpha = 0.30f),
        spotColor = Ink.copy(alpha = 0.55f)
    )

/**
 * A card with physical presence: weighted shadow + a subtle inset highlight
 * (thin light border) so it reads as an object lying on the ground, not a
 * flat Material tile.
 *
 * @param onDark true when the card sits on the ink ground (dims the highlight).
 */
@Composable
fun WeightedCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    containerColor: Color = Paper2,
    onDark: Boolean = false,
    ambient: Dp = 16.dp,
    contact: Dp = 5.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val highlight = if (onDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.35f)
    Column(
        modifier = modifier
            .weightedShadow(shape, ambient, contact)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, highlight, shape),
        content = content
    )
}

/** The embossed oxblood stamp fill — [SealMark]'s filled variant and the app launcher icon both
 *  use this exact gradient; shared here so a third user (the note thumbnail glyph) doesn't drift. */
val SealStampGradient: Brush = Brush.verticalGradient(
    0f to Color(0xFF8D4234),
    1f to Color(0xFF6E2F25)
)

/**
 * The 兄 seal — the mark Aniki presses on items it has read and filed.
 * Two forms, both from the mockup:
 *
 *  - [filled] = false: the small outlined chip (circle, 1.5dp seal-colored
 *    ring, rotated −8°) used on Library rows, Detail hero, note cards.
 *  - [filled] = true: the stamp block (rounded square, oxblood fill, paper
 *    glyph, embossed/pressed treatment) used at brand moments — onboarding,
 *    the share-confirmation sheet.
 *
 * @param onDark use the dark-ground seal variant ([SealDark]) for the outlined
 *   ring/glyph so it stays legible on ink. The filled stamp keeps its oxblood
 *   body on either ground.
 */
@Composable
fun SealMark(
    size: Dp = 26.dp,
    filled: Boolean = false,
    onDark: Boolean = false,
    rotation: Float = if (filled) -6f else -8f,
    modifier: Modifier = Modifier
) {
    if (filled) {
        val corner = size * 0.28f
        val shape = RoundedCornerShape(corner)
        Box(
            modifier = modifier
                .size(size)
                .rotate(rotation)
                .weightedShadow(shape, ambient = size * 0.18f, contact = size * 0.06f)
                .clip(shape)
                // Slight top-light vertical shift for the pressed/embossed feel.
                .background(SealStampGradient)
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "兄",
                style = TextStyle(
                    fontFamily = PlexSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.48f).sp,
                    color = Paper,
                    shadow = Shadow(
                        color = Ink.copy(alpha = 0.35f),
                        offset = Offset(0f, 2f),
                        blurRadius = 2f
                    )
                )
            )
        }
    } else {
        val sealColor = if (onDark) SealDark else Seal
        Box(
            modifier = modifier
                .size(size)
                .rotate(rotation)
                .border(1.5.dp, sealColor.copy(alpha = 0.92f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "兄",
                style = TextStyle(
                    fontFamily = PlexSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.5f).sp,
                    color = sealColor.copy(alpha = 0.92f)
                )
            )
        }
    }
}

/**
 * The oxblood star mark — Aniki's "starred / keep resurfacing this" stamp (Slice 2, item 3). A
 * small filled star in the seal palette, tipped at the same −8° as the outlined [SealMark] so the
 * two read as a matched pair when they sit at opposite top corners of a Feed card (seal top-right,
 * star top-left). Purely the resting mark — the stamp-down / ink-fade animation lives at the call
 * site (FeedScreen) so this stays reusable anywhere a static "starred" badge is wanted.
 *
 * @param onDark use [SealDark] (legible on the immersive Feed's ink ground) instead of [Seal].
 */
@Composable
fun StarMark(
    size: Dp = 22.dp,
    onDark: Boolean = false,
    rotation: Float = -8f,
    modifier: Modifier = Modifier
) {
    val color = if (onDark) SealDark else Seal
    Icon(
        imageVector = Icons.Filled.Star,
        contentDescription = null,
        tint = color,
        modifier = modifier.size(size).rotate(rotation)
    )
}

/** The "Aniki is reading this…" pulsing dot — Library processing rows, the Share sheet. */
@Composable
fun PulseDot(color: Color = Seal, size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulsePhase"
    )
    Box(
        modifier = Modifier
            .size(size)
            .scale(0.8f + 0.35f * phase)
            .alpha(0.3f + 0.7f * phase)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * A static "paused" glyph (two bars in a ring) for the offline-queued state -- a PENDING item
 * whose enrichment job is parked on WorkManager's CONNECTED constraint, not actively running.
 * Deliberately non-animated so it reads as distinct from [PulseDot] at a glance (waiting vs.
 * actively working), same footprint so it drops into the same call sites without a layout shift.
 */
@Composable
fun PausedIndicator(color: Color = Muted, size: Dp = 8.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val strokeWidth = size.toPx() * 0.12f
        drawCircle(color = color, radius = (size.toPx() - strokeWidth) / 2f, style = Stroke(strokeWidth))

        val barWidth = size.toPx() * 0.16f
        val barHeight = size.toPx() * 0.42f
        val gap = size.toPx() * 0.12f
        val top = center.y - barHeight / 2f
        drawRect(
            color = color,
            topLeft = Offset(center.x - gap / 2f - barWidth, top),
            size = Size(barWidth, barHeight)
        )
        drawRect(
            color = color,
            topLeft = Offset(center.x + gap / 2f, top),
            size = Size(barWidth, barHeight)
        )
    }
}

/** Shimmering placeholder for a not-yet-enriched item's thumbnail (Library "processing" rows). */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(11.dp)) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerOffset"
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Paper2, Color(0xFFF3F0E6), Paper2),
                    start = Offset(offset * 300f - 150f, 0f),
                    end = Offset(offset * 300f + 150f, 200f)
                )
            )
    )
}
