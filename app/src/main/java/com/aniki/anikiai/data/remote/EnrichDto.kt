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

@Serializable
data class EnrichErrorDto(val error: String)
