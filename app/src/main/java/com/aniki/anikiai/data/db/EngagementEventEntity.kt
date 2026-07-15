package com.aniki.anikiai.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Append-only, one-way (client -> server). Nothing consumes these yet — Slice 5 does. */
@Entity(tableName = "engagement_events")
data class EngagementEventEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val eventType: String,
    val value: Double?,
    val createdAt: Long,
    val dirty: Boolean = true
)
