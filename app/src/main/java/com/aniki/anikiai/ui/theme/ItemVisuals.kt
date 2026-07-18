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

/** Second-level public suffixes: the label before the TLD in multi-part suffixes like
 *  bbc.co.uk / example.com.au / school.ac.in, so they aren't mistaken for the site name. */
private val secondLevelSuffixes = setOf("co", "com", "org", "net", "gov", "edu", "ac")

/**
 * The site's primary (registrable) label, subdomains and public suffix stripped:
 * "www.theverge.com" -> "theverge", "en.wikipedia.org" -> "wikipedia", "bbc.co.uk" -> "bbc".
 *
 * A [Uri.host] always carries whatever subdomain the URL had (www / m / en / ...), so both the
 * monogram letter and its color must normalize down to this label first -- otherwise every www.*
 * site collapses to one "W" tile and a language/mobile subdomain gives the wrong initial. A
 * deliberately lightweight heuristic (drop the TLD, then a second-level suffix if one remains),
 * not a full Public Suffix List -- more than enough for a one-letter monogram + a color bucket.
 */
fun registrableLabelFor(host: String): String {
    val labels = host.lowercase().split('.').filter { it.isNotEmpty() }
    if (labels.size <= 1) return labels.firstOrNull().orEmpty()
    // Last label is the TLD; the one before it is normally the site name...
    var primaryIndex = labels.size - 2
    // ...unless that's itself a second-level suffix (co.uk, com.au), then step back once more.
    if (primaryIndex >= 1 && labels[primaryIndex] in secondLevelSuffixes) primaryIndex -= 1
    return labels[primaryIndex]
}

/**
 * Deterministic on-palette (background, glyph color) pair for an article's monogram tile: hashes
 * the site's [registrableLabelFor] label into [monogramPalette] rather than free RGB space, so
 * every subdomain of the same site lands on the same tone and every tone stays in the
 * oxblood/parchment family.
 */
fun monogramColorsFor(host: String): Pair<Color, Color> {
    val index = Math.floorMod(registrableLabelFor(host).hashCode(), monogramPalette.size)
    return monogramPalette[index]
}

/** First letter of a site's [registrableLabelFor] label, uppercased, for the monogram glyph
 *  (e.g. "www.theverge.com" -> "T", "en.wikipedia.org" -> "W"). */
fun monogramLetterFor(host: String): String =
    registrableLabelFor(host).firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

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
