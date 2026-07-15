package com.aniki.anikiai.ui.theme

import androidx.compose.ui.graphics.Brush
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
