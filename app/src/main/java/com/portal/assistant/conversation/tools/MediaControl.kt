package com.portal.assistant.conversation.tools

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.MediaStore
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.portal.assistant.system.AppPrefs
import com.portal.commons.DebugLog
import org.json.JSONObject

/**
 * The one Android site that touches the media-session APIs. Reads the device's active sessions via
 * [MediaSessionManager] (gated on [PortalNotificationListener] being an enabled notification listener) and
 * drives transport on the right one. Pure selection lives in [MediaSelection]; this is the thin shell (same
 * split as [VolumeMath] / [VolumeController]).
 *
 * **Why sessions, not media keys:** on this Portal `dispatchMediaKeyEvent` is intercepted by Alexa; talking
 * to the music app's [MediaController] directly bypasses that.
 *
 * **Threading:** one-shot per tool call — `getActiveSessions` + cached state/metadata reads + a oneway
 * transport call, with no `MediaController.Callback` registered — so it's safe on the tool-executor thread
 * (no Looper needed; a Looper/HandlerThread is only required for live-update callbacks, which we don't do).
 */
class MediaControl(context: Context) {

    private val appContext = context.applicationContext
    private val pm = appContext.packageManager
    private val ownPkg = appContext.packageName
    private val component = ComponentName(appContext, PortalNotificationListener::class.java)
    private val manager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    /** Run [action] on the best matching session. */
    fun control(action: MediaAction): JSONObject = withSession(
        pick = { MediaSelection.pickForControl(it, action, ownPkg) },
        notFound = { empty -> JSONObject().put("error", if (empty) "nothing is playing" else "no media session supports that action") },
    ) { controller ->
        when (action) {
            MediaAction.PLAY -> controller.transportControls.play()
            MediaAction.PAUSE -> controller.transportControls.pause()
            MediaAction.NEXT -> controller.transportControls.skipToNext()
            MediaAction.PREVIOUS -> controller.transportControls.skipToPrevious()
        }
        val app = appLabel(controller.packageName)
        DebugLog.log("media ${action.name.lowercase()} → $app")
        JSONObject().put("action", action.name.lowercase()).put("app", app)
    }

    /**
     * Set the repeat mode on the active music session. Gated on the app advertising `ACTION_SET_REPEAT_MODE`
     * (via [SessionInfo.canSetRepeat]) — an app that doesn't publish it gets a graceful error rather than a
     * silent no-op. Pure parse + selection live in [MediaSelection]; this maps to the framework constant and
     * drives the transport (same session route as [control], so it bypasses Alexa's media-key interception).
     */
    fun setRepeat(mode: String?): JSONObject {
        val repeat = MediaSelection.repeatMode(mode)
            ?: return JSONObject().put("error", "repeat must be one, all, or off")
        return withSession(
            pick = { MediaSelection.pickForRepeat(it, ownPkg) },
            notFound = { empty -> JSONObject().put("error", if (empty) "nothing is playing" else "repeat isn't available on the current app") },
        ) { controller ->
            val app = appLabel(controller.packageName)
            runCatching {
                // setRepeatMode is a media-compat transport control; bridge the framework session token into a
                // MediaControllerCompat to reach the app's onSetRepeatMode (the Android Auto / Assistant path).
                MediaControllerCompat(appContext, MediaSessionCompat.Token.fromToken(controller.sessionToken))
                    .transportControls.setRepeatMode(androidRepeat(repeat))
                DebugLog.log("media set_repeat ${repeat.name.lowercase()} → $app")
                JSONObject().put("repeat", repeat.name.lowercase()).put("app", app)
            }.getOrElse {
                DebugLog.log("media set_repeat failed for ${controller.packageName}: ${it.message}")
                JSONObject().put("error", "couldn't set repeat on $app: ${it.message}")
            }
        }
    }

    private fun androidRepeat(mode: RepeatMode): Int = when (mode) {
        RepeatMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
        RepeatMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
        RepeatMode.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE
    }

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
        val pkg = target.pkg
            ?: return JSONObject().put("error", target.reason ?: "couldn't find a music app to play on")
        val label = target.label ?: appLabel(pkg)

