package com.aniki.anikiai.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Standalone FTS4 virtual table (not an @Fts4(contentEntity=...) mirror of ItemEntity):
 * ItemEntity's primary key is a client-generated UUID String, but Room's content-linked FTS
 * pattern requires an INTEGER-rowid-compatible content entity, which doesn't fit. So this table
 * has its own independent rowid and a plain (unindexed-by-Room, matched-by-FTS) itemId column,
 * kept in sync manually by ItemRepository.syncFtsRow rather than by Room-managed triggers.
 */
@Fts4
@Entity(tableName = "items_fts")
data class ItemFtsEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Int = 0,
    val itemId: String,
    val title: String,
    val summary: String,
    val bodyText: String,
    val tagsText: String
)
