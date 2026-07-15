package com.aniki.anikiai.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * A real thumbnail (article og:image / YouTube oEmbed thumbnail) when one was captured during
 * enrichment, layered over the type-tinted gradient placeholder — Coil's painter is transparent
 * until the image finishes loading (and stays transparent on failure), so the gradient underneath
 * shows through naturally in every case except a successful load. Shared by Library rows, the
 * Feed hero, and Detail's hero so the fallback logic lives in exactly one place.
 */
@Composable
fun ItemThumbnail(
    thumbnailUrl: String?,
    type: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    Box(modifier.background(typeThumbBrush(type))) {
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
