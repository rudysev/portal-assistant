package com.portal.assistant.conversation.tools.music

/**
 * The device's media sessions, as **Music** reads and drives them: raw platform calls only. Which session a command
 * targets and what the model is told live in Music. Production adapter: [AndroidMediaSessions]. Called on the
 * tool-executor thread.
 */
interface MediaSessions {
    /**
     * The active sessions in the framework's priority order (most-recently-active first), each [SessionInfo.index] its
     * position in the list, or null when session access (notification access) isn't granted.
     */
    fun active(): List<ActiveSession>?

    /** The user-visible name of the app [pkg], or [pkg] itself when it has none. */
    fun appLabel(pkg: String): String
}

/** One active session: what selection reads ([info]) and the track it reports, blank values already dropped. */
data class ActiveSession(val info: SessionInfo, val title: String? = null, val artist: String? = null)
