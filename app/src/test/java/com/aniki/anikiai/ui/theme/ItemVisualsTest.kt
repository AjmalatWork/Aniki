package com.aniki.anikiai.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemVisualsTest {

    private val onPaletteBackgrounds = setOf(SealTint1, SealTint2, SealTint3, SealTint4)

    @Test
    fun `same domain always maps to the same color pair`() {
        val first = monogramColorsFor("theverge.com")
        val second = monogramColorsFor("theverge.com")

        assertEquals(first, second)
    }

    @Test
    fun `every possible domain hashes into the curated on-palette background set`() {
        val domains = listOf("theverge.com", "nytimes.com", "arstechnica.com", "wikipedia.org", "a", "z", "example.co")
        for (domain in domains) {
            val (background, _) = monogramColorsFor(domain)
            assertTrue("$domain should map to an on-palette tone", background in onPaletteBackgrounds)
        }
    }

    @Test
    fun `different domains can map to different tones`() {
        val results = listOf("theverge.com", "nytimes.com", "arstechnica.com", "wikipedia.org", "reuters.com", "bbc.com")
            .map { monogramColorsFor(it).first }
            .toSet()

        assertTrue("expected more than one distinct tone across varied domains", results.size > 1)
    }

    @Test
    fun `monogram letter is the first alphanumeric character, uppercased`() {
        assertEquals("T", monogramLetterFor("theverge.com"))
        assertEquals("N", monogramLetterFor("nytimes.com"))
    }

    @Test
    fun `monogram letter uses the site name, not the subdomain the host actually carries`() {
        // Uri.host always includes the subdomain, so these are the real production inputs.
        assertEquals("T", monogramLetterFor("www.theverge.com"))
        assertEquals("W", monogramLetterFor("en.wikipedia.org"))
        assertEquals("N", monogramLetterFor("m.nytimes.com"))
        assertEquals("B", monogramLetterFor("bbc.co.uk")) // two-part public suffix
    }

    @Test
    fun `every subdomain of a site shares the site's color`() {
        val bare = monogramColorsFor("theverge.com")
        assertEquals(bare, monogramColorsFor("www.theverge.com"))
        assertEquals(bare, monogramColorsFor("m.theverge.com"))
    }

    @Test
    fun `registrable label strips subdomains and public suffix`() {
        assertEquals("theverge", registrableLabelFor("www.theverge.com"))
        assertEquals("wikipedia", registrableLabelFor("en.wikipedia.org"))
        assertEquals("bbc", registrableLabelFor("bbc.co.uk"))
        assertEquals("example", registrableLabelFor("example.com"))
    }

    @Test
    fun `monogram letter falls back to a question mark for a blank domain`() {
        assertEquals("?", monogramLetterFor(""))
        assertEquals("?", monogramLetterFor("   "))
    }
}
