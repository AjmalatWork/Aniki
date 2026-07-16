package com.aniki.anikiai.ui.theme

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aniki.anikiai.data.db.ItemType

/**
 * Three-tier thumbnail resolution (brief "polish pass" item 2):
 * 1. NOTE -> always a note-glyph tile, regardless of thumbnailUrl (notes never use OG images or
 *    monograms).
 * 2. A real thumbnailUrl (article og:image or YouTube oEmbed thumbnail) -> layered over the
 *    type-tinted gradient placeholder, same as before -- Coil's painter is transparent until the
 *    image finishes loading (and stays transparent on failure), so the gradient underneath shows
 *    through naturally in every case except a successful load.
 * 3. WEB_ARTICLE with no thumbnailUrl -> a deterministic on-palette monogram tile instead of the
 *    generic gradient, so distinct articles are visually distinguishable at a glance.
 *
 * Shared by Library rows, the Feed hero (video only, post-polish-pass), and Detail's hero so the
 * fallback logic lives in exactly one place.
 */
@Composable
fun ItemThumbnail(
    thumbnailUrl: String?,
    type: String,
    sourceUrl: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    when {
        type == ItemType.NOTE -> NoteGlyphTile(modifier)
        type == ItemType.WEB_ARTICLE && thumbnailUrl.isNullOrBlank() -> MonogramTile(sourceUrl, modifier)
        else -> Box(modifier.background(typeThumbBrush(type))) {
            if (!thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale
                )
            }
        }
    }
}

@Composable
private fun MonogramTile(sourceUrl: String?, modifier: Modifier) {
    val domain = remember(sourceUrl) {
        sourceUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() }.orEmpty()
    }
    val (background, glyphColor) = remember(domain) {
        if (domain.isBlank()) SealTint1 to Paper else monogramColorsFor(domain)
    }
    val letter = remember(domain) { if (domain.isBlank()) "?" else monogramLetterFor(domain) }

    Box(modifier.background(background), contentAlignment = Alignment.Center) {
        Text(text = letter, style = MaterialTheme.typography.headlineSmall, color = glyphColor)
    }
}

/**
 * Note thumbnail fallback ("polish pass 2" item 2, revised per user feedback): styled like the
 * app launcher icon -- the same [SealStampGradient] oxblood badge, with the existing
 * [Icons.Default.Edit] pencil glyph (already used for edit affordances elsewhere, e.g. Detail's
 * edit-title/summary buttons) in [Paper] on top, standing in for the launcher's 兄 mark. Reuses an
 * existing icon rather than a new hand-drawn shape, and stays visually distinct from the outlined
 * seal shown elsewhere on the card (filled badge, no 兄 character, no rotation) so it doesn't read
 * as a duplicate. Unrotated and Center-aligned so it can't drift off-center the way the old
 * rotated seal glyph did.
 */
@Composable
private fun NoteGlyphTile(modifier: Modifier) {
    Box(modifier.background(SealStampGradient), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.Edit,
            contentDescription = null,
            tint = Paper,
            modifier = Modifier.size(20.dp)
        )
    }
}
