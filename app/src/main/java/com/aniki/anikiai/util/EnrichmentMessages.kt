package com.aniki.anikiai.util

import com.aniki.anikiai.data.db.ItemType

/**
 * Machine-readable enrichment failure codes returned by the server (mirrors server/src/types.ts's
 * EnrichmentErrorCode) -- also used client-side to key retry eligibility and curated UI copy.
 * Centralized here (rather than each call site holding its own string) since both
 * EnrichmentWorker and the message functions below need to agree on the exact values.
 */
object EnrichmentErrorCode {
    const val RATE_LIMITED = "RATE_LIMITED"
    const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    const val FETCH_FAILED = "FETCH_FAILED"
    const val EXTRACTION_FAILED = "EXTRACTION_FAILED"
    const val GENERIC = "GENERIC"
}

/**
 * Curated, Aniki-voiced copy for a NEEDS_ATTENTION item, keyed off [errorCode] (Slice 1c revision
 * -- Slice 1b showed the server's raw message verbatim, which mixed technical detail like "Fetch
 * failed with status 404" into user-facing UI). [itemType] only matters for FETCH_FAILED, which
 * reads differently for a video (the oEmbed call failed) than for an article/link (the page fetch
 * failed) -- every other code is type-independent.
 *
 * Two lengths per case: [enrichmentErrorMessageShort] for the Library row (truncates aggressively,
 * one line), [enrichmentErrorMessageLong] for the Detail screen's roomier status card. Null or any
 * unrecognized code (a raw network exception with no server response at all, or a malformed error
 * body) falls through to the generic case.
 */
fun enrichmentErrorMessageShort(errorCode: String?, itemType: String): String = when (errorCode) {
    EnrichmentErrorCode.QUOTA_EXCEEDED -> "Aniki can't read more today"
    EnrichmentErrorCode.RATE_LIMITED -> "Aniki can't keep up — retry"
    EnrichmentErrorCode.FETCH_FAILED ->
        if (itemType == ItemType.YOUTUBE_VIDEO) "Aniki couldn't load this" else "Aniki couldn't reach this"
    EnrichmentErrorCode.EXTRACTION_FAILED -> "Aniki couldn't read this"
    else -> "Aniki couldn't process this"
}

fun enrichmentErrorMessageLong(errorCode: String?, itemType: String): String = when (errorCode) {
    EnrichmentErrorCode.QUOTA_EXCEEDED ->
        "Aniki's reached today's reading limit — this will be picked up automatically tomorrow"
    EnrichmentErrorCode.RATE_LIMITED ->
        "Aniki's handling a lot of requests right now — please try again shortly"
    EnrichmentErrorCode.FETCH_FAILED ->
        if (itemType == ItemType.YOUTUBE_VIDEO) {
            "Aniki couldn't load this video's details right now"
        } else {
            "Aniki couldn't reach this link — it may be down or unavailable"
        }
    EnrichmentErrorCode.EXTRACTION_FAILED -> "Aniki couldn't read this page — it may be paywalled or blocked"
    else -> "Aniki couldn't process this — something unexpected happened"
}

/**
 * Whether a manual Retry is worth offering for this failure. Hidden for:
 *  - QUOTA_EXCEEDED: already auto-scheduled for just after the next UTC reset (see
 *    EnrichmentScheduler.enqueueAfterQuotaReset) -- a manual retry can't succeed before then, so
 *    the button would just be misleading.
 *  - EXTRACTION_FAILED: paywall/blocking is a persistent property of the source, not a transient
 *    hiccup -- retrying will almost certainly reproduce the identical failure.
 * Shown for everything else (RATE_LIMITED, FETCH_FAILED, GENERIC/null) -- all plausibly transient.
 */
fun enrichmentCanRetry(errorCode: String?): Boolean = when (errorCode) {
    EnrichmentErrorCode.QUOTA_EXCEEDED, EnrichmentErrorCode.EXTRACTION_FAILED -> false
    else -> true
}
