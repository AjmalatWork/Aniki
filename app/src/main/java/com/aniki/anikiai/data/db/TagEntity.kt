package com.aniki.anikiai.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val label: String,
    val origin: String,       // "AI" | "USER"
    val updatedAt: Long,      // content/LWW clock
    val deletedAt: Long? = null,
    val dirty: Boolean = true
)
