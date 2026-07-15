package com.aniki.anikiai.data.db

import androidx.room.Entity

@Entity(primaryKeys = ["itemId", "tagId"], tableName = "item_tags")
data class ItemTagCrossRef(
    val itemId: String,
    val tagId: String,
    val updatedAt: Long,      // content/LWW clock
    val deletedAt: Long? = null,
    val dirty: Boolean = true
)
