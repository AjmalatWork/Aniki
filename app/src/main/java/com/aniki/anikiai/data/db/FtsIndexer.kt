package com.aniki.anikiai.data.db

/**
 * Single home for keeping items_fts (the standalone FTS4 table, see AnikiDatabase.MIGRATION_2_3)
 * in sync with an item's searchable content. Both the local-write path (ItemRepository) and the
 * sync-merge path (SyncRepository) index through here, so the two can never drift — which is the
 * exact bug class that let Slice 4 ship a sync path that never indexed pulled rows.
 *
 * Every op is delete-then-(maybe)insert, so it's idempotent: re-indexing the same item twice, or
 * indexing an item mid-pull before its tags have merged and again after, both converge correctly.
 */
class FtsIndexer(private val itemDao: ItemDao) {

    /** Rebuild one item's FTS row from current state. No-op-to-empty if the item is gone or tombstoned. */
    suspend fun index(itemId: String) {
        val item = itemDao.getItemById(itemId)
        itemDao.deleteFtsRow(itemId)
        if (item == null || item.deletedAt != null) return

        val tags = itemDao.getActiveCrossRefsForItem(itemId).mapNotNull { itemDao.getTagById(it.tagId) }
        itemDao.insertFtsRow(
            ItemFtsEntity(
                itemId = itemId,
                title = item.title,
                summary = item.summary.orEmpty(),
                bodyText = item.bodyText.orEmpty(),
                tagsText = tags.joinToString(" ") { it.label }
            )
        )
    }

    suspend fun remove(itemId: String) {
        itemDao.deleteFtsRow(itemId)
    }

    /**
     * Self-heal: index every non-deleted item that isn't already in items_fts. Cheap and
     * idempotent, so it can run unconditionally on start — an install that synced before the
     * Slice-4 FTS gap was fixed gets its pulled-but-unindexed items indexed here.
     */
    suspend fun backfillMissing() {
        itemDao.getActiveItemIdsMissingFromFts().forEach { index(it) }
    }
}
