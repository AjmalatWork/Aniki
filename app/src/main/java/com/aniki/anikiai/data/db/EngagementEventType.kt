package com.aniki.anikiai.data.db

/**
 * The engagement-event vocabulary — one source of truth shared by the capture path
 * (ItemRepository), the sync DTOs, and the pure tag-weight derivation (feed.computeTagWeights).
 * These strings are what land in engagement_events.eventType and sync to the server verbatim.
 */
object EngagementEventType {
    const val SHOWN = "SHOWN"          // card became the focused Feed page (impression; not affinity signal)
    const val DWELL = "DWELL"          // time spent on a card before moving on (value = ms)
    const val OPENED = "OPENED"        // user opened the source / Detail
    const val STARRED = "STARRED"      // user starred from the Feed
    const val DISMISSED = "DISMISSED"  // user dismissed a card
    const val SWIPED_FAST = "SWIPED_FAST" // reserved: very short dwell before swiping away
}
