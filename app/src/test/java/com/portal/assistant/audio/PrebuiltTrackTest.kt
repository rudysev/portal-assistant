package com.portal.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the reuse policy behind the Listening tone's player: the track is built once ahead of time
 * and replayed for every play; an unusable track is released and replaced by a fresh one, which is kept; and every
 * play ends in exactly one outcome (started or failed), so the engine's pending tone always ends.
 */
class PrebuiltTrackTest {

    /** A fake track: [replay] throws while [usable] is false. Records what was done to it. */
    private class FakeTrack(var usable: Boolean = true) : PrebuiltTrack.Track {
        var replays = 0
        var released = false

        override fun replay() {
            check(usable) { "unusable" }
            replays++
        }

        override fun release() {
            released = true
        }
    }

    /** Builds [FakeTrack]s, in order, from [usableInBuildOrder]; records every track built. */
    private class FakeBuilder(vararg val usableInBuildOrder: Boolean) {
        val built = mutableListOf<FakeTrack>()
        fun build(): PrebuiltTrack.Track = FakeTrack(usableInBuildOrder.getOrElse(built.size) { true }).also { built += it }
    }

    @Test fun aPreparedTrackIsReplayedForEveryPlayWithoutRebuilding() {
        val builder = FakeBuilder()
        val toneTrack = PrebuiltTrack("test tone", builder::build)

        toneTrack.prepare()
        assertTrue(toneTrack.play())
        assertTrue(toneTrack.play())

        assertEquals(1, builder.built.size)
        assertEquals(2, builder.built.single().replays)
    }

    @Test fun anUnusablePrebuiltTrackIsReleasedAndAFreshOnePlaysAndIsKept() {
        // e.g. the audio server restarted since prewarm: this play still sounds, and the next reuses the fresh track.
        val builder = FakeBuilder(false, true)
        val toneTrack = PrebuiltTrack("test tone", builder::build)
        toneTrack.prepare()

        assertTrue(toneTrack.play())
        assertTrue(toneTrack.play())

        val (stale, fresh) = builder.built
        assertTrue(stale.released)
        assertEquals(2, builder.built.size)
        assertEquals(2, fresh.replays)
        assertFalse(fresh.released)
    }

    @Test fun whenNoTrackCanPlayThePlayFailsAndNothingIsKept() {
        val builder = FakeBuilder(false, false, true)
        val toneTrack = PrebuiltTrack("test tone", builder::build)
        toneTrack.prepare()

        assertFalse(toneTrack.play())
        assertTrue(builder.built.all { it.released })

        // The next play starts over with a fresh build rather than retrying a broken track.
        assertTrue(toneTrack.play())
        assertEquals(3, builder.built.size)
        assertEquals(1, builder.built.last().replays)
    }

    @Test fun aFailedBuildFailsThatPlayAndTheNextPlayBuildsAgain() {
        var buildAttempts = 0
        val working = FakeTrack()
        val toneTrack = PrebuiltTrack("test tone") {
            buildAttempts++
            if (buildAttempts <= 2) error("no track") else working
        }

        toneTrack.prepare() // a failed build ahead of time is not an error: the first play builds instead
        assertFalse(toneTrack.play())
        assertTrue(toneTrack.play())

        assertEquals(3, buildAttempts)
        assertEquals(1, working.replays)
    }
}
