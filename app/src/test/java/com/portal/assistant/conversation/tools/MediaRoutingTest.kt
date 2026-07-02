package com.portal.assistant.conversation.tools

import com.portal.assistant.conversation.tools.MediaRouting.PlayStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for play routing (target selection + strategy). */
class MediaRoutingTest {

    private val spotifyPkgs = listOf("com.facebook.aloha.spotifystandalone", "com.spotify.music")

    private val spotify = AppEntry("Spotify", "com.facebook.aloha.spotifystandalone")
    private val tidal = AppEntry("TIDAL", "com.aspiro.tidal")
    private val appleMusic = AppEntry("Apple Music", "com.apple.android.music")
    private val portalPlayer = AppEntry("Portal Player", "com.facebook.alohaservices.player2")

    private fun resolve(
        spokenApp: String? = null,
        defaultPkg: String? = null,
        musicApps: List<AppEntry> = listOf(spotify, tidal),
        allApps: List<AppEntry> = listOf(spotify, tidal),
    ) = MediaRouting.resolveTarget(spokenApp, defaultPkg, musicApps, { allApps }, spotifyPkgs)

    // --- spoken app (honour the name) ---

    @Test fun spokenAppMatchesMusicApp() {
        assertEquals(tidal.pkg, resolve(spokenApp = "TIDAL").pkg)
    }

    @Test fun spokenAppOverridesDefault() {
        // the named app wins over the saved default
        assertEquals(tidal.pkg, resolve(spokenApp = "TIDAL", defaultPkg = spotify.pkg).pkg)
    }

    @Test fun spokenAppFallsBackToMusicCapableApp() {
        // Detection missed TIDAL (not in the music list); the shell scopes the fallback to music-capable apps
        // (search-play handlers + Spotify), so "on TIDAL" still resolves via that fallback list.
        val t = resolve(spokenApp = "TIDAL", musicApps = listOf(spotify), allApps = listOf(spotify, tidal))
        assertEquals(tidal.pkg, t.pkg)
    }

    @Test fun spokenAppNotInstalledReturnsNamedReasonNoSubstitution() {
        val t = resolve(spokenApp = "Pandora", musicApps = listOf(spotify), allApps = listOf(spotify))
        assertNull(t.pkg) // never silently switch to Spotify
        assertNotNull(t.reason)
        assertEquals(true, t.reason!!.contains("Pandora"))
    }

    // --- default resolution (no app named) ---

    @Test fun defaultPkgIsTrustedDirectly() {
        val t = resolve(defaultPkg = tidal.pkg)
        assertEquals(tidal.pkg, t.pkg)
    }

    @Test fun defaultPkgTrustedEvenWhenNotInMusicSnapshot() {
        // The caller (AppPrefs.defaultMusicPkg) validates install, so routing trusts the pkg without needing
        // it in the discovered music list — no dependency on the snapshot.
        val t = resolve(defaultPkg = "com.some.installed", musicApps = listOf(spotify), allApps = listOf(spotify))
        assertEquals("com.some.installed", t.pkg)
    }

    @Test fun soleMusicAppUsedWhenNoDefault() {
        assertEquals(tidal.pkg, resolve(musicApps = listOf(tidal), allApps = listOf(tidal)).pkg)
    }

    @Test fun spotifyIsTheFallbackDefaultWhenPresent() {
        // Multiple apps, no default set → Spotify preserves today's behavior.
        assertEquals(spotify.pkg, resolve(musicApps = listOf(spotify, tidal, appleMusic)).pkg)
    }

    @Test fun spotifyFallbackFoundViaAllAppsWhenUndiscovered() {
        // Spotify installed (launchable) but not surfaced by music-app discovery → still the fallback default.
        val t = resolve(musicApps = listOf(tidal, appleMusic), allApps = listOf(tidal, appleMusic, spotify))
        assertEquals(spotify.pkg, t.pkg)
    }

    @Test fun soleAppOnlyInFallbackBecomesDefault() {
        // Discovery missed the only music app but it's search-playable → phase-2 of effectiveDefault picks it.
        val t = resolve(musicApps = emptyList(), allApps = listOf(tidal))
        assertEquals(tidal.pkg, t.pkg)
    }

    @Test fun soleDiscoveredAppWinsOverSpotifyInFallback() {
        // Your one discovered music app (TIDAL) must not be outranked by a Spotify that's only in the fallback.
        val t = resolve(musicApps = listOf(tidal), allApps = listOf(tidal, spotify))
        assertEquals(tidal.pkg, t.pkg)
    }

    @Test fun soleDiscoveredAppPlaysNotAsksWhenFallbackAddsAnother() {
        // One discovered app (TIDAL) + a fallback-only app (Apple Music), no Spotify → auto-play TIDAL, not ask.
        val t = resolve(musicApps = listOf(tidal), allApps = listOf(tidal, appleMusic))
        assertEquals(tidal.pkg, t.pkg)
    }

