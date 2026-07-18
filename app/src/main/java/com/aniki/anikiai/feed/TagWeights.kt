package com.aniki.anikiai.feed

import com.aniki.anikiai.data.db.EngagementEventType
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure, deterministic (NOT ML) derivation of per-tag affinity weights from the user's engagement
 * history. Each event contributes a signal to every tag of the item it targeted; signals are
 * summed per tag and normalized to [-1, 1] by the largest magnitude, so a heavily-dismissed tag
 * ends up negative and a heavily-engaged one near +1. Empty history -> empty map = cold start
 * (buildFeed's affinity term is then neutral for everyone).
 */
fun computeTagWeights(
    records: List<EngagementRecord>,
    config: TagWeightConfig = TagWeightConfig()
): Map<String, Double> {
    if (records.isEmpty()) return emptyMap()

    val raw = HashMap<String, Double>()
    for (record in records) {
        val signal = signalForEvent(record.eventType, record.value, config)
        if (signal == 0.0) continue
        for (tag in record.itemTags) {
            raw[tag] = (raw[tag] ?: 0.0) + signal
        }
    }
    return normalizeTagWeights(raw)
}

/**
 * The per-event affinity signal, independent of which tags it applies to. Pulled out of
 * [computeTagWeights] so [com.aniki.anikiai.data.repository.ItemRepository] can fold each event's
 * signal into its item's running `engagementSignal` total at write time (see
 * ItemRepository.recordEvent / SyncRepository.mergeEngagementEvent) instead of re-deriving it from
 * a full event scan on every feed refresh. `AnikiDatabase`'s migration 9->10 backfill duplicates
 * these same constants in raw SQL for the one-time historical fold -- see
 * AnikiDatabaseMigrationConstantsTest, which asserts the two can't silently drift apart.
 */
fun signalForEvent(eventType: String, value: Double?, config: TagWeightConfig = TagWeightConfig()): Double =
    when (eventType) {
        EngagementEventType.OPENED -> config.opened
        EngagementEventType.STARRED -> config.starred
        EngagementEventType.DISMISSED -> config.dismissed
        EngagementEventType.SWIPED_FAST -> config.swipedFast
        EngagementEventType.DWELL -> min((value ?: 0.0) / config.dwellFullMs, 1.0) * config.dwellMax
        else -> 0.0 // SHOWN (impression) and anything unknown contribute nothing
    }

/**
 * Normalizes a raw per-tag (or, for [ItemRepository.computeUserTagWeights], per-tag-summed-from-
 * per-item) signal map to [-1, 1] by the largest magnitude. Shared tail of [computeTagWeights] and
 * the item-signal-aggregate path so both normalize identically.
 */
fun normalizeTagWeights(raw: Map<String, Double>): Map<String, Double> {
    if (raw.isEmpty()) return emptyMap()
    val maxAbs = raw.values.maxOf { abs(it) }
    if (maxAbs == 0.0) return emptyMap()
    return raw.mapValues { it.value / maxAbs }
}
