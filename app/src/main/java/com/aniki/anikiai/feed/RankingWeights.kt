package com.aniki.anikiai.feed

/**
 * Every tunable in the ranking function, in one place (no magic numbers scattered through the
 * logic). Defaults are the shipped tuning; tests and future tuning pass their own instance.
 *
 * Score (per usable item):
 *   score = W_RECENCY*recency + W_RESURFACE*resurface + W_AFFINITY*affinity
 *         + W_DATE*dateProximity + W_STAR*star - P_SEEN*seenPenalty
 */
data class RankingWeights(
    // Term weights.
    val wRecency: Double = 1.0,
    val wResurface: Double = 1.0,   // core anti-graveyard term; on par with recency so old-unseen climbs to parity then overtakes
    val wAffinity: Double = 0.8,
    val wDate: Double = 2.0,        // large so an imminent eventDate jumps an item toward the top
    val wStar: Double = 0.6,
    val pSeen: Double = 1.5,        // strong enough that a just-shown item drops below fresh peers

    // Curve shape.
    val recencyTauDays: Double = 7.0,    // exp decay; ~0.37 at 1wk, ~0.14 at 2wk
    val resurfaceTauDays: Double = 10.0, // saturating rise; ~0.63 at 10d unseen, ~0.95 at 30d
    val seenTauDays: Double = 0.5,       // ~12h; ~1.0 just-shown, ~0.13 a day later
    val dateWindowDays: Int = 7,         // eventDate boost applies within this many days

    // Diversity post-pass: no more than this many consecutive items of one type (enforced whenever
    // the type multiset allows it; see Ranking.diversify).
    val diversityMaxRun: Int = 2
)

/** Engagement -> per-tag signal weights (Task 2), also centralized. */
data class TagWeightConfig(
    val opened: Double = 1.0,
    val starred: Double = 2.0,
    val dismissed: Double = -1.0,
    val swipedFast: Double = -0.5,
    val dwellMax: Double = 1.0,          // a full-dwell event contributes this
    val dwellFullMs: Double = 30_000.0   // dwell at/above this many ms counts as a full dwell
)
