package com.aniki.anikiai.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Aniki design tokens — FIXED, from the approved mockup sheet. Do not
// reinterpret the palette; every screen styles itself from these.
// ---------------------------------------------------------------------------

/** Sumi ink — the dark ground (immersive Feed, onboarding). */
val Ink = Color(0xFF14161E)

/** Slate indigo — primary structural color on parchment (buttons, chips, headings on cards). */
val Kon = Color(0xFF2A3140)

/** Warm parchment — the light ground (Library, Detail, New Note, Settings, sheets). */
val Paper = Color(0xFFEFE8D6)

/** Card parchment — raised/recessed blocks sitting on [Paper]. */
val Paper2 = Color(0xFFE4DBC5)

/** Oxblood — THE brand accent. Used like a wax seal or ink stamp: small, weighted,
 *  occasional. Never a large flat fill, never a bright call-to-action color. */
val Seal = Color(0xFF7D362B)

/** Seal variant for dark grounds, where [Seal] itself lacks contrast against [Ink]. */
val SealDark = Color(0xFFB0604C)

/** Dried-herb green — tag color. */
val Matcha = Color(0xFF6E7359)

/** Muted warm gray — secondary text/timestamps on parchment. */
val Muted = Color(0xFF8C8672)

// ---------------------------------------------------------------------------
// Derived washes and lines — not new hues, just alpha/blends of the tokens
// above (the mockup's --line/--line-dark/--matcha-soft equivalents, recomputed
// against this palette).
// ---------------------------------------------------------------------------

/** Hairline dividers/borders on parchment (mockup --line: ink at 12%). */
val InkLine = Ink.copy(alpha = 0.12f)

/** Hairline borders on dark grounds (mockup --line-dark: paper at 14%). */
val PaperLine = Paper.copy(alpha = 0.14f)

/** Matcha tag background on parchment (Matcha blended ~20% over Paper). */
val MatchaWash = Color(0xFFD5D0BD)

/** Text on [MatchaWash] — darkened matcha, per the mockup's tag text. */
val MatchaInk = Color(0xFF565B46)

/** Slightly raised dark surface (mockup --ink-2 recomputed against the fixed Ink). */
val InkRaised = Color(0xFF1D212C)

/** Muted text on dark grounds (cool gray in the Kon/Ink family). */
val OnDarkMuted = Color(0xFFA6A9B5)

/** Body text on dark grounds, a step brighter than [OnDarkMuted]. */
val OnDarkBody = Color(0xFFC8CBD6)
