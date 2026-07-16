package com.aniki.anikiai.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    primaryKeys = ["itemId", "tagId"],
    tableName = "item_tags",
    // KSP flags tagId as uncovered for the ItemWithTags @Relation join (full-table scan risk);
    // itemId is already covered by the primary key's leading column.
    indices = [Index(value = ["tagId"])]
)
data class ItemTagCrossRef(
    val itemId: String,
    val tagId: String,
    val updatedAt: Long,      // content/LWW clock
    val deletedAt: Long? = null,
    val dirty: Boolean = true
)
