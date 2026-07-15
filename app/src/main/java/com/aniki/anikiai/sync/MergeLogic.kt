package com.aniki.anikiai.sync

enum class MergeDecision {
    /** No local row exists — write the pulled row as-is, clean (not dirty). */
    INSERT,

    /** Pulled is strictly newer, or tied with different content — pulled wins, overwrite local. */
    OVERWRITE,

    /** Local is strictly newer than pulled — keep local untouched, it stays dirty for push. */
    KEEP_LOCAL,

    /** Tied updatedAt and identical content — a true no-op; touching local would be needless churn. */
    NO_OP
}

/**
 * The row-level last-write-wins rule, isolated from Room/Retrofit/entity shape so it's trivially
 * unit-testable. Ties (pulledUpdatedAt == localUpdatedAt) go to the pulled row UNLESS content is
 * byte-identical, in which case it's a no-op — this is what makes self-echoed pulls (a device
 * re-seeing its own just-pushed row) and repeated no-change syncs produce zero local writes.
 */
fun decideMerge(
    localExists: Boolean,
    localUpdatedAt: Long,
    pulledUpdatedAt: Long,
    contentIdentical: Boolean
): MergeDecision {
    if (!localExists) return MergeDecision.INSERT
    if (pulledUpdatedAt < localUpdatedAt) return MergeDecision.KEEP_LOCAL
    if (pulledUpdatedAt == localUpdatedAt && contentIdentical) return MergeDecision.NO_OP
    return MergeDecision.OVERWRITE
}
