package com.portal.assistant.conversation.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.portal.assistant.system.AppPrefs
import com.portal.commons.DebugLog
import org.json.JSONObject

/**
 * Starts music in an app, by name: [MediaRouting] picks the app and the strategy (a Spotify search deep link,
 * `MEDIA_PLAY_FROM_SEARCH`, or just opening the app), and this fires it. No media session is needed, unlike the
 * transport controls in [MediaControl].
 */
class MusicLauncher(context: Context) {

    private val appContext = context.applicationContext
    private val pm = appContext.packageManager

    /**
     * Start playing a song/artist/album/playlist by name. [MediaRouting] picks the target app (named app,
     * else default, else fallback) and the strategy ([MediaRouting.strategyFor]); this fires it.
     */
    fun play(query: String, app: String?, type: String? = null): JSONObject {
        val q = query.trim()
        if (q.isEmpty()) return JSONObject().put("error", "no song or artist specified")

        // The fallback set (search-play handlers + Spotify) is a targeted query; pass it as a thunk so it's
        // only run on the paths that need it — a named app missing from the music list, or an undiscovered
        // Spotify default.
        val target = MediaRouting.resolveTarget(
            app,
            AppPrefs.defaultMusicPkg(appContext),
            PackageCatalog.musicApps(appContext),
            { PackageCatalog.searchPlayableApps(appContext) },
            PackageCatalog.SPOTIFY_PKGS,
        )
        return target.pkg?.let { pkg -> playOn(pkg, target.label ?: appLabel(pkg), q, type) }
            ?: JSONObject().put("error", target.reason ?: "couldn't find a music app to play on")
    }

    private fun playOn(pkg: String, label: String, q: String, type: String?): JSONObject = when (MediaRouting.strategyFor(pkg, PackageCatalog.SPOTIFY_PKGS) { hasPlayFromSearch(pkg) }) {
        MediaRouting.PlayStrategy.DEEP_LINK -> playSpotify(pkg, q, label)
        MediaRouting.PlayStrategy.PLAY_FROM_SEARCH -> playFromSearch(pkg, q, label, MediaRouting.playType(type))
        MediaRouting.PlayStrategy.LAUNCH_ONLY -> launchOnly(pkg, q, label)
    }

    // A *search* deep link, not an exact `spotify:track:<id>`: the Portal's Spotify is Free-tier (can't
    // on-demand-play one track — a precise URI just opens the page) and blocks playFromSearch/media keys, so a
    // broad search via the app's own VIEW intent is what actually starts playback (device-verified).
    private fun playSpotify(pkg: String, q: String, label: String): JSONObject = runCatching {
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, ("spotify:search:" + Uri.encode(q)).toUri()).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        DebugLog.log("play_music → spotify:search \"$q\"")
        JSONObject().put("playing", true).put("query", q).put("app", label)
    }.getOrElse {
        DebugLog.log("play_music spotify failed: ${it.message}")
        JSONObject().put("error", "couldn't start $label: ${it.message}")
    }

    private fun playFromSearch(pkg: String, q: String, label: String, type: MediaRouting.PlayType?): JSONObject = runCatching {
        appContext.startActivity(playFromSearchIntent(pkg, q, type))
        DebugLog.log("play_music → play_from_search $pkg \"$q\" type=${type?.name?.lowercase() ?: "any"}")
        JSONObject().put("playing", true).put("query", q).put("app", label)
    }.getOrElse {
        // The activity resolved but failed to start — fall back to just opening the app.
        DebugLog.log("play_music play_from_search failed for $pkg: ${it.message}")
        launchOnly(pkg, q, label)
    }

    private fun launchOnly(pkg: String, q: String, label: String): JSONObject {
        val launch = pm.getLaunchIntentForPackage(pkg)
            ?: return JSONObject().put("error", "couldn't open $label")
        return runCatching {
            appContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DebugLog.log("play_music → launch-only $pkg (no search support)")
            JSONObject()
                .put("opened", true)
                .put("searchUnsupported", true) // couldn't start the exact search — the model should say so
                .put("query", q)
                .put("app", label)
        }.getOrElse {
            DebugLog.log("play_music launch failed for $pkg: ${it.message}")
            JSONObject().put("error", "couldn't open $label: ${it.message}")
        }
    }

    /** True if [pkg] declares an activity for the generic `MEDIA_PLAY_FROM_SEARCH` intent. Activity
     *  resolution matches on action/package, not the query, so no query is needed to probe capability. */
    private fun hasPlayFromSearch(pkg: String): Boolean = pm.resolveActivity(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg), 0) != null

    private fun playFromSearchIntent(pkg: String, q: String, type: MediaRouting.PlayType?) = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        setPackage(pkg)
        putExtra(SearchManager.QUERY, q)
        // Pin the search kind (from the model's type hint) so apps that honor it play the right type;
        // unknown/none → generic audio focus. The consumer app reads QUERY + focus — we deliberately don't
        // fake structured artist/title extras out of a blob query.
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focusFor(type))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Map a parsed [MediaRouting.PlayType] to the Android MEDIA_PLAY_FROM_SEARCH focus mime. */
    private fun focusFor(type: MediaRouting.PlayType?): String = when (type) {
        MediaRouting.PlayType.ARTIST -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
        MediaRouting.PlayType.ALBUM -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
        MediaRouting.PlayType.SONG -> MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
        MediaRouting.PlayType.PLAYLIST -> PLAYLIST_FOCUS
        null -> MediaStore.Audio.Media.CONTENT_TYPE // generic "any audio" focus
    }

    private fun appLabel(pkg: String): String = PackageCatalog.labelFor(appContext, pkg)

    private companion object {
        /**
         * The playlist search focus — `MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE`'s value. That class is
         * deprecated from API 31, but the focus mime itself is still what MEDIA_PLAY_FROM_SEARCH apps read.
         */
        const val PLAYLIST_FOCUS = "vnd.android.cursor.item/playlist"
    }
}
