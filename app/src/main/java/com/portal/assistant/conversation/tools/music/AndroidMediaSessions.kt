package com.portal.assistant.conversation.tools.music

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.portal.assistant.conversation.tools.PackageCatalog
import com.portal.assistant.conversation.tools.PortalNotificationListener
import com.portal.commons.DebugLog

/**
 * [MediaSessions] on the device's [MediaSessionManager], gated on [PortalNotificationListener] being an enabled
 * notification listener. Platform calls only: selection and results live in [Music].
 *
 * **Why sessions, not media keys:** on this Portal `dispatchMediaKeyEvent` is intercepted by Alexa; talking to the
 * music app's [MediaController] directly bypasses that.
 *
 * **Threading:** one-shot per tool call (`getActiveSessions` + cached state/metadata reads + a oneway transport call,
 * no `MediaController.Callback` registered), so it's safe on the tool-executor thread with no Looper. [active]
 * re-reads the sessions on every call and keeps them, so [transport] / [setRepeat] drive the very controller the
 * index was picked from.
 */
class AndroidMediaSessions(context: Context) : MediaSessions {

    private val appContext = context.applicationContext
    private val component = ComponentName(appContext, PortalNotificationListener::class.java)
    private val manager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    // Written by [active], read by [transport] / [setRepeat]: all on the single `tool-exec` thread (ToolRunner runs
    // every built-in tool there, one after another), so no synchronisation is needed.
    private var lastRead: List<MediaController> = emptyList()

    override fun active(): List<ActiveSession>? = controllers()?.also { lastRead = it }
        ?.mapIndexed { i, c -> ActiveSession(c.toInfo(i), title(c.metadata), artist(c.metadata)) }

    override fun appLabel(pkg: String): String = PackageCatalog.labelFor(appContext, pkg)

    override fun transport(index: Int, action: MediaAction) {
        val controls = lastRead[index].transportControls
        when (action) {
            MediaAction.PLAY -> controls.play()
            MediaAction.PAUSE -> controls.pause()
            MediaAction.NEXT -> controls.skipToNext()
            MediaAction.PREVIOUS -> controls.skipToPrevious()
        }
    }

    override fun setRepeat(index: Int, mode: RepeatMode) {
        // setRepeatMode is a media-compat transport control; bridge the framework session token into a
        // MediaControllerCompat to reach the app's onSetRepeatMode (the Android Auto / Assistant path).
        MediaControllerCompat(appContext, MediaSessionCompat.Token.fromToken(lastRead[index].sessionToken))
            .transportControls.setRepeatMode(
                when (mode) {
                    RepeatMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
                    RepeatMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
                    RepeatMode.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE
                },
            )
    }

    /**
     * Active sessions, or null when notification access isn't granted (only a `SecurityException` means that).
     * Other failures (e.g. `RemoteException`) propagate so they aren't misreported as "grant access": the tool runner
     * wraps them into a generic `{"error":...}`.
     */
    private fun controllers(): List<MediaController>? = try {
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
            // Repeat lives in the media-compat API, not framework PlaybackState; the bit is still set in the
            // framework actions long, so match it against the compat constant.
            canSetRepeat = has(PlaybackStateCompat.ACTION_SET_REPEAT_MODE),
        )
    }

    private companion object {
        fun str(md: MediaMetadata?, key: String) = md?.getString(key)?.takeIf { it.isNotBlank() }

        fun title(md: MediaMetadata?) =
            str(md, MediaMetadata.METADATA_KEY_TITLE) ?: str(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)

        fun artist(md: MediaMetadata?) = str(md, MediaMetadata.METADATA_KEY_ARTIST)
            ?: str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: str(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    }
}
