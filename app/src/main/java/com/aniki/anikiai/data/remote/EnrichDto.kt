package com.aniki.anikiai.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class EnrichRequestDto(
    val id: String,
    val type: String,
    val sourceUrl: String?,
    val bodyText: String?
)

@Serializable
data class EnrichEntitiesDto(
    val people: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val dates: List<String> = emptyList()
)

@Serializable
data class EnrichResponseDto(
    val id: String,
    val title: String?,
    val summary: String,
    val category: String,
    val tags: List<String> = emptyList(),
    val entities: EnrichEntitiesDto = EnrichEntitiesDto(),
    val thumbnailUrl: String?,
    val eventDate: String?
)

/** [code] is one of RATE_LIMITED | QUOTA_EXCEEDED | FETCH_FAILED | EXTRACTION_FAILED | GENERIC
 *  (server/src/types.ts's EnrichmentErrorCode) -- null for a malformed/unexpected error body. */
@Serializable
data class EnrichErrorDto(val error: String, val code: String? = null)

@Serializable
data class ExtractThumbnailRequestDto(val sourceUrl: String)

@Serializable
data class ExtractThumbnailResponseDto(val thumbnailUrl: String?)
