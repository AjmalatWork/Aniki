package com.aniki.anikiai.feed

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToLong

private const val MS_PER_DAY = 86_400_000.0

/**
 * Which items are eligible for the Feed. Documented choice: ENRICHED only — the Feed resurfaces
 * *understood* content, and a PENDING/NEEDS_ATTENTION item has no summary/tags (renders poorly
 * full-screen and contributes no affinity). Such items stay visible and retry-able in the Library.
 */
private const val USABLE_STATUS = "ENRICHED"

/**
 * Pure, offline ranking. Scores every usable item, sorts by score (recency as a stable tiebreak),
 * then applies the diversity post-pass. No Android/DB/UI dependencies — unit-tested directly.
 */
fun buildFeed(
    items: List<FeedCandidate>,
    userTagWeights: Map<String, Double>,
    now: Long,
    weights: RankingWeights = RankingWeights()
): List<FeedCandidate> {
    val scored = items
        .filter { it.status == USABLE_STATUS }
        .map { it to scoreItem(it, userTagWeights, now, weights) }
        .sortedWith(
            compareByDescending<Pair<FeedCandidate, Double>> { it.second }
                .thenByDescending { it.first.createdAt }
        )
    return diversify(scored, weights)
}

internal fun scoreItem(
    item: FeedCandidate,
    userTagWeights: Map<String, Double>,
    now: Long,
    w: RankingWeights
): Double {
    val recency = recencyDecay(now - item.createdAt, w.recencyTauDays)
    // Unseen "age" is time since last view, or since creation if never viewed — so an old,
    // never-viewed save climbs (large unseen) while a brand-new unseen one stays low (small
    // unseen), which is what keeps resurface from double-counting fresh items with recency.
    val unseenMs = now - (item.lastViewedAt ?: item.createdAt)
    val resurface = 1.0 - exp(-daysOf(unseenMs) / w.resurfaceTauDays)
    val affinity = tagAffinity(item.tags, userTagWeights)
    val dateProx = dateProximity(item.eventDate, now, w.dateWindowDays)
    val star = if (item.isStarred) 1.0 else 0.0
    val seen = seenPenalty(item.lastShownAt, now, w.seenTauDays)

    return w.wRecency * recency +
        w.wResurface * resurface +
        w.wAffinity * affinity +
        w.wDate * dateProx +
        w.wStar * star -
        w.pSeen * seen
}

private fun recencyDecay(ageMs: Long, tauDays: Double): Double = exp(-daysOf(ageMs) / tauDays)

private fun tagAffinity(tags: List<String>, weights: Map<String, Double>): Double {
    if (tags.isEmpty() || weights.isEmpty()) return 0.0
    // Average matched weight (unknown tag = 0) — averaging, not summing, so an item isn't boosted
    // just for carrying many tags. Weights are pre-normalized to [-1,1] (dismissed tags subtract).
    return tags.sumOf { weights[it] ?: 0.0 } / tags.size
}

private fun dateProximity(eventDate: Long?, now: Long, windowDays: Int): Double {
    if (eventDate == null || windowDays <= 0) return 0.0
    val daysUntil = ((eventDate - now).toDouble() / MS_PER_DAY).roundToLong()
    if (daysUntil < 0 || daysUntil > windowDays) return 0.0
    return 1.0 - daysUntil.toDouble() / windowDays // ramp: 1.0 today -> 0 at the window edge
}

private fun seenPenalty(lastShownAt: Long?, now: Long, tauDays: Double): Double {
    if (lastShownAt == null) return 0.0
    return exp(-daysOf(now - lastShownAt) / tauDays)
}

/** Delta in days, floored at 0 so clock skew (a future timestamp) can't invert a curve. */
private fun daysOf(deltaMs: Long): Double = max(0.0, deltaMs.toDouble() / MS_PER_DAY)

/**
 * Diversity post-pass: reorder the score-sorted list so no more than [RankingWeights.diversityMaxRun]
 * consecutive items share a type, whenever the type multiset allows it.
 *
 * A naive "defer the next same-type item only when a different-type breaker happens to be nearby"
 * pass fails badly when items cluster by type in the score order (e.g. several articles saved in one
 * burst, all near-tied): the higher-scored breakers get emitted first and the dominant type is
 * stranded in one long run at the tail. So instead this greedily emits, at each step, the
 * highest-scored item whose type isn't at the run limit AND whose removal leaves the remainder still
 * arrangeable (no forced future run > K). If the top-scored choice would strand a dominant type, it
 * falls back to the most-frequent eligible type to keep breakers in reserve. Score order is followed
 * whenever it doesn't threaten the cap, so the feed still leads with its most relevant items.
 */
private fun diversify(scored: List<Pair<FeedCandidate, Double>>, w: RankingWeights): List<FeedCandidate> {
    val k = w.diversityMaxRun
    val queues = LinkedHashMap<String, ArrayDeque<Pair<FeedCandidate, Double>>>()
    for (entry in scored) queues.getOrPut(entry.first.type) { ArrayDeque() }.add(entry)
    val counts = HashMap(queues.mapValues { it.value.size })

    val out = ArrayList<FeedCandidate>(scored.size)
    var lastType: String? = null
    var run = 0

    repeat(scored.size) {
        val blocked = if (run >= k) lastType else null
        val eligible = queues.keys.filter { queues.getValue(it).isNotEmpty() && it != blocked }
        // Highest head score first, but skip a pick that would leave the remainder impossible to
        // arrange within the run cap (that's what reserves breakers for the dominant type).
        val chosen = eligible.sortedByDescending { queues.getValue(it).first().second }
            .firstOrNull { arrangeableAfter(counts, it, k) }
            ?: eligible.maxByOrNull { counts.getValue(it) }
            ?: blocked!! // only the blocked (last remaining) type is left — unavoidable run

        out.add(queues.getValue(chosen).removeFirst().first)
        counts[chosen] = counts.getValue(chosen) - 1
        if (chosen == lastType) run++ else { lastType = chosen; run = 1 }
    }
    return out
}

/** Can the remaining multiset (after removing one [type]) still be arranged with no run > [k]? */
private fun arrangeableAfter(counts: Map<String, Int>, type: String, k: Int): Boolean {
    var total = 0
    var maxCount = 0
    for ((t, c) in counts) {
        val remaining = if (t == type) c - 1 else c
        total += remaining
        if (remaining > maxCount) maxCount = remaining
    }
    if (total == 0) return true
    val others = total - maxCount
    // A multiset is arrangeable with no more than k equal items in a row iff the most frequent type
    // fits in the gaps around the others: maxCount <= k * (others + 1).
    return maxCount <= k.toLong() * (others + 1)
}
