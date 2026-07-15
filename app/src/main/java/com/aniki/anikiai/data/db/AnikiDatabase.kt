package com.aniki.anikiai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ItemEntity::class,
        TagEntity::class,
        ItemTagCrossRef::class,
        EngagementEventEntity::class,
        ItemFtsEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AnikiDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao

    companion object {
        @Volatile
        private var INSTANCE: AnikiDatabase? = null

        /**
         * Adds sync support (entities/tag-tombstone/dirty fields + engagement_events) without
         * wiping the local cache — this device already has real Slice 1/2 data.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()

                db.execSQL("ALTER TABLE items ADD COLUMN entities TEXT")

                db.execSQL("ALTER TABLE tags ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT $now")
                db.execSQL("ALTER TABLE tags ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE tags ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")

                db.execSQL("ALTER TABLE item_tags ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT $now")
                db.execSQL("ALTER TABLE item_tags ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE item_tags ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS engagement_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        itemId TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        value REAL,
                        createdAt INTEGER NOT NULL,
                        dirty INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Adds local full-text search (Slice 4): the items_fts table backing ItemFtsEntity, a
         * standalone FTS4 virtual table (not linked via @Fts4(contentEntity=...) — ItemEntity's
         * PK is a client-generated UUID String, which doesn't fit that pattern's integer-rowid
         * requirement). Kept in sync manually from ItemRepository.syncFtsRow rather than
         * Room-managed triggers. Backfills from existing items (+ their active tags) so data
         * already on-device stays searchable immediately.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING fts4(itemId, title, summary, bodyText, tagsText)"
                )
                db.execSQL(
                    """
                    INSERT INTO items_fts (itemId, title, summary, bodyText, tagsText)
                    SELECT
                        i.id,
                        i.title,
                        COALESCE(i.summary, ''),
                        COALESCE(i.bodyText, ''),
                        COALESCE((
                            SELECT GROUP_CONCAT(t.label, ' ')
                            FROM item_tags it
                            JOIN tags t ON t.id = it.tagId
                            WHERE it.itemId = i.id AND it.deletedAt IS NULL AND t.deletedAt IS NULL
                        ), '')
                    FROM items i
                    WHERE i.deletedAt IS NULL
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AnikiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnikiDatabase::class.java,
                    "aniki.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
