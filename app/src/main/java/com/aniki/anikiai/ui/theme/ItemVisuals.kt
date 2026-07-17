package com.aniki.anikiai.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aniki.anikiai.data.db.ItemType

/**
 * Item-type-to-visual mapping shared across Library rows, Detail's hero, and
 * the Share-confirmation sheet — defined once so the three don't drift.
 */

/** Somber token-blend gradient standing in for a real thumbnail, by type. */
fun typeThumbBrush(type: String): Brush = when (type) {
    ItemType.YOUTUBE_VIDEO -> Brush.linearGradient(listOf(Kon, SealDark))
    ItemType.NOTE -> Brush.linearGradient(listOf(Matcha, Kon))
    else -> Brush.linearGradient(listOf(Kon, Matcha))
}

/** Mono corner/kicker label, e.g. Library thumb corner or the share sheet's detected-type pill. */
fun typeMonoLabel(type: String): String = when (type) {
    ItemType.YOUTUBE_VIDEO -> "▶ VIDEO"
    ItemType.NOTE -> "✎ NOTE"
    else -> "◈ LINK"
}

/** background to glyph-text color, background darkest-to-lightest. */
private val monogramPalette = listOf(
    SealTint1 to Paper,
    SealTint2 to Paper,
    SealTint3 to Kon,
    SealTint4 to Kon
)

/**
 * Deterministic on-palette (background, glyph color) pair for an article's monogram tile: hashes
 * the domain into [monogramPalette] rather than free RGB space, so the same domain always lands
 * on the same tone and every tone stays in the oxblood/parchment family.
 */
fun monogramColorsFor(domain: String): Pair<Color, Color> {
    val index = Math.floorMod(domain.hashCode(), monogramPalette.size)
    return monogramPalette[index]
}

/** First letter of a domain, uppercased, for the monogram glyph (e.g. "theverge.com" -> "T"). */
fun monogramLetterFor(domain: String): String =
    domain.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/**
 * Small below-thumbnail type glyph (Library rows, "polish pass 2" item 1) -- deliberately not
 * shown for notes, since a note's thumbnail glyph already communicates its type on its own (see
 * [com.aniki.anikiai.ui.theme.NoteGlyphTile] in Thumbnail.kt).
 */
@Composable
fun TypeIcon(type: String, size: Dp = 12.dp, tint: Color = Muted, modifier: Modifier = Modifier) {
    when (type) {
        ItemType.YOUTUBE_VIDEO -> Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(size)
        )
        else -> GlobeGlyph(tint = tint, modifier = modifier.size(size))
    }
}

/** Hand-drawn "globe" glyph (web article) -- no such icon exists in the trimmed
 *  material-icons-core set this app depends on, so it's drawn directly (same approach as
 *  [SealMark]): a ring, an equator line, and a single meridian ellipse, matching [PlayArrow]'s
 *  plain single-tone line-icon weight. */
@Composable
private fun GlobeGlyph(tint: Color, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.09f
        val ringStyle = Stroke(width = stroke)
        val radius = size.minDimension / 2f - stroke / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = tint, radius = radius, center = center, style = ringStyle)
        drawLine(
            tint,
            Offset(center.x - radius, center.y),
            Offset(center.x + radius, center.y),
            strokeWidth = stroke
        )
        drawOval(
            color = tint,
            topLeft = Offset(center.x - radius * 0.42f, center.y - radius),
            size = Size(radius * 0.84f, radius * 2f),
            style = ringStyle
        )
    }
}
