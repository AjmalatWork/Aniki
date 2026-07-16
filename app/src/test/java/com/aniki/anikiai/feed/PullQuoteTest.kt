package com.aniki.anikiai.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class PullQuoteTest {

    @Test
    fun `blank summary falls back to title`() {
        assertEquals("Fallback Title", extractPullQuote(null, "Fallback Title"))
        assertEquals("Fallback Title", extractPullQuote("   ", "Fallback Title"))
    }

    @Test
    fun `first sentence is used when it clears the minimum length`() {
        val summary = "This is a genuinely strong first sentence. This is the second one."
        assertEquals("This is a genuinely strong first sentence.", extractPullQuote(summary, "Title"))
    }

    @Test
    fun `stub first sentence falls back to the second sentence`() {
        val summary = "Ok. This second sentence is long enough to actually work as a pull-quote."
        assertEquals(
            "This second sentence is long enough to actually work as a pull-quote.",
            extractPullQuote(summary, "Title")
        )
    }

    @Test
    fun `both sentences too short falls back to the whole summary rather than an empty stub`() {
        val summary = "Ok. Fine."
        assertEquals("Ok. Fine.", extractPullQuote(summary, "Title"))
    }

    @Test
    fun `single unbroken long sentence with no punctuation is used as-is`() {
        val summary = "a".repeat(300)
        assertEquals(summary, extractPullQuote(summary, "Title"))
    }

    @Test
    fun `exclamation and question marks count as sentence boundaries`() {
        val summary = "Wait, what?! This is the much longer and more substantial second sentence here."
        assertEquals(
            "This is the much longer and more substantial second sentence here.",
            extractPullQuote(summary, "Title")
        )
    }

    @Test
    fun `font size steps down as the quote gets longer`() {
        assertEquals(34, pullQuoteFontSizeSp("Short quote."))
        assertEquals(28, pullQuoteFontSizeSp("A".repeat(60)))
        assertEquals(22, pullQuoteFontSizeSp("A".repeat(100)))
        assertEquals(18, pullQuoteFontSizeSp("A".repeat(180)))
        assertEquals(15, pullQuoteFontSizeSp("A".repeat(300)))
    }
}
