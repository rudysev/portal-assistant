package com.portal.assistant.conversation.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for media session selection. */
class MediaSelectionTest {

    private val own = "com.portal.assistant"

    /** A session that supports every transport action, not playing, with metadata; tests copy() what matters. */
    private fun s(index: Int, pkg: String) = SessionInfo(
        index,
        pkg,
        isPlaying = false,
        canPlay = true,
        canPause = true,
        canNext = true,
        canPrev = true,
        hasMetadata = true,
    )

    @Test fun pauseTargetsThePlayingSession() {
        val sessions = listOf(
            s(0, "com.alexa").copy(isPlaying = false, canNext = false, canPrev = false),
            s(1, "com.spotify").copy(isPlaying = true),
        )
        assertEquals(1, MediaSelection.pickForControl(sessions, MediaAction.PAUSE, own))
    }

    @Test fun playResumesPausedSessionWhenNothingPlaying() {
        // Nothing is playing; a paused Spotify that supports play must still be chosen (resume).
        val sessions = listOf(s(0, "com.spotify").copy(isPlaying = false, canPlay = true))
        assertEquals(0, MediaSelection.pickForControl(sessions, MediaAction.PLAY, own))
    }

    @Test fun nextRequiresSkipSupport() {
        val sessions = listOf(s(0, "com.alexa").copy(isPlaying = false, canNext = false))
        assertNull(MediaSelection.pickForControl(sessions, MediaAction.NEXT, own))
        val withSkip = listOf(s(0, "com.spotify").copy(canNext = true))
        assertEquals(0, MediaSelection.pickForControl(withSkip, MediaAction.NEXT, own))
    }

    @Test fun alexaHighPriorityDoesNotHijackMusicControl() {
        // Alexa is first (high priority) but not playing and can't skip; a playing music app must win.
        val sessions = listOf(
            s(0, "com.amazon.alexa.multimodal.falcon").copy(isPlaying = false, canNext = false, canPrev = false),
            s(1, "com.spotify").copy(isPlaying = true),
        )
        assertEquals(1, MediaSelection.pickForControl(sessions, MediaAction.PAUSE, own))
        assertEquals(1, MediaSelection.pickForControl(sessions, MediaAction.NEXT, own))
    }

    @Test fun playPrefersMetadataSessionOverHigherPriorityAlexa() {
        // Nothing playing; Alexa is first and supports play but has no metadata; paused Spotify has metadata.
        // PLAY must resume Spotify, not poke Alexa.
        val sessions = listOf(
            s(0, "com.amazon.alexa.multimodal.falcon").copy(isPlaying = false, canPlay = true, hasMetadata = false),
            s(1, "com.spotify").copy(isPlaying = false, canPlay = true, hasMetadata = true),
        )
        assertEquals(1, MediaSelection.pickForControl(sessions, MediaAction.PLAY, own))
    }

    @Test fun ownPackageNeverTargeted() {
        val sessions = listOf(s(0, own).copy(isPlaying = true))
        assertNull(MediaSelection.pickForControl(sessions, MediaAction.PAUSE, own))
    }

    @Test fun noneQualifyingReturnsNull() {
        assertNull(MediaSelection.pickForControl(emptyList(), MediaAction.PLAY, own))
    }

    @Test fun statusPrefersPlayingThenMetadata() {
        val playing = listOf(
            s(0, "com.spotify").copy(isPlaying = false, hasMetadata = true),
            s(1, "com.player").copy(isPlaying = true, hasMetadata = true),
        )
        assertEquals(1, MediaSelection.pickForStatus(playing, own))

        val pausedOnly = listOf(
            s(0, "com.noMeta").copy(isPlaying = false, hasMetadata = false),
            s(1, "com.spotify").copy(isPlaying = false, hasMetadata = true),
        )
        assertEquals(1, MediaSelection.pickForStatus(pausedOnly, own))

        assertNull(MediaSelection.pickForStatus(emptyList(), own))
    }

    @Test fun repeatTargetsPlayingSessionThatSupportsIt() {
        val sessions = listOf(
            s(0, "com.alexa").copy(isPlaying = false, canSetRepeat = false),
            s(1, "com.applemusic").copy(isPlaying = true, canSetRepeat = true),
        )
        assertEquals(1, MediaSelection.pickForRepeat(sessions, own))
    }

    @Test fun repeatNullWhenNoSessionSupportsIt() {
        val sessions = listOf(s(0, "com.applemusic").copy(isPlaying = true, canSetRepeat = false))
        assertNull(MediaSelection.pickForRepeat(sessions, own))
    }

    @Test fun repeatNeverTargetsOwnPackage() {
        assertNull(MediaSelection.pickForRepeat(listOf(s(0, own).copy(isPlaying = true, canSetRepeat = true)), own))
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
