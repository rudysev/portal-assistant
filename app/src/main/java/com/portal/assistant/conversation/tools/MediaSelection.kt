package com.portal.assistant.conversation.tools

/** A media transport action the model can request. */
enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

/**
 * A lightweight, framework-free view of one active media session — enough to choose which session a media
 * command should target, without depending on android.media.session. The shell ([MediaControl]) builds these
 * from the real `MediaController`s (mapping `PlaybackState.actions` bits to the `can*` flags).
 */
data class SessionInfo(
    val index: Int,
    val pkg: String,
    val isPlaying: Boolean,
    val canPlay: Boolean,
    val canPause: Boolean,
    val canNext: Boolean,
    val canPrev: Boolean,
    val hasMetadata: Boolean,
) {
    fun supports(action: MediaAction): Boolean = when (action) {
        MediaAction.PLAY -> canPlay
        MediaAction.PAUSE -> canPause
        MediaAction.NEXT -> canNext
        MediaAction.PREVIOUS -> canPrev
    }
}

/**
 * Pure session-selection logic — Android-free so it's unit-tested (same split as [VolumeMath] /
 * [com.portal.assistant.system.Geolocation]). Picks which of several active sessions (Spotify, the Portal
 * player, Alexa, Bluetooth…) a command targets. The shell passes sessions in the framework's priority order
 * (most-recently-active first); these helpers return the chosen `index` or null.
 */
object MediaSelection {

    /**
     * Choose the session for [action]: prefer a currently-PLAYING session that supports it (you pause/skip
     * what's playing); else a session that has **metadata** (a real music app — paused Spotify); else the
     * highest-priority session that supports it. The metadata tiebreak matters most for PLAY/PAUSE, which
     * Alexa also advertises: a paused Spotify (has metadata) wins resume over a higher-priority Alexa session
     * (no metadata) instead of "play" poking Alexa. Our own [ownPkg] is never targeted.
     */
    fun pickForControl(sessions: List<SessionInfo>, action: MediaAction, ownPkg: String): Int? {
        val usable = sessions.filter { it.pkg != ownPkg && it.supports(action) }
        val chosen = usable.firstOrNull { it.isPlaying }
            ?: usable.firstOrNull { it.hasMetadata }
            ?: usable.firstOrNull()
        return chosen?.index
    }

    /** Choose the session to report "what's playing": the playing one, else the highest-priority with metadata. */
    fun pickForStatus(sessions: List<SessionInfo>, ownPkg: String): Int? {
        val others = sessions.filter { it.pkg != ownPkg }
        return (others.firstOrNull { it.isPlaying } ?: others.firstOrNull { it.hasMetadata })?.index
    }

    /** A speakable app name for a package id (raw package is ugly in speech). Falls through to the package. */
    fun friendlyApp(pkg: String): String = when {
        pkg.contains("spotify") -> "Spotify"
        pkg.contains("alohaservices.player") -> "the Portal player"
        else -> pkg
    }
}
