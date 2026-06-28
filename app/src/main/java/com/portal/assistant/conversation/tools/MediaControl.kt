package com.portal.assistant.conversation.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
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
    private val ownPkg = appContext.packageName
    private val component = ComponentName(appContext, PortalNotificationListener::class.java)
    private val manager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    /** Run [action] on the best matching session. */
    fun control(action: MediaAction): JSONObject {
        val controllers = activeSessions() ?: return notEnabledError()
        val infos = controllers.mapIndexed { i, c -> c.toInfo(i) }
        val idx = MediaSelection.pickForControl(infos, action, ownPkg) ?: return JSONObject().put(
            "error",
            if (controllers.isEmpty()) "nothing is playing" else "no media session supports that action",
        )
        val controller = controllers[idx]
        when (action) {
            MediaAction.PLAY -> controller.transportControls.play()
            MediaAction.PAUSE -> controller.transportControls.pause()
            MediaAction.NEXT -> controller.transportControls.skipToNext()
            MediaAction.PREVIOUS -> controller.transportControls.skipToPrevious()
        }
        val app = MediaSelection.friendlyApp(controller.packageName)
        DebugLog.log("media ${action.name.lowercase()} → $app")
        return JSONObject()
            .put("action", action.name.lowercase())
            .put("app", app)
    }

    /**
     * Start playing a song/artist/album/playlist by name on Spotify via its `spotify:search:<query>` deep
     * link (device-verified on the Portal's locked-down Spotify to start the top result and NOT take over the
     * screen). Unlike the transport commands this works because it's the app's own VIEW intent, not a
     * media-key or `playFromSearch` surface (both of which the Portal Spotify blocks).
     *
     * **Why search, not an exact `spotify:track:<id>`:** this plays on the device user's own Spotify account,
     * which is **Free tier** — Free can't on-demand-play a specific track, so a precise track URI just opens
     * the page silently. A broad search lands on an artist/playlist the Free tier *can* shuffle-play, so it
     * actually starts. Less precise than the exact track, but it plays.
     */
    fun play(query: String): JSONObject {
        val q = query.trim()
        if (q.isEmpty()) return JSONObject().put("error", "no song or artist specified")
        val pkg = SPOTIFY_PKGS.firstOrNull { appContext.packageManager.getLaunchIntentForPackage(it) != null }
            ?: return JSONObject().put("error", "Spotify isn't installed")
        return runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            DebugLog.log("play_music → spotify:search \"$q\"")
            JSONObject()
                .put("playing", true)
                .put("query", q)
                .put("app", MediaSelection.friendlyApp(pkg))
        }.getOrElse {
            DebugLog.log("play_music failed: ${it.message}")
            JSONObject().put("error", "couldn't start Spotify: ${it.message}")
        }
    }

    /** Report what's currently playing (or paused with known metadata). */
    fun nowPlaying(): JSONObject {
        val controllers = activeSessions() ?: return notEnabledError()
        val infos = controllers.mapIndexed { i, c -> c.toInfo(i) }
        val idx = MediaSelection.pickForStatus(infos, ownPkg)
            ?: return JSONObject().put("playing", false)
        val controller = controllers[idx]
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val md = controller.metadata
        val out = JSONObject()
            .put("playing", playing)
            .put("app", MediaSelection.friendlyApp(controller.packageName))
        title(md)?.let { out.put("title", it) } // omit when unknown rather than send blank
        artist(md)?.let { out.put("artist", it) }
        DebugLog.log("media now_playing → ${controller.packageName} playing=$playing")
        return out
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
        )
    }

    private fun notEnabledError(): JSONObject = JSONObject().put("error", "media control isn't enabled — grant notification access in Settings")

    private companion object {
        // Portal's standalone Spotify first, then the standard app as a fallback for other devices.
        val SPOTIFY_PKGS = listOf("com.facebook.aloha.spotifystandalone", "com.spotify.music")

        fun str(md: MediaMetadata?, key: String) = md?.getString(key)?.takeIf { it.isNotBlank() }

        fun title(md: MediaMetadata?) = str(md, MediaMetadata.METADATA_KEY_TITLE) ?: str(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)

        fun artist(md: MediaMetadata?) = str(md, MediaMetadata.METADATA_KEY_ARTIST)
            ?: str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: str(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    }
}
