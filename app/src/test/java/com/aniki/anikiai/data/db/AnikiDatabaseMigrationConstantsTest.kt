package com.aniki.anikiai.data.db

import com.aniki.anikiai.feed.TagWeightConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards MIGRATION_9_10's one-time historical fold (see AnikiDatabase.BACKFILL_ENGAGEMENT_SIGNAL_SQL):
 * its CASE literals must always match feed.signalForEvent's actual TagWeightConfig() defaults, or a
 * device upgrading with real pre-migration engagement history would fold in the wrong signal. The SQL
 * is built via string interpolation from TagWeightConfig() itself (see AnikiDatabase.kt), so this test
 * is a regression guard against a future edit that replaces that interpolation with a hand-copied,
 * driftable literal -- it parses the actual generated SQL text and checks it against the config
 * directly, rather than trusting the construction.
 */
class AnikiDatabaseMigrationConstantsTest {

    private val sql = AnikiDatabase.BACKFILL_ENGAGEMENT_SIGNAL_SQL
    private val config = TagWeightConfig()

    private fun literalAfter(marker: String): Double {
        val regex = Regex(Regex.escape(marker) + """\s*(-?[0-9]+(?:\.[0-9]+)?)""")
        val match = regex.find(sql) ?: error("could not find a numeric literal after '$marker' in migration SQL")
        return match.groupValues[1].toDouble()
    }

    @Test
    fun `OPENED literal matches TagWeightConfig`() {
        assertEquals(config.opened, literalAfter("WHEN 'OPENED' THEN"), 1e-9)
    }

    @Test
    fun `STARRED literal matches TagWeightConfig`() {
        assertEquals(config.starred, literalAfter("WHEN 'STARRED' THEN"), 1e-9)
    }

    @Test
    fun `DISMISSED literal matches TagWeightConfig`() {
        assertEquals(config.dismissed, literalAfter("WHEN 'DISMISSED' THEN"), 1e-9)
    }

    @Test
    fun `SWIPED_FAST literal matches TagWeightConfig`() {
        assertEquals(config.swipedFast, literalAfter("WHEN 'SWIPED_FAST' THEN"), 1e-9)
    }

    @Test
    fun `DWELL divisor matches TagWeightConfig dwellFullMs`() {
        assertEquals(config.dwellFullMs, literalAfter("COALESCE(e.value, 0.0) /"), 1e-9)
    }

    @Test
    fun `DWELL multiplier matches TagWeightConfig dwellMax`() {
        assertEquals(config.dwellMax, literalAfter(", 1.0) *"), 1e-9)
    }

    @Test
    fun `migration SQL has a CASE branch for every non-SHOWN event type signalForEvent recognizes`() {
        for (type in listOf("OPENED", "STARRED", "DISMISSED", "SWIPED_FAST", "DWELL")) {
            assertTrue("migration SQL should have a CASE branch for $type", sql.contains("WHEN '$type'"))
        }
        // SHOWN deliberately has no branch -- it falls through to ELSE 0.0, matching
        // signalForEvent's "SHOWN ... contribute nothing" (impression-only, not affinity signal).
        assertTrue(sql.contains("ELSE 0.0"))
        assertTrue(!sql.contains("'SHOWN'"))
    }
}