        return when (MediaRouting.strategyFor(pkg, PackageCatalog.SPOTIFY_PKGS) { hasPlayFromSearch(pkg) }) {
            MediaRouting.PlayStrategy.DEEP_LINK -> playSpotify(pkg, q, label)
            MediaRouting.PlayStrategy.PLAY_FROM_SEARCH -> playFromSearch(pkg, q, label, MediaRouting.playType(type))
            MediaRouting.PlayStrategy.LAUNCH_ONLY -> launchOnly(pkg, q, label)
        }
    }

    // A *search* deep link, not an exact `spotify:track:<id>`: the Portal's Spotify is Free-tier (can't
    // on-demand-play one track — a precise URI just opens the page) and blocks playFromSearch/media keys, so a
    // broad search via the app's own VIEW intent is what actually starts playback (device-verified).
    private fun playSpotify(pkg: String, q: String, label: String): JSONObject = runCatching {
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))).apply {
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
        MediaRouting.PlayType.PLAYLIST -> MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE
        null -> MediaStore.Audio.Media.CONTENT_TYPE // generic "any audio" focus
    }

    private fun appLabel(pkg: String): String = PackageCatalog.labelFor(appContext, pkg)

    /** Report what's currently playing (or paused with known metadata). */
    fun nowPlaying(): JSONObject = withSession(
        pick = { MediaSelection.pickForStatus(it, ownPkg) },
        notFound = { JSONObject().put("playing", false) },
    ) { controller ->
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val md = controller.metadata
        val out = JSONObject()
            .put("playing", playing)
            .put("app", appLabel(controller.packageName))
        title(md)?.let { out.put("title", it) } // omit when unknown rather than send blank
        artist(md)?.let { out.put("artist", it) }
        DebugLog.log("media now_playing → ${controller.packageName} playing=$playing")
        out
    }

    /**
     * Active sessions, or null when notification access isn't granted (only a `SecurityException` means that).
     * Other failures (e.g. `RemoteException`) propagate so they aren't misreported as "grant access" — the
     * engine wraps them into a generic `{"error":...}`.
     */
    private fun activeSessions(): List<MediaController>? = try {
        manager.getActiveSessions(component)
    } catch (e: SecurityException) {
        DebugLog.log("media: session access not granted (${e.javaClass.simpleName})")
        null
    }

    /**
     * Resolve the active sessions, [pick] one, and run [body] on it — the shared preamble for [control],
     * [nowPlaying], and [setRepeat]. Returns [notEnabledError] when session access is missing, or [notFound]
     * (given whether the session list was empty) when nothing matches.
     */
    private inline fun withSession(
        pick: (List<SessionInfo>) -> Int?,
        notFound: (empty: Boolean) -> JSONObject,
        body: (MediaController) -> JSONObject,
    ): JSONObject {
        val controllers = activeSessions() ?: return notEnabledError()
        val idx = pick(controllers.mapIndexed { i, c -> c.toInfo(i) }) ?: return notFound(controllers.isEmpty())
        return body(controllers[idx])
    }

    private fun MediaController.toInfo(index: Int): SessionInfo {
        val actions = playbackState?.actions ?: 0L
        fun has(bit: Long) = actions and bit != 0L
        return SessionInfo(
            index = index,
            pkg = packageName,
            isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING,
            canPlay = has(PlaybackState.ACTION_PLAY) || has(PlaybackState.ACTION_PLAY_PAUSE),
            canPause = has(PlaybackState.ACTION_PAUSE) || has(PlaybackState.ACTION_PLAY_PAUSE),
            canNext = has(PlaybackState.ACTION_SKIP_TO_NEXT),
            canPrev = has(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            hasMetadata = metadata != null,
            // Repeat lives in the media-compat API, not framework PlaybackState; the bit is still set in the
            // framework actions long, so match it against the compat constant.
            canSetRepeat = has(PlaybackStateCompat.ACTION_SET_REPEAT_MODE),
        )
    }

    private fun notEnabledError(): JSONObject = JSONObject().put("error", "media control isn't enabled — grant notification access in Settings")

    private companion object {
        fun str(md: MediaMetadata?, key: String) = md?.getString(key)?.takeIf { it.isNotBlank() }

        fun title(md: MediaMetadata?) = str(md, MediaMetadata.METADATA_KEY_TITLE) ?: str(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)

        fun artist(md: MediaMetadata?) = str(md, MediaMetadata.METADATA_KEY_ARTIST)
            ?: str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: str(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    }
}
