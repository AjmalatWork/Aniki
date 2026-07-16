package com.aniki.anikiai.feed

/**
 * Picks the text the Feed's typographic hero should set large: the first "strong" sentence of
 * the AI summary, so an article/note slide reads as composed typography rather than an empty
 * card. Pure, no Compose/Android deps -- unit-tested directly (see PullQuoteTest).
 */
private const val MIN_SENTENCE_LENGTH = 20

fun extractPullQuote(summary: String?, title: String): String {
    val trimmedSummary = summary?.trim().orEmpty()
    if (trimmedSummary.isEmpty()) return title

    val sentences = splitIntoSentences(trimmedSummary)
    val first = sentences.getOrNull(0).orEmpty()
    if (first.length >= MIN_SENTENCE_LENGTH) return first

    val second = sentences.getOrNull(1).orEmpty()
    if (second.length >= MIN_SENTENCE_LENGTH) return second

    // Every sentence is a stub (or the summary is one unbroken sentence with no split points
    // at all) -- fall back to the whole summary rather than an near-empty stub; the sizing step
    // function scales this down instead of truncating/clipping it.
    return trimmedSummary
}

/** Simple end-of-sentence split (./!/? followed by whitespace) -- no ICU BreakIterator, since
 *  this only ever needs the first couple of sentences, not a fully correct segmentation. */
private fun splitIntoSentences(text: String): List<String> =
    text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Step the pull-quote's font size down as it gets longer, so it always fits the card's main area
 * without scrolling/clipping. Character-count buckets rather than a measure-and-shrink loop --
 * simple, deterministic, and good enough for a handful of discrete size classes.
 */
fun pullQuoteFontSizeSp(quote: String): Int = when {
    quote.length <= 40 -> 34
    quote.length <= 80 -> 28
    quote.length <= 140 -> 22
    quote.length <= 220 -> 18
    else -> 15
}
