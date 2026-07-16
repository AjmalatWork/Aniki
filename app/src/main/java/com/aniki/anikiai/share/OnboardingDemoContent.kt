package com.aniki.anikiai.share

/**
 * The onboarding "how sharing works" step (ui/onboarding/ShareTipScreen.kt) fires a real
 * `Intent.ACTION_SEND` with this content so Aniki appears as a genuine target in the OS share
 * sheet, exactly like any real share. [title]/[body] are hardcoded and saved as-is by
 * ShareReceiverActivity when it detects this is the demo share -- never routed through
 * ShareContentClassifier or Gemini enrichment (see ItemRepository.saveSharedContent's isDemo
 * branch), so onboarding never silently costs a real API call.
 *
 * The marker is a run of zero-width spaces (U+200B) -- invisible in the share sheet's own text
 * preview and in any app the demo gets shared to, but content a real share would never contain by
 * coincidence -- so ShareReceiverActivity can tell this apart from a real share.
 */
object OnboardingDemoContent {
    private const val ZWSP = "​"
    private val MARKER = ZWSP.repeat(6)

    /** Hardcoded demo note title -- saved verbatim, never Gemini-generated. */
    const val title: String = "Welcome to Aniki"

    /** Hardcoded demo note body -- saved verbatim, never Gemini-generated. */
    const val body: String =
        "This is what a saved note looks like -- Aniki reads and tags everything you save, including this one."

    /**
     * The text handed to `Intent.ACTION_SEND` when the user taps "Try it" in ShareTipScreen --
     * shows [title]/[body] in the share sheet's own preview so it reads as a real note, with the
     * invisible marker appended for detection. ShareReceiverActivity ignores this string's content
     * once it detects the marker, saving [title]/[body] directly instead of parsing it back out.
     */
    val shareText: String = "$title\n\n$body$MARKER"

    fun isDemoShare(rawText: String): Boolean = rawText.contains(MARKER)
}
