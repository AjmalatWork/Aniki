package com.aniki.anikiai.util

import com.aniki.anikiai.data.db.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the client/server contract for EnrichmentErrorCode (M3 of the maintainability audit):
 * server/src/types.ts defines the canonical EnrichmentErrorCode union; util/EnrichmentMessages.kt
 * re-declares the same values as a Kotlin `object` with no compile-time link to the TS type. If a
 * code is ever added on one side and not the other, enrichmentErrorMessageShort/Long silently fall
 * through to their generic `else` copy at runtime instead of failing loudly (the exact failure mode
 * this test exists to catch instead).
 *
 * [SERVER_DEFINED_ERROR_CODES] is a hand-maintained mirror of server/src/types.ts's
 * EnrichmentErrorCode union. There is no cross-language codegen link between a TS union type (which
 * has no runtime representation to read programmatically) and this Kotlin list -- keeping the two
 * in sync when either side adds/removes a code is still a manual step, but this test turns "forgot
 * to update the other side" into a failing test instead of a silent fallback at runtime. If you add
 * a code here, also add it to server/src/types.ts's EnrichmentErrorCode (and vice versa).
 */
private val SERVER_DEFINED_ERROR_CODES = listOf(
    EnrichmentErrorCode.RATE_LIMITED,
    EnrichmentErrorCode.QUOTA_EXCEEDED,
    EnrichmentErrorCode.FETCH_FAILED,
    EnrichmentErrorCode.EXTRACTION_FAILED,
    EnrichmentErrorCode.GENERIC
)

/** GENERIC is the one code deliberately *not* distinguished from the null/unrecognized-code
 *  fallback (see EnrichmentMessages.kt's `else` branch) -- it's the intentional catch-all, so its
 *  message is expected to equal the fallback, not diverge from it like every other real code. */
private val CODES_EXPECTED_TO_HAVE_CURATED_COPY = SERVER_DEFINED_ERROR_CODES - EnrichmentErrorCode.GENERIC

private val ALL_ITEM_TYPES = listOf(ItemType.WEB_ARTICLE, ItemType.YOUTUBE_VIDEO, ItemType.NOTE)

class EnrichmentMessagesTest {

    @Test
    fun everyCuratedErrorCode_hasANonGenericShortMessage_forEveryItemType() {
        for (code in CODES_EXPECTED_TO_HAVE_CURATED_COPY) {
            for (itemType in ALL_ITEM_TYPES) {
                assertNotEquals(
                    "error code '$code' (itemType=$itemType) fell through to the generic short message " +
                        "-- add a branch for it in enrichmentErrorMessageShort",
                    enrichmentErrorMessageShort(null, itemType),
                    enrichmentErrorMessageShort(code, itemType)
                )
            }
        }
    }

    @Test
    fun everyCuratedErrorCode_hasANonGenericLongMessage_forEveryItemType() {
        for (code in CODES_EXPECTED_TO_HAVE_CURATED_COPY) {
            for (itemType in ALL_ITEM_TYPES) {
                assertNotEquals(
                    "error code '$code' (itemType=$itemType) fell through to the generic long message " +
                        "-- add a branch for it in enrichmentErrorMessageLong",
                    enrichmentErrorMessageLong(null, itemType),
                    enrichmentErrorMessageLong(code, itemType)
                )
            }
        }
    }

    @Test
    fun generic_deliberatelySharesTheFallbackCopy_withNullOrAnUnrecognizedCode() {
        for (itemType in ALL_ITEM_TYPES) {
            assertEquals(
                enrichmentErrorMessageShort(null, itemType),
                enrichmentErrorMessageShort(EnrichmentErrorCode.GENERIC, itemType)
            )
            assertEquals(
                enrichmentErrorMessageLong(null, itemType),
                enrichmentErrorMessageLong(EnrichmentErrorCode.GENERIC, itemType)
            )
        }
    }

    @Test
    fun everyServerDefinedErrorCode_hasAnEnrichmentCanRetryEntry() {
        // enrichmentCanRetry's `when` always has an else branch, so this can't literally "throw" for
        // an unhandled code today -- the real regression this guards is a *future* refactor to an
        // exhaustive `when` (no else) that would then fail to compile for a genuinely new code but
        // silently do the wrong thing for one of these five if the branch were simply missing. Fixing
        // the exact known set here documents the deliberate current retry-eligibility for each code
        // (see EnrichmentMessages.kt's doc), so an accidental change shows up as a diff here.
        assertEquals(false, enrichmentCanRetry(EnrichmentErrorCode.QUOTA_EXCEEDED))
        assertEquals(false, enrichmentCanRetry(EnrichmentErrorCode.EXTRACTION_FAILED))
        assertEquals(true, enrichmentCanRetry(EnrichmentErrorCode.RATE_LIMITED))
        assertEquals(true, enrichmentCanRetry(EnrichmentErrorCode.FETCH_FAILED))
        assertEquals(true, enrichmentCanRetry(EnrichmentErrorCode.GENERIC))
    }

    @Test
    fun nullOrAnUnrecognizedCode_fallsBackToTheGenericMessage_notACrash() {
        assertTrue(enrichmentErrorMessageShort(null, ItemType.NOTE).isNotBlank())
        assertTrue(enrichmentErrorMessageShort("SOME_FUTURE_CODE_NOT_YET_HANDLED", ItemType.NOTE).isNotBlank())
        assertTrue(enrichmentCanRetry("SOME_FUTURE_CODE_NOT_YET_HANDLED"))
    }
}
