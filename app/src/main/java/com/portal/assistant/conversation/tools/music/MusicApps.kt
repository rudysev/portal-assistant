package com.portal.assistant.conversation.tools.music

import com.portal.assistant.conversation.tools.AppEntry

/**
 * The device's music apps, as **Music** finds and starts them: raw platform calls only. Which app a play targets, how
 * it starts, and what the model is told live in Music. Production adapter: [AndroidMusicApps]. Called on the
 * tool-executor thread.
 */
interface MusicApps {
    /** The user's saved default music app, already checked installed and launchable; null when none is set. */
    fun defaultPkg(): String?

    /** The installed music apps discovery found. */
    fun musicApps(): List<AppEntry>

    /** The apps a music search can start on (play-from-search handlers + Spotify), discovered or not. */
    fun searchPlayableApps(): List<AppEntry>

    /** The user-visible name of the app [pkg], or [pkg] itself when it has none. */
    fun appLabel(pkg: String): String

    /** Whether [pkg] handles the generic play-from-search intent. */
    fun hasPlayFromSearch(pkg: String): Boolean

    /** Open Spotify's search deep link for [query] in [pkg]. */
    fun searchDeepLink(pkg: String, query: String): Start

    /** Fire the generic play-from-search intent for [query] at [pkg], its kind pinned to [type] when given. */
    fun playFromSearch(pkg: String, query: String, type: PlayType?): Start

    /** Just open [pkg]. */
    fun launch(pkg: String): Start
}

/** Whether a start fired. */
sealed interface Start {
    /** The app was started. */
    data object Started : Start

    /** The app wasn't started; [reason] is the platform's, null when there's none (e.g. no launch activity). */
    data class Failed(val reason: String?) : Start
}
