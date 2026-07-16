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
    version = 8,
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

        /**
         * Purely additive perf migration (no data/behavior change): indexes on items.normalizedUrl
         * (dedupe lookup on every save), items.createdAt (default sort order), items.status
         * (pending-items reconciliation scan) — all previously unindexed full-table scans — plus
         * item_tags.tagId, which KSP flags as uncovered for the ItemWithTags @Relation join.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_normalizedUrl ON items(normalizedUrl)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_createdAt ON items(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_status ON items(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_item_tags_tagId ON item_tags(tagId)")
            }
        }

        /**
         * Adds thumbnailBackfillAttempted (local-only, not synced): lets the lazy OG-image
         * backfill (polish-pass item 2) mark an article as "tried" so a dead/unreachable URL
         * isn't re-attempted forever. Existing rows default to 0 (not yet attempted), which is
         * correct -- the backfill will pick them up on the next sync.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN thumbnailBackfillAttempted INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds titleEditedByUser -- same edit-lock pattern as summaryEditedByUser/tagsEditedByUser
         * (polish-pass item 4: note titles are now Gemini-generated and user-editable, so they
         * need the same "never overwritten by re-enrichment once edited" guard). Existing rows
         * default to 0 (not user-edited), which is correct: no title editing UI existed before
         * this, so nothing could have set it.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN titleEditedByUser INTEGER NOT NULL DEFAULT 0")
                // Local-only cap on the note-title backfill (see ItemEntity's field doc) --
                // bundled into this same migration since both ship in the same feature pass.
                db.execSQL("ALTER TABLE items ADD COLUMN titleBackfillAttempted INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds isDemo (local-only, not synced): flags the onboarding "how sharing works" demo
         * item (ui/onboarding/ShareTipScreen.kt). It's otherwise a normal, visible item -- isDemo
         * only gates sync and enrichment, not Library/Feed/search visibility. Existing rows
         * default to 0 -- correct, since no demo item could have existed before this feature
         * shipped.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN isDemo INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds demoLandingAnimationShown (local-only, not synced): whether the Feed's one-time
         * "you just shared this" landing animation has already played for the onboarding demo
         * item. Existing rows default to 0 -- harmless for every non-demo row, since the flag is
         * only ever read/written when isDemo is true.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN demoLandingAnimationShown INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AnikiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnikiDatabase::class.java,
                    "aniki.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8
                ).build().also { INSTANCE = it }
            }
        }
    }
}
