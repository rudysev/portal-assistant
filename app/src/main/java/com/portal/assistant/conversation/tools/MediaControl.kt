package com.portal.assistant.conversation.tools

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.portal.commons.DebugLog
import org.json.JSONObject

/**
 * The one Android site that touches the media-session APIs. Reads the device's active sessions via
 * [MediaSessionManager] (gated on [PortalNotificationListener] being an enabled notification listener) and
 * drives transport on the right one. Pure selection lives in [MediaSelection]; this is the thin shell (same
 * split as [VolumeMath] / [VolumeController]). Starting music by name needs no session: that's [MusicLauncher].
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
    fun control(action: MediaAction): JSONObject = withSession(
        pick = { MediaSelection.pickForControl(it, action, ownPkg) },
        notFound = { empty ->
            JSONObject().put("error", if (empty) "nothing is playing" else "no media session supports that action")
        },
    ) { controller ->
        when (action) {
            MediaAction.PLAY -> controller.transportControls.play()
            MediaAction.PAUSE -> controller.transportControls.pause()
            MediaAction.NEXT -> controller.transportControls.skipToNext()
            MediaAction.PREVIOUS -> controller.transportControls.skipToPrevious()
        }
        val app = PackageCatalog.labelFor(appContext, controller.packageName)
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
            notFound = { empty ->
                JSONObject().put(
                    "error",
                    if (empty) "nothing is playing" else "repeat isn't available on the current app",
                )
            },
        ) { controller ->
            val app = PackageCatalog.labelFor(appContext, controller.packageName)
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

    /** Report what's currently playing (or paused with known metadata). */
    fun nowPlaying(): JSONObject = withSession(
        pick = { MediaSelection.pickForStatus(it, ownPkg) },
        notFound = { JSONObject().put("playing", false) },
    ) { controller ->
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val md = controller.metadata
        val out = JSONObject()
            .put("playing", playing)
            .put("app", PackageCatalog.labelFor(appContext, controller.packageName))
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
        val idx = pick(controllers.mapIndexed { i, c -> c.toInfo(i) })
        return if (idx == null) notFound(controllers.isEmpty()) else body(controllers[idx])
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

    private fun notEnabledError(): JSONObject =
        JSONObject().put("error", "media control isn't enabled — grant notification access in Settings")

    private companion object {
        fun str(md: MediaMetadata?, key: String) = md?.getString(key)?.takeIf { it.isNotBlank() }

        fun title(md: MediaMetadata?) =
            str(md, MediaMetadata.METADATA_KEY_TITLE) ?: str(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)

        fun artist(md: MediaMetadata?) = str(md, MediaMetadata.METADATA_KEY_ARTIST)
            ?: str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: str(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    }
}
