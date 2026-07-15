package com.aniki.anikiai.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ---------------------------------------------------------------------------
// Central Aniki theme. Light/dark is PER-SCREEN by design (warm parchment
// surfaces vs. the immersive dark Feed) — there is deliberately no system
// dark-mode switch and no dynamic color. Screens on the dark ground wrap
// themselves in AnikiTheme(darkGround = true); everything else inherits the
// parchment scheme from MainActivity.
// ---------------------------------------------------------------------------

/**
 * Parchment scheme — the app's default ground.
 * primary = Kon (structural fills: primary buttons, active chips) — NOT Seal;
 * the oxblood accent is reserved for seal/stamp moments and lives in tertiary.
 */
private val ParchmentColors = lightColorScheme(
    primary = Kon,
    onPrimary = Paper,
    primaryContainer = Paper2,
    onPrimaryContainer = Kon,
    secondary = Matcha,
    onSecondary = Paper,
    secondaryContainer = MatchaWash,
    onSecondaryContainer = MatchaInk,
    tertiary = Seal,
    onTertiary = Paper,
    tertiaryContainer = Paper2,
    onTertiaryContainer = Seal,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Paper2,
    onSurfaceVariant = Muted,
    outline = InkLine,
    outlineVariant = InkLine,
    error = Seal,
    onError = Paper,
    errorContainer = Paper2,
    onErrorContainer = Seal,
    // Keep tonal elevation from cooling the parchment toward Kon.
    surfaceTint = Paper
)

/** Ink scheme — the immersive dark ground (Feed, onboarding). Seal accent shifts to SealDark for legibility. */
private val InkColors = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    primaryContainer = InkRaised,
    onPrimaryContainer = Paper,
    secondary = Matcha,
    onSecondary = Paper,
    secondaryContainer = InkRaised,
    onSecondaryContainer = OnDarkBody,
    tertiary = SealDark,
    onTertiary = Ink,
    tertiaryContainer = InkRaised,
    onTertiaryContainer = SealDark,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = InkRaised,
    onSurfaceVariant = OnDarkMuted,
    outline = PaperLine,
    outlineVariant = PaperLine,
    error = SealDark,
    onError = Ink,
    errorContainer = InkRaised,
    onErrorContainer = SealDark,
    surfaceTint = Ink
)

/**
 * Rounded, weighted corners per the mockup: tags 6, thumbs/chips 9,
 * cards/buttons 12, note paper 18, sheets 22.
 */
val AnikiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun AnikiTheme(
    darkGround: Boolean = false,
    content: @Composable () -> Unit
) {
    // targetSdk 35+ is always edge-to-edge (statusBarColor is ignored), so the
    // status icon appearance must track the per-screen ground: dark icons on
    // parchment, light icons on ink.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkGround
            }
        }
    }

    MaterialTheme(
        colorScheme = if (darkGround) InkColors else ParchmentColors,
        typography = Typography,
        shapes = AnikiShapes,
        content = content
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
