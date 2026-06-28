package com.portal.assistant.conversation.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for app-name resolution. */
class AppMatchTest {

    private val apps = listOf(
        AppEntry("Spotify", "com.facebook.aloha.spotifystandalone"),
        AppEntry("Calendar", "com.portal.calendar"),
        AppEntry("Camera", "com.portal.cameras"),
        AppEntry("Chrome", "org.chromium.chrome"),
        AppEntry("Photos", "com.facebook.alohaapps.superframe"),
    )

    private fun pick(query: String) = AppMatch.best(query, apps).pick?.label

    @Test fun exactCaseInsensitive() {
        assertEquals("Spotify", pick("Spotify"))
        assertEquals("Spotify", pick("spotify"))
    }

    @Test fun stripsLeadingVerbAndArticle() {
        assertEquals("Spotify", pick("open Spotify"))
        assertEquals("Calendar", pick("launch the calendar"))
        assertEquals("Camera", pick("start the camera"))
        assertEquals("Chrome", pick("go to chrome"))
    }

    @Test fun prefixAndContains() {
        assertEquals("Calendar", pick("calend")) // prefix
        assertEquals("Calendar", pick("family calendar")) // query contains the label
        assertEquals("Photos", pick("google photos")) // query contains the label
    }

    @Test fun aliasResolvesAsFallback() {
        assertEquals("Chrome", pick("open the browser"))
        assertEquals("Chrome", pick("web"))
    }

    @Test fun aliasNeverOverridesADirectLabelMatch() {
        // If a real app were literally named "Browser", the literal match must win over the chrome alias.
        val withBrowser = apps + AppEntry("Browser", "com.some.browser")
        assertEquals("Browser", AppMatch.best("browser", withBrowser).pick?.label)
    }

    @Test fun shortNonExactQueryDoesNotAutoPick() {
        // "ca" is below the min length and not an exact label → no confident pick (don't open Calendar/Camera).
        assertNull(pick("ca"))
    }

    @Test fun noMatchReturnsClosestCandidatesNoPick() {
        // A misheard name shares a stem with Spotify → surfaced as a candidate, not auto-opened.
        val r = AppMatch.best("spotty", apps)
        assertNull(r.pick)
        assertTrue(r.candidates.contains("Spotify"))
    }

    @Test fun deterministicTieBreakForDuplicateLabels() {
        // Two "Settings" with different packages → exact ties resolve to the alphabetically-first package, every run.
        val dupes = listOf(
            AppEntry("Settings", "com.facebook.alohaapps.settings"),
            AppEntry("Settings", "com.android.settings"),
        )
        val first = AppMatch.best("settings", dupes).pick
        assertEquals("com.android.settings", first?.pkg)
        assertEquals(first, AppMatch.best("settings", dupes).pick) // stable across calls
    }

    @Test fun emptyOrFillerOnlyQueryIsSafeNoPick() {
        assertNull(pick(""))
        assertNull(pick("open the")) // nothing left after stripping filler
    }

    @Test fun emptyAppListIsSafe() {
        val r = AppMatch.best("Spotify", emptyList())
        assertNull(r.pick)
        assertTrue(r.candidates.isEmpty())
    }

    @Test fun shortMidSubstringDoesNotAutoPick() {
        // "ify" is a 3-char fragment of "Spotify"; the contains tier must not auto-open it.
        assertNull(pick("ify"))
        // A longer mid-substring (not a prefix) is still allowed to resolve via the contains tier.
        assertEquals("Spotify", pick("potify"))
    }

    @Test fun tokenTierStaysInCandidatesNeverAutoPicks() {
        // Partial token overlap (one shared word of several) scores below the pick threshold → candidates only.
        val apps = listOf(
            AppEntry("Family Room TV", "com.tv.familyroom"),
            AppEntry("Living Room Speaker", "com.spk.living"),
        )
        val r = AppMatch.best("game room", apps) // shares only "room" with each
        assertNull(r.pick)
        assertTrue(r.candidates.isNotEmpty())
    }

    @Test fun containsTieIsDeterministicAndDeduped() {
        // Two apps whose labels both contain the query at the same tier — pick is stable, candidates deduped.
        val dupes = listOf(
            AppEntry("Settings", "com.facebook.alohaapps.settings"),
            AppEntry("Settings", "com.android.settings"),
        )
        val r = AppMatch.best("system settings", dupes) // "systemsettings" contains "settings" → tier contains
        assertEquals("com.android.settings", r.pick?.pkg)
        assertEquals(r.pick, AppMatch.best("system settings", dupes).pick)
    }

    @Test fun multiWordAliasFallsThroughToTiers() {
        // Documents the known v1 gap: aliases are single-token, so "web browser" doesn't hit the alias map;
        // it resolves (or not) on its own merits via the tiers instead.
        val withChrome = apps // "Chrome" present
        val r = AppMatch.best("web browser", withChrome)
        assertNull(r.pick) // neither "web" nor "browser" is a substring/token of "Chrome"
    }
}
