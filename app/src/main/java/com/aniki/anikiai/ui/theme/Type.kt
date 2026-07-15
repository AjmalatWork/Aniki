package com.aniki.anikiai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aniki.anikiai.R

// ---------------------------------------------------------------------------
// IBM Plex, per the mockup sheet:
//   Serif — titles and brand moments
//   Sans  — UI/body text
//   Mono  — tags, timestamps, category labels, tiny utility text
// ---------------------------------------------------------------------------

/** IBM Plex Serif — titles/brand. Static weights (Medium/SemiBold/Bold). */
val PlexSerif = FontFamily(
    Font(R.font.ibm_plex_serif_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_serif_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_serif_bold, FontWeight.Bold)
)

/** IBM Plex Sans — UI text. Variable font, weight axis pinned per entry. */
@OptIn(ExperimentalTextApi::class)
val PlexSans = FontFamily(
    Font(
        R.font.ibm_plex_sans_var,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        R.font.ibm_plex_sans_var,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(
        R.font.ibm_plex_sans_var,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))
    ),
    Font(
        R.font.ibm_plex_sans_var,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    )
)

/** IBM Plex Mono — tags/timestamps/labels/utility. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold)
)

/**
 * Sizes/line-heights/letter-spacing follow the mockup sheet (px read as sp):
 * feed title 27, library title 24, detail title 21, sheet heading 18,
 * body 13–15, row snippet 11.5, mono meta 11, tags 10.5.
 */
val Typography = Typography(
    // Brand moments (onboarding wordmark).
    displaySmall = TextStyle(
        fontFamily = PlexSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1).sp
    ),
    // Feed hero title.
    headlineLarge = TextStyle(
        fontFamily = PlexSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    // Library screen title.
    headlineMedium = TextStyle(
        fontFamily = PlexSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    // Detail title.
    headlineSmall = TextStyle(
        fontFamily = PlexSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.4).sp
    ),
    // Sheet/section headings ("Saved to Aniki").
    titleLarge = TextStyle(
        fontFamily = PlexSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp
    ),
    // List row titles.
    titleSmall = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        lineHeight = 17.sp
    ),
    // Lead/onboarding paragraph.
    bodyLarge = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp
    ),
    // Summaries, card body.
    bodyMedium = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.5.sp
    ),
    // Row snippets, fine print.
    bodySmall = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 15.5.sp
    ),
    // Buttons.
    labelLarge = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp
    ),
    // Mono meta: source pills, timestamps, kickers.
    labelMedium = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    ),
    // Mono tags, tiny utility text.
    labelSmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.3.sp
    )
)
