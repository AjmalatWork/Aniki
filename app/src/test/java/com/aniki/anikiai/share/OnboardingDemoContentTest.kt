package com.aniki.anikiai.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingDemoContentTest {

    @Test
    fun `the demo share text is recognized as a demo share`() {
        assertTrue(OnboardingDemoContent.isDemoShare(OnboardingDemoContent.shareText))
    }

    @Test
    fun `ordinary shared text is not mistaken for the demo share`() {
        assertFalse(OnboardingDemoContent.isDemoShare("Check out this article: https://example.com/foo"))
        assertFalse(OnboardingDemoContent.isDemoShare("Just a plain note about groceries"))
    }

    @Test
    fun `the share text visibly shows the hardcoded title and body, with no marker artifact`() {
        assertTrue(OnboardingDemoContent.shareText.contains(OnboardingDemoContent.title))
        assertTrue(OnboardingDemoContent.shareText.contains(OnboardingDemoContent.body))
        // Regression guard for the original design: a plain visible ASCII marker showed up in
        // the OS share sheet's own text preview, which looked broken. The marker must never be
        // a run of ordinary visible characters.
        assertFalse(OnboardingDemoContent.shareText.contains("aniki-onboarding-demo"))
    }
}
