package com.aniki.anikiai.data.repository

import com.aniki.anikiai.data.db.EngagementEventEntity
import com.aniki.anikiai.data.db.ItemEntity
import com.aniki.anikiai.data.db.ItemTagCrossRef
import com.aniki.anikiai.data.db.TagEntity
import com.aniki.anikiai.data.remote.SyncEntitiesDto
import com.aniki.anikiai.data.remote.SyncItemDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the hand-written Entity<->DTO mappers in SyncRepository.kt: every field added to
 * ItemEntity/SyncItemDto (and the other synced entities) must be threaded through toDto()/
 * toEntity() on both client and server, or sync silently drops it. This test round-trips a
 * fully-populated instance through both mapper directions and fails loudly if a field goes
 * missing, rather than relying on someone noticing a sync bug in the field.
 */
class SyncRepositoryMapperTest {

    // Exactly midnight UTC so the day-granularity eventDate<->ISO-date conversion is lossless.
    private val midnightUtcMillis = 1_700_000_000_000L / 86_400_000L * 86_400_000L

    private fun fullDto(): SyncItemDto = SyncItemDto(
        id = "item-1",
        type = "WEB_ARTICLE",
        sourceUrl = "https://example.com/article",
        normalizedUrl = "example.com/article",
        title = "A Title",
        bodyText = "Some body text",
        summary = "A summary",
        thumbnailUrl = "https://example.com/thumb.png",
        category = "Tech",
        entities = SyncEntitiesDto(people = listOf("Ada"), places = listOf("Cambridge"), dates = listOf("2024-01-01")),
        eventDate = "2023-11-14",
        status = "ENRICHED",
        isStarred = true,
        summaryLocked = true,
        tagsLocked = false,
        updatedAt = 1_700_000_000_000L,
        deletedAt = 1_700_000_500_000L,
        seq = 42
    )

    /** DTO -> Entity -> DTO must be lossless: this is what a pull-then-push (or a field forgotten
     * in toEntity()) would break. `seq` is intentionally excluded — the entity has no seq column,
     * it's a server-assigned replication cursor with no local counterpart. */
    @Test
    fun dtoToEntityToDto_isLosslessExceptSeq() {
        val original = fullDto()
        val roundTripped = original.toEntity(dirty = false).toDto()

        assertEquals(original.copy(seq = 0), roundTripped)
    }

    /** Entity -> DTO -> Entity is lossy by documented design: `createdAt` has no server
     * counterpart and is best-efforted to `updatedAt` on the way back, and `dirty`/`seq` aren't
     * part of the DTO contract at all. Every other field must survive intact. */
    @Test
    fun entityToDtoToEntity_preservesAllFieldsExceptCreatedAtAndDirty() {
        val original = ItemEntity(
            id = "item-2",
            type = "YOUTUBE_VIDEO",
            sourceUrl = "https://youtube.com/watch?v=abc",
            normalizedUrl = "youtube.com/watch?v=abc",
            title = "A Video",
            bodyText = null,
            summary = "Video summary",
            thumbnailUrl = "https://img.example/thumb.jpg",
            category = "Entertainment",
            entities = null,
            eventDate = midnightUtcMillis,
            status = "ENRICHED",
            isStarred = false,
            summaryEditedByUser = true,
            tagsEditedByUser = true,
            createdAt = midnightUtcMillis - 1_000, // deliberately != updatedAt to prove the lossy field
            lastViewedAt = 123L,
            lastShownAt = 456L,
            updatedAt = midnightUtcMillis,
            deletedAt = null,
            dirty = true
        )

        val roundTripped = original.toDto().toEntity(dirty = false)

        assertEquals(original.id, roundTripped.id)
        assertEquals(original.type, roundTripped.type)
        assertEquals(original.sourceUrl, roundTripped.sourceUrl)
        assertEquals(original.normalizedUrl, roundTripped.normalizedUrl)
        assertEquals(original.title, roundTripped.title)
        assertEquals(original.bodyText, roundTripped.bodyText)
        assertEquals(original.summary, roundTripped.summary)
        assertEquals(original.thumbnailUrl, roundTripped.thumbnailUrl)
        assertEquals(original.category, roundTripped.category)
        assertEquals(original.entities, roundTripped.entities)
        assertEquals(original.eventDate, roundTripped.eventDate)
        assertEquals(original.status, roundTripped.status)
        assertEquals(original.isStarred, roundTripped.isStarred)
        assertEquals(original.summaryEditedByUser, roundTripped.summaryEditedByUser)
        assertEquals(original.tagsEditedByUser, roundTripped.tagsEditedByUser)
        assertEquals(original.updatedAt, roundTripped.updatedAt)
        assertEquals(original.deletedAt, roundTripped.deletedAt)

        // Documented lossy fields: createdAt collapses to updatedAt; lastViewedAt/lastShownAt
        // and dirty aren't part of the sync contract at all (fresh defaults on the way back).
        assertEquals(roundTripped.updatedAt, roundTripped.createdAt)
        assertEquals(null, roundTripped.lastViewedAt)
        assertEquals(null, roundTripped.lastShownAt)
    }

    @Test
    fun tagEntity_toDto_mapsEveryField() {
        val tag = TagEntity(id = "tag-1", label = "kotlin", origin = "USER", updatedAt = 1000L, deletedAt = 2000L)
        val dto = tag.toDto()

        assertEquals(tag.id, dto.id)
        assertEquals(tag.label, dto.label)
        assertEquals(tag.origin, dto.origin)
        assertEquals(tag.updatedAt, dto.updatedAt)
        assertEquals(tag.deletedAt, dto.deletedAt)
    }

    @Test
    fun itemTagCrossRef_toDto_mapsEveryField() {
        val crossRef = ItemTagCrossRef(itemId = "item-1", tagId = "tag-1", updatedAt = 1000L, deletedAt = null)
        val dto = crossRef.toDto()

        assertEquals(crossRef.itemId, dto.itemId)
        assertEquals(crossRef.tagId, dto.tagId)
        assertEquals(crossRef.updatedAt, dto.updatedAt)
        assertEquals(crossRef.deletedAt, dto.deletedAt)
    }

    @Test
    fun engagementEventEntity_toDto_mapsEveryField() {
        val event = EngagementEventEntity(
            id = "event-1",
            itemId = "item-1",
            eventType = "OPENED",
            value = 12.5,
            createdAt = 1000L
        )
        val dto = event.toDto()

        assertEquals(event.id, dto.id)
        assertEquals(event.itemId, dto.itemId)
        assertEquals(event.eventType, dto.eventType)
        assertEquals(event.value, dto.value)
        assertEquals(event.createdAt, dto.createdAt)
    }
}
