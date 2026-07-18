package com.aniki.anikiai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aniki.anikiai.feed.TagWeightConfig

@Database(
    entities = [
        ItemEntity::class,
        TagEntity::class,
        ItemTagCrossRef::class,
        EngagementEventEntity::class,
        ItemFtsEntity::class
    ],
    version = 11,
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

        /**
         * Adds errorCode/errorMessage (local-only, not synced) -- persists *why* the last
         * enrichment attempt landed on NEEDS_ATTENTION (quota/rate-limit/fetch/extraction failure,
         * from the server's EnrichmentErrorCode) so the UI can show the real message instead of one
         * hardcoded generic string. Existing rows default to NULL, which is correct: any item
         * already sitting in NEEDS_ATTENTION before this migration has no recorded reason, so it
         * falls back to the old generic copy until its next enrichment attempt fills these in.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN errorCode TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN errorMessage TEXT")
            }
        }

        /**
         * Adds engagementSignal (local-only, not synced): a running per-item total of engagement
         * affinity signal, replacing a full engagement_events table scan on every Feed refresh
         * (ItemRepository.computeUserTagWeights) with an incrementally-maintained column summed
         * with one cheap GROUP BY (ItemDao.getTagSignalTotals) -- see feed.signalForEvent, folded
         * in going forward by ItemRepository.recordEvent / SyncRepository.mergeEngagementEvent.
         *
         * Existing devices (real friend-testing data) get a one-time historical fold of every
         * engagement_events row they already have, right here, before either the client-side
         * prune (work/EngagementEventPurger) or the server's engagement-event GC can ever run --
         * that ordering is what makes pruning safe: nothing is discarded before its signal has
         * somewhere durable to live. The CASE literals are TagWeightConfig()'s actual defaults,
         * interpolated at compile time (not hand-copied) from the same Kotlin object
         * feed.signalForEvent reads -- AnikiDatabaseMigrationConstantsTest still asserts this SQL
         * text matches TagWeightConfig() as a regression guard against a future edit reintroducing
         * a hardcoded, driftable literal here.
         */
        /**
         * Built as its own property (rather than inlined in [MIGRATION_9_10]) so
         * AnikiDatabaseMigrationConstantsTest -- a plain JVM test, no Android instrumentation --
         * can inspect the generated SQL text directly and assert its literals still match
         * [TagWeightConfig]'s current defaults, without needing to execute a real migration.
         */
        internal val BACKFILL_ENGAGEMENT_SIGNAL_SQL: String = run {
            val c = TagWeightConfig()
            """
            UPDATE items SET engagementSignal = COALESCE((
                SELECT SUM(
                    CASE e.eventType
                        WHEN 'OPENED' THEN ${c.opened}
                        WHEN 'STARRED' THEN ${c.starred}
                        WHEN 'DISMISSED' THEN ${c.dismissed}
                        WHEN 'SWIPED_FAST' THEN ${c.swipedFast}
                        WHEN 'DWELL' THEN MIN(COALESCE(e.value, 0.0) / ${c.dwellFullMs}, 1.0) * ${c.dwellMax}
                        ELSE 0.0
                    END
                )
                FROM engagement_events e
                WHERE e.itemId = items.id
            ), 0.0)
            """.trimIndent()
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN engagementSignal REAL NOT NULL DEFAULT 0")
                db.execSQL(BACKFILL_ENGAGEMENT_SIGNAL_SQL)
            }
        }

        /**
         * Purely additive perf migration (S3 of the maintainability audit, no data/behavior
         * change): an index on items.deletedAt, which `WHERE deletedAt IS NULL`/`IS NOT NULL`
         * gates in nearly every hot read query (observeAllItems, observeAllItemsWithTags, search,
         * getAllItemsWithTags) plus the trash purge scan (getExpiredTrashItemIds) -- previously
         * unindexed despite being the single most common predicate in the whole query set, same
         * category of gap MIGRATION_3_4 closed for normalizedUrl/createdAt/status.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_deletedAt ON items(deletedAt)")
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
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
                ).build().also { INSTANCE = it }
            }
        }
    }
}
