package com.aniki.anikiai.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    else -> "◈ ARTICLE"
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
