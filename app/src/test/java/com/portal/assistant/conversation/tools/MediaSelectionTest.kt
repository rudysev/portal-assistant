package com.portal.assistant.conversation.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for media session selection. */
class MediaSelectionTest {

    private val own = "com.portal.assistant"

    private fun s(
        index: Int,
        pkg: String,
        playing: Boolean = false,
        canPlay: Boolean = true,
        canPause: Boolean = true,
        canNext: Boolean = true,
        canPrev: Boolean = true,
        meta: Boolean = true,
        canRepeat: Boolean = false,
    ) = SessionInfo(index, pkg, playing, canPlay, canPause, canNext, canPrev, meta, canRepeat)

    @Test fun pauseTargetsThePlayingSession() {
        val sessions = listOf(
            s(0, "com.alexa", playing = false, canNext = false, canPrev = false),
            s(1, "com.spotify", playing = true),
        )
        assertEquals(1, MediaSelection.pickForControl(sessions, MediaAction.PAUSE, own))
    }

    @Test fun playResumesPausedSessionWhenNothingPlaying() {
        // Nothing is playing; a paused Spotify that supports play must still be chosen (resume).
        val sessions = listOf(s(0, "com.spotify", playing = false, canPlay = true))
        assertEquals(0, MediaSelection.pickForControl(sessions, MediaAction.PLAY, own))
    }

    @Test fun nextRequiresSkipSupport() {
        val sessions = listOf(s(0, "com.alexa", playing = false, canNext = false))
        assertNull(MediaSelection.pickForControl(sessions, MediaAction.NEXT, own))
        val withSkip = listOf(s(0, "com.spotify", canNext = true))
        assertEquals(0, MediaSelection.pickForControl(withSkip, MediaAction.NEXT, own))
    }

    @Test fun alexaHighPriorityDoesNotHijackMusicControl() {
        // Alexa is first (high priority) but not playing and can't skip; a playing music app must win.
        val sessions = listOf(
            s(0, "com.amazon.alexa.multimodal.falcon", playing = false, canNext = false, canPrev = false),
            s(1, "com.spotify", playing = true),
        )
        assertEquals(1, MediaSelection.pickForControl(sessions, MediaAction.PAUSE, own))
        assertEquals(1, MediaSelection.pickForControl(sessions, MediaAction.NEXT, own))
    }

    @Test fun playPrefersMetadataSessionOverHigherPriorityAlexa() {
        // Nothing playing; Alexa is first and supports play but has no metadata; paused Spotify has metadata.
        // PLAY must resume Spotify, not poke Alexa.
        val sessions = listOf(
            s(0, "com.amazon.alexa.multimodal.falcon", playing = false, canPlay = true, meta = false),
            s(1, "com.spotify", playing = false, canPlay = true, meta = true),
        )
        assertEquals(1, MediaSelection.pickForControl(sessions, MediaAction.PLAY, own))
    }

    @Test fun ownPackageNeverTargeted() {
        val sessions = listOf(s(0, own, playing = true))
        assertNull(MediaSelection.pickForControl(sessions, MediaAction.PAUSE, own))
    }

    @Test fun noneQualifyingReturnsNull() {
        assertNull(MediaSelection.pickForControl(emptyList(), MediaAction.PLAY, own))
    }

    @Test fun statusPrefersPlayingThenMetadata() {
        val playing = listOf(
            s(0, "com.spotify", playing = false, meta = true),
            s(1, "com.player", playing = true, meta = true),
        )
        assertEquals(1, MediaSelection.pickForStatus(playing, own))

        val pausedOnly = listOf(
            s(0, "com.noMeta", playing = false, meta = false),
            s(1, "com.spotify", playing = false, meta = true),
        )
        assertEquals(1, MediaSelection.pickForStatus(pausedOnly, own))

        assertNull(MediaSelection.pickForStatus(emptyList(), own))
    }

    @Test fun repeatTargetsPlayingSessionThatSupportsIt() {
        val sessions = listOf(
            s(0, "com.alexa", playing = false, canRepeat = false),
            s(1, "com.applemusic", playing = true, canRepeat = true),
        )
        assertEquals(1, MediaSelection.pickForRepeat(sessions, own))
    }

    @Test fun repeatNullWhenNoSessionSupportsIt() {
        val sessions = listOf(s(0, "com.applemusic", playing = true, canRepeat = false))
        assertNull(MediaSelection.pickForRepeat(sessions, own))
    }

    @Test fun repeatNeverTargetsOwnPackage() {
        assertNull(MediaSelection.pickForRepeat(listOf(s(0, own, playing = true, canRepeat = true)), own))
    }

    @Test fun repeatModeParsesCanonicalValuesCaseAndWhitespaceInsensitive() {
        assertEquals(RepeatMode.ONE, MediaSelection.repeatMode("one"))
        assertEquals(RepeatMode.ONE, MediaSelection.repeatMode("  One "))
        assertEquals(RepeatMode.ALL, MediaSelection.repeatMode("ALL"))
        assertEquals(RepeatMode.OFF, MediaSelection.repeatMode("off"))
        assertEquals(RepeatMode.OFF, MediaSelection.repeatMode("none"))
    }

    @Test fun repeatModeNullForAbsentOrUnknown() {
        assertNull(MediaSelection.repeatMode(null))
        assertNull(MediaSelection.repeatMode(""))
        assertNull(MediaSelection.repeatMode("   "))
        assertNull(MediaSelection.repeatMode("shuffle"))
        assertNull(MediaSelection.repeatMode("track")) // tightened: no longer a synonym for ONE
        assertNull(MediaSelection.repeatMode("stop")) // must NOT collide with media_control's stop → pause
    }
}
