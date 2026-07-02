package com.portal.assistant.conversation.tools

/** A media transport action the model can request. */
enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

/** A repeat mode the model can request; parsed from a free-form hint by [MediaSelection.repeatMode]. */
enum class RepeatMode { ONE, ALL, OFF }

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
    val canSetRepeat: Boolean = false,
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
     * Choose the session for [action]: the sessions that support it (never our own [ownPkg]), then [choose]
     * the best. The metadata tiebreak matters most for PLAY/PAUSE, which Alexa also advertises: a paused
     * Spotify (has metadata) wins resume over a higher-priority Alexa (no metadata) instead of poking Alexa.
     */
    fun pickForControl(sessions: List<SessionInfo>, action: MediaAction, ownPkg: String): Int? = choose(sessions.filter { it.pkg != ownPkg && it.supports(action) })

    /** Choose the session to set repeat on: the active music session that advertises repeat support. */
    fun pickForRepeat(sessions: List<SessionInfo>, ownPkg: String): Int? = choose(sessions.filter { it.pkg != ownPkg && it.canSetRepeat })

    /** Choose the session to report "what's playing": the playing one, else the highest-priority with metadata. */
    fun pickForStatus(sessions: List<SessionInfo>, ownPkg: String): Int? {
        val others = sessions.filter { it.pkg != ownPkg }
        return (others.firstOrNull { it.isPlaying } ?: others.firstOrNull { it.hasMetadata })?.index
    }

    /** Prefer a currently-playing session (you control what's playing), else one with metadata (a real music
     *  app), else the highest-priority. Sessions arrive in framework priority order (most-recently-active first). */
    private fun choose(usable: List<SessionInfo>): Int? = (usable.firstOrNull { it.isPlaying } ?: usable.firstOrNull { it.hasMetadata } ?: usable.firstOrNull())?.index

    /** Normalize the repeat mode to a [RepeatMode], or null when absent/unrecognized. Kept to the tool's
     *  declared values (one/all/off) — deliberately NOT accepting "stop" (it means pause in media_control). */
    fun repeatMode(raw: String?): RepeatMode? = when (raw?.trim()?.lowercase()) {
        "one" -> RepeatMode.ONE
        "all" -> RepeatMode.ALL
        "off", "none" -> RepeatMode.OFF
        else -> null
    }
}