    @Test fun routingDefaultMatchesSettingsEffectiveDefault() {
        // Parity contract: no-default routing == Settings' effectiveDefault over the same discovered set + union.
        // Settings sorts the union by label; routing leaves it unsorted — order-independent autoDefault keeps
        // them equal, and the sole discovered app (Apple Music) wins over the union's Spotify.
        val discovered = listOf(appleMusic)
        val fallback = listOf(appleMusic, tidal, spotify)
        val sortedUnion = (discovered + fallback).distinctBy { it.pkg }.sortedBy { it.label }
        val routed = MediaRouting.resolveTarget(null, null, discovered, { fallback }, spotifyPkgs).pkg
        assertEquals(MediaRouting.effectiveDefault(discovered, { sortedUnion }, spotifyPkgs)?.pkg, routed)
        assertEquals(appleMusic.pkg, routed)
    }

    @Test fun autoDefaultSpotifyTiebreakIsOrderIndependent() {
        // Both Spotify packages installed: sorted (Settings) vs unsorted (routing) input must pick the SAME one
        // (SPOTIFY_PKGS priority, standalone first) — else display and play drift.
        val standalone = AppEntry("Spotify", "com.facebook.aloha.spotifystandalone")
        val standard = AppEntry("Spotify Music", "com.spotify.music")
        val unsorted = MediaRouting.autoDefault(listOf(standard, tidal, standalone), spotifyPkgs)
        val sorted = MediaRouting.autoDefault(listOf(standard, tidal, standalone).sortedBy { it.label }, spotifyPkgs)
        assertEquals(standalone.pkg, unsorted?.pkg)
        assertEquals(unsorted?.pkg, sorted?.pkg)
    }

    @Test fun noDefaultAndNoSpotifyAsksUserToPick() {
        val t = resolve(musicApps = listOf(tidal, appleMusic), allApps = listOf(tidal, appleMusic))
        assertNull(t.pkg)
        assertNotNull(t.reason)
    }

    // --- strategy ---

    @Test fun strategySpotifyIsDeepLink() {
        assertEquals(PlayStrategy.DEEP_LINK, MediaRouting.strategyFor(spotify.pkg, spotifyPkgs) { true })
    }

    @Test fun strategySpotifySkipsThePlayFromSearchProbe() {
        // The capability thunk must not even be invoked for Spotify (avoids a wasted resolveActivity IPC).
        var probed = false
        val s = MediaRouting.strategyFor(spotify.pkg, spotifyPkgs) {
            probed = true
            true
        }
        assertEquals(PlayStrategy.DEEP_LINK, s)
        assertEquals(false, probed)
    }

    @Test fun strategyPlayFromSearchWhenSupported() {
        assertEquals(PlayStrategy.PLAY_FROM_SEARCH, MediaRouting.strategyFor(tidal.pkg, spotifyPkgs) { true })
    }

    @Test fun strategyLaunchOnlyWhenNoSearchSupport() {
        assertEquals(PlayStrategy.LAUNCH_ONLY, MediaRouting.strategyFor(portalPlayer.pkg, spotifyPkgs) { false })
    }

    // --- effective default rendered in Settings ---

    @Test fun autoDefaultIsTheSoleMusicApp() {
        assertEquals(tidal, MediaRouting.autoDefault(listOf(tidal), spotifyPkgs))
    }

    @Test fun autoDefaultPrefersSpotifyAmongSeveral() {
        assertEquals(spotify, MediaRouting.autoDefault(listOf(spotify, tidal, appleMusic), spotifyPkgs))
    }

    @Test fun autoDefaultNullWhenSeveralAndNoSpotify() {
        assertNull(MediaRouting.autoDefault(listOf(tidal, appleMusic), spotifyPkgs))
    }

    // --- play-type parsing (from the model's free-form hint) ---

    @Test fun playTypeParsesSingularAndPlural() {
        assertEquals(MediaRouting.PlayType.SONG, MediaRouting.playType("song"))
        assertEquals(MediaRouting.PlayType.SONG, MediaRouting.playType("songs"))
        assertEquals(MediaRouting.PlayType.ARTIST, MediaRouting.playType("artists"))
        assertEquals(MediaRouting.PlayType.ALBUM, MediaRouting.playType("album"))
        assertEquals(MediaRouting.PlayType.PLAYLIST, MediaRouting.playType("playlists"))
    }

    @Test fun playTypeIsCaseAndWhitespaceInsensitiveWithSynonyms() {
        assertEquals(MediaRouting.PlayType.ALBUM, MediaRouting.playType("  Album "))
        assertEquals(MediaRouting.PlayType.SONG, MediaRouting.playType("TRACK"))
    }

    @Test fun playTypeNullForAbsentOrUnknown() {
        assertNull(MediaRouting.playType(null))
        assertNull(MediaRouting.playType(""))
        assertNull(MediaRouting.playType("   "))
        assertNull(MediaRouting.playType("genre"))
        assertNull(MediaRouting.playType("radio"))
    }
}
