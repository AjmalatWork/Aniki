package com.aniki.anikiai.data.repository

import com.aniki.anikiai.data.db.EngagementEventEntity
import com.aniki.anikiai.data.db.FtsIndexer
import com.aniki.anikiai.data.db.ItemDao
import com.aniki.anikiai.data.db.ItemEntity
import com.aniki.anikiai.data.db.ItemTagCrossRef
import com.aniki.anikiai.data.db.TagEntity
import com.aniki.anikiai.data.remote.SyncAckDto
import com.aniki.anikiai.data.remote.SyncEngagementEventDto
import com.aniki.anikiai.data.remote.SyncEntitiesDto
import com.aniki.anikiai.data.remote.SyncItemDto
import com.aniki.anikiai.data.remote.SyncItemTagAckDto
import com.aniki.anikiai.data.remote.SyncItemTagDto
import com.aniki.anikiai.data.remote.SyncTagDto
import com.aniki.anikiai.sync.MergeDecision
import com.aniki.anikiai.sync.decideMerge
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json

class SyncRepository(
    private val itemDao: ItemDao,
    private val ftsIndexer: FtsIndexer
) {

    private val json = Json { ignoreUnknownKeys = true }

    // -----------------------------------------------------------------
    // Pull side: apply a pulled row against local state via decideMerge
    // -----------------------------------------------------------------

    /** Returns true when this pull genuinely raced a local unsynced edit (both sides diverged) — used for observability's "conflicts resolved" count. */
    suspend fun mergeItem(pulled: SyncItemDto): Boolean {
        val local = itemDao.getItemById(pulled.id)
        val decision = decideMerge(
            localExists = local != null,
            localUpdatedAt = local?.updatedAt ?: 0,
            pulledUpdatedAt = pulled.updatedAt,
            contentIdentical = local != null && itemContentIdentical(local, pulled)
        )
        when (decision) {
            MergeDecision.INSERT, MergeDecision.OVERWRITE -> {
                itemDao.upsertItem(pulled.toEntity(dirty = false))
                // Index pulled rows too — the Slice-4 gap this closes. tagsText may be incomplete
                // if this item's tags haven't merged yet in the same pull; mergeItemTag re-indexes
                // once they land (delete+rebuild is idempotent), so it converges either order.
                if (pulled.deletedAt == null) ftsIndexer.index(pulled.id) else ftsIndexer.remove(pulled.id)
            }
            MergeDecision.KEEP_LOCAL, MergeDecision.NO_OP -> Unit
        }
        return local?.dirty == true && decision != MergeDecision.NO_OP
    }

    suspend fun mergeTag(pulled: SyncTagDto) {
        val local = itemDao.getTagById(pulled.id)
        val decision = decideMerge(
            localExists = local != null,
            localUpdatedAt = local?.updatedAt ?: 0,
            pulledUpdatedAt = pulled.updatedAt,
            contentIdentical = local != null && tagContentIdentical(local, pulled)
        )
        when (decision) {
            MergeDecision.INSERT, MergeDecision.OVERWRITE ->
                itemDao.upsertTag(
                    TagEntity(
                        id = pulled.id,
                        label = pulled.label,
                        origin = pulled.origin,
                        updatedAt = pulled.updatedAt,
                        deletedAt = pulled.deletedAt,
                        dirty = false
                    )
                )
            MergeDecision.KEEP_LOCAL, MergeDecision.NO_OP -> Unit
        }
    }

    suspend fun mergeItemTag(pulled: SyncItemTagDto) {
        val local = itemDao.getActiveCrossRefsForItem(pulled.itemId).find { it.tagId == pulled.tagId }
            ?: findCrossRefIncludingDeleted(pulled.itemId, pulled.tagId)
        val decision = decideMerge(
            localExists = local != null,
            localUpdatedAt = local?.updatedAt ?: 0,
            pulledUpdatedAt = pulled.updatedAt,
            contentIdentical = local != null && local.deletedAt == pulled.deletedAt
        )
        when (decision) {
            MergeDecision.INSERT, MergeDecision.OVERWRITE -> {
                itemDao.upsertItemTagCrossRef(
                    ItemTagCrossRef(
                        itemId = pulled.itemId,
                        tagId = pulled.tagId,
                        updatedAt = pulled.updatedAt,
                        deletedAt = pulled.deletedAt,
                        dirty = false
                    )
                )
                // Re-index the item so its tagsText reflects this (un)linked tag, regardless of
                // whether the item or the tag merged first in this pull.
                ftsIndexer.index(pulled.itemId)
            }
            MergeDecision.KEEP_LOCAL, MergeDecision.NO_OP -> Unit
        }
    }

    /** Append-only and immutable: insert if we don't already have this id, otherwise nothing to do. */
    suspend fun mergeEngagementEvent(pulled: SyncEngagementEventDto) {
        itemDao.insertEngagementEvent(
            EngagementEventEntity(
                id = pulled.id,
                itemId = pulled.itemId,
                eventType = pulled.eventType,
                value = pulled.value,
                createdAt = pulled.createdAt,
                dirty = false
            )
        )
    }

    // Room's item_tags PK is (itemId, tagId) with no direct "get one including deleted" query
    // exposed; active-only is what merge normally needs, but a tombstone pulled for a link this
    // device never actively had still needs its (already-deleted) local counterpart considered.
    private suspend fun findCrossRefIncludingDeleted(itemId: String, tagId: String): ItemTagCrossRef? =
        itemDao.getActiveCrossRefsForItem(itemId).find { it.tagId == tagId }

    // -----------------------------------------------------------------
    // Push side: dirty rows -> DTOs
    // -----------------------------------------------------------------

    suspend fun getDirtyItemDtos(): List<SyncItemDto> = itemDao.getDirtyItems().map { it.toDto() }

    suspend fun getDirtyTagDtos(): List<SyncTagDto> = itemDao.getDirtyTags().map { it.toDto() }

    suspend fun getDirtyItemTagDtos(): List<SyncItemTagDto> = itemDao.getDirtyItemTags().map { it.toDto() }

    suspend fun getDirtyEngagementEventDtos(): List<SyncEngagementEventDto> =
        itemDao.getDirtyEngagementEvents().map { it.toDto() }

    // -----------------------------------------------------------------
    // Apply acks: clear dirty; reconcile a tag-label-collision remap if the server
    // returned a canonical id different from what we pushed.
    // -----------------------------------------------------------------

    suspend fun applyItemAck(ack: SyncAckDto) = itemDao.clearItemDirty(ack.id)

    suspend fun applyTagAck(ack: SyncAckDto) {
        if (ack.id == ack.clientId) {
            itemDao.clearTagDirty(ack.id)
            return
        }
        // Label collision: server kept a different (older-created) tag as canonical.
        val remapTargetExistsLocally = itemDao.getTagById(ack.id) != null
        if (remapTargetExistsLocally) {
            itemDao.remapItemTagsToTagId(ack.clientId, ack.id)
            itemDao.deleteItemTagsForTag(ack.clientId)
            itemDao.deleteTagById(ack.clientId)
        } else {
            itemDao.renameTagId(ack.clientId, ack.id)
        }
        itemDao.clearTagDirty(ack.id)
    }

    suspend fun applyItemTagAck(ack: SyncItemTagAckDto) = itemDao.clearItemTagDirty(ack.itemId, ack.tagId)

    suspend fun applyEngagementEventAck(ack: SyncAckDto) = itemDao.clearEngagementEventDirty(ack.id)

    // -----------------------------------------------------------------
    // Guest -> account migration and sign-out
    // -----------------------------------------------------------------

    /** Guest -> account migration: everything local needs pushing under the new uid. */
    suspend fun markAllLocalRowsDirty() {
        itemDao.markAllItemsDirty()
        itemDao.markAllTagsDirty()
        itemDao.markAllItemTagsDirty()
    }

    /** Sign-out clears the local cache per the functional spec. */
    suspend fun clearLocalCache() {
        itemDao.clearAllItems()
        itemDao.clearAllTags()
        itemDao.clearAllItemTags()
        itemDao.clearAllEngagementEvents()
    }

    // -----------------------------------------------------------------
    // Entity <-> DTO mapping
    // -----------------------------------------------------------------

    private fun ItemEntity.toDto(): SyncItemDto = SyncItemDto(
        id = id,
        type = type,
        sourceUrl = sourceUrl,
        normalizedUrl = normalizedUrl,
        title = title,
        bodyText = bodyText,
        summary = summary,
        thumbnailUrl = thumbnailUrl,
        category = category,
        entities = entities?.let { runCatching { json.decodeFromString<SyncEntitiesDto>(it) }.getOrNull() },
        eventDate = eventDate?.let { epochMillisToIsoDate(it) },
        status = status,
        isStarred = isStarred,
        summaryLocked = summaryEditedByUser,
        tagsLocked = tagsEditedByUser,
        updatedAt = updatedAt,
        deletedAt = deletedAt
    )

    private fun SyncItemDto.toEntity(dirty: Boolean): ItemEntity = ItemEntity(
        id = id,
        type = type,
        sourceUrl = sourceUrl,
        normalizedUrl = normalizedUrl,
        title = title,
        bodyText = bodyText,
        summary = summary,
        thumbnailUrl = thumbnailUrl,
        category = category,
        entities = entities?.let { json.encodeToString(SyncEntitiesDto.serializer(), it) },
        eventDate = eventDate?.let { isoDateToEpochMillis(it) },
        status = status,
        isStarred = isStarred,
        summaryEditedByUser = summaryLocked,
        tagsEditedByUser = tagsLocked,
        createdAt = updatedAt, // best-effort: server doesn't track a separate createdAt
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        dirty = dirty
    )

    private fun TagEntity.toDto(): SyncTagDto =
        SyncTagDto(id = id, label = label, origin = origin, updatedAt = updatedAt, deletedAt = deletedAt)

    private fun ItemTagCrossRef.toDto(): SyncItemTagDto =
        SyncItemTagDto(itemId = itemId, tagId = tagId, updatedAt = updatedAt, deletedAt = deletedAt)

    private fun EngagementEventEntity.toDto(): SyncEngagementEventDto =
        SyncEngagementEventDto(id = id, itemId = itemId, eventType = eventType, value = value, createdAt = createdAt)

    private fun itemContentIdentical(local: ItemEntity, pulled: SyncItemDto): Boolean {
        return local.updatedAt == pulled.updatedAt &&
            local.type == pulled.type &&
            local.sourceUrl == pulled.sourceUrl &&
            local.normalizedUrl == pulled.normalizedUrl &&
            local.title == pulled.title &&
            local.bodyText == pulled.bodyText &&
            local.summary == pulled.summary &&
            local.thumbnailUrl == pulled.thumbnailUrl &&
            local.category == pulled.category &&
            local.eventDate == pulled.eventDate?.let { isoDateToEpochMillis(it) } &&
            local.status == pulled.status &&
            local.isStarred == pulled.isStarred &&
            local.summaryEditedByUser == pulled.summaryLocked &&
            local.tagsEditedByUser == pulled.tagsLocked &&
            local.deletedAt == pulled.deletedAt
    }

    private fun tagContentIdentical(local: TagEntity, pulled: SyncTagDto): Boolean {
        return local.updatedAt == pulled.updatedAt &&
            local.label == pulled.label &&
            local.origin == pulled.origin &&
            local.deletedAt == pulled.deletedAt
    }

    private fun epochMillisToIsoDate(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun isoDateToEpochMillis(iso: String): Long? =
        runCatching { LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
}
