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
        val signal = signalFor(record, config)
        if (signal == 0.0) continue
        for (tag in record.itemTags) {
            raw[tag] = (raw[tag] ?: 0.0) + signal
        }
    }
    if (raw.isEmpty()) return emptyMap()

    val maxAbs = raw.values.maxOf { abs(it) }
    if (maxAbs == 0.0) return emptyMap()
    return raw.mapValues { it.value / maxAbs }
}

private fun signalFor(record: EngagementRecord, c: TagWeightConfig): Double = when (record.eventType) {
    EngagementEventType.OPENED -> c.opened
    EngagementEventType.STARRED -> c.starred
    EngagementEventType.DISMISSED -> c.dismissed
    EngagementEventType.SWIPED_FAST -> c.swipedFast
    EngagementEventType.DWELL -> min((record.value ?: 0.0) / c.dwellFullMs, 1.0) * c.dwellMax
    else -> 0.0 // SHOWN (impression) and anything unknown contribute nothing
}
