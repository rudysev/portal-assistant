package com.portal.assistant.conversation.tools.music

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.portal.assistant.conversation.tools.AppEntry
import com.portal.assistant.conversation.tools.PackageCatalog
import com.portal.assistant.system.AppPrefs

/**
 * [MusicApps] on the device's PackageManager ([PackageCatalog]), the saved default ([AppPrefs]) and `startActivity`.
 * Platform calls only: which app, which strategy and what the model is told live in [Music]. No media session is
 * needed to start music, unlike the transport controls.
 */
class AndroidMusicApps(context: Context) : MusicApps {

    private val appContext = context.applicationContext
    private val pm = appContext.packageManager

    override fun defaultPkg(): String? = AppPrefs.defaultMusicPkg(appContext)

    override fun musicApps(): List<AppEntry> = PackageCatalog.musicApps(appContext)

    override fun searchPlayableApps(): List<AppEntry> = PackageCatalog.searchPlayableApps(appContext)

    override fun appLabel(pkg: String): String = PackageCatalog.labelFor(appContext, pkg)

    /** Activity resolution matches on action/package, not the query, so no query is needed to probe capability. */
    override fun hasPlayFromSearch(pkg: String): Boolean =
        pm.resolveActivity(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg), 0) != null

    override fun searchDeepLink(pkg: String, query: String): Start = start {
        Intent(Intent.ACTION_VIEW, ("spotify:search:" + Uri.encode(query)).toUri()).setPackage(pkg)
    }

    override fun playFromSearch(pkg: String, query: String, type: PlayType?): Start = start {
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(pkg)
            putExtra(SearchManager.QUERY, query)
            // Pin the search kind (from the model's type hint) so apps that honor it play the right type; unknown or
            // none → generic audio focus. The app reads QUERY + focus: we deliberately don't fake structured
            // artist/title extras out of a blob query.
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focusFor(type))
        }
    }

    override fun launch(pkg: String): Start =
        pm.getLaunchIntentForPackage(pkg)?.let { launch -> start { launch } } ?: Start.Failed(null)

    /** Build and start the [intent] in one `runCatching`, so a failure building it is a failed start too. */
    private fun start(intent: () -> Intent): Start = runCatching {
        appContext.startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Start.Started
    }.getOrElse { Start.Failed(it.message.toString()) }

    /** The Android play-from-search focus mime for [type]. */
    private fun focusFor(type: PlayType?): String = when (type) {
        PlayType.ARTIST -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
        PlayType.ALBUM -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
        PlayType.SONG -> MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
        PlayType.PLAYLIST -> PLAYLIST_FOCUS
        null -> MediaStore.Audio.Media.CONTENT_TYPE // generic "any audio" focus
    }

    private companion object {
        /**
         * The playlist search focus: `MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE`'s value. That class is
         * deprecated from API 31, but the focus mime itself is still what MEDIA_PLAY_FROM_SEARCH apps read.
         */
        const val PLAYLIST_FOCUS = "vnd.android.cursor.item/playlist"
    }
}
