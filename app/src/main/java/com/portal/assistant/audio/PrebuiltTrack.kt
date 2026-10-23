package com.portal.assistant.audio

import com.portal.commons.DebugLog

/**
 * A short sound's playback track, built once ahead of time ([prepare], off the hot path) and replayed from its start
 * for every [play] — so a play costs a rewind, not a track build.
 *
 * **Self-healing.** If the kept track can't replay (never initialized, audio server restarted, `play()` threw), it
 * is released and a fresh one is built for that play; the fresh one is kept for next time. A track that fails even
 * when fresh is released too, so a broken track is never kept and the next play starts over with a build.
 *
 * Never throws: each [play] has exactly one outcome, started or not (failures are logged under [logLabel]). Not
 * thread-safe — call it from one thread. No Android (the platform track sits behind [Track]), so it's unit-tested
 * on the JVM.
 */
class PrebuiltTrack(private val logLabel: String, private val buildTrack: () -> Track) {

    /** One built playback track holding the sound. */
    interface Track {
        /** Play the sound from its start (cutting off any play still sounding). Throws if the track is unusable. */
        fun replay()

        /** Free the track; it is never used again. */
        fun release()
    }

    /** The track every play reuses; null until built, and after an unusable one is released. */
    private var keptTrack: Track? = null

    /** Build the track ahead of the first play. A failed build is only logged: the first play builds instead. */
    fun prepare() {
        if (keptTrack == null) keptTrack = buildOrNull()
    }

    /** Play the sound from its start. Returns whether it started; a failure is already logged. */
    fun play(): Boolean {
        val prebuilt = keptTrack
        if (prebuilt != null && replayOrDiscard(prebuilt, "prebuilt track unusable, building a fresh one")) return true
        val fresh = buildOrNull()
        val played = fresh != null && replayOrDiscard(fresh, "failed to play")
        if (played) keptTrack = fresh
        return played
    }

    /**
     * Replay [candidate]; if it can't, log [failureContext], release it, and stop keeping it. Returns whether it
     * played.
     */
    private fun replayOrDiscard(candidate: Track, failureContext: String): Boolean {
        val replayed = runCatching { candidate.replay() }
        if (replayed.isSuccess) return true
        DebugLog.log("$logLabel: $failureContext (${replayed.exceptionOrNull()?.message})")
        runCatching { candidate.release() }
        if (keptTrack === candidate) keptTrack = null
        return false
    }

    private fun buildOrNull(): Track? = runCatching { buildTrack() }.getOrElse {
        DebugLog.log("$logLabel: failed to build track: ${it.message}")
        null
    }
}
