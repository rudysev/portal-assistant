package com.portal.assistant.conversation.tools

import android.content.Context
import android.media.AudioManager
import com.portal.assistant.conversation.AfterSpeech
import com.portal.commons.DebugLog
import org.json.JSONObject

/** Speaker volume state, echoed back to the model. */
data class VolumeState(val percent: Int, val muted: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("level_percent", percent).put("muted", muted)
}

/**
 * The one Android site that touches [AudioManager], controlling the **media** stream ([STREAM]) — the same
 * stream the assistant voice (`USAGE_ASSISTANT`) and the timer chime (`USAGE_MEDIA`) play on, so this is the
 * volume the user perceives. Pure percent↔index math lives in [VolumeMath]; this is the thin shell (same
 * split as [Timers] / [TimerScheduler]). It also exposes [unmuteRing] so `DoNotDisturbController` can restore
 * the ring stream after DnD without a second AudioManager owner — keeping this the single AudioManager site.
 *
 * AudioManager is synchronous and main-thread-safe, so these run directly on the engine's tool-executor
 * thread — no dispatch hop, no lock (volume itself is system state, not app state). The only instance state
 * is the deferred-mute [pendingMute]; the owner must call [dispose] on conversation teardown so a scheduled
 * mute can't fire into the next conversation.
 */
class VolumeController(context: Context, private val afterSpeech: AfterSpeech) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxIndex = audioManager.getStreamMaxVolume(STREAM)

    // A mute queued on [afterSpeech] but not yet applied (see [setMuted]) — the handle is the queued Runnable.
    // Cancelled by unmute or any explicit volume change. @Volatile: scheduled/cancelled on the tool-exec
    // thread, cleared on the engine handler when AfterSpeech fires it.
    @Volatile private var pendingMute: Runnable? = null

    /** True device level — reports the curve's round-trip percent (honest), used by get_volume. */
    fun current(): VolumeState {
        val index = audioManager.getStreamVolume(STREAM)
        return VolumeState(VolumeMath.toPercent(index, maxIndex), isMuted(index))
    }

    /**
     * Set an absolute level (0..100). flag 0 → no system volume UI/beep — the model speaks the confirmation
     * (hands-free). A level >0 explicitly unmutes first (setStreamVolume alone won't clear a mute). Linear:
     * "50%" is the device's true half-scale.
     */
    fun setPercent(percent: Int): VolumeState {
        cancelPendingMute() // an explicit level change overrides a pending mute
        val index = VolumeMath.toIndex(percent, maxIndex)
        if (index > 0) audioManager.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
        audioManager.setStreamVolume(STREAM, index, 0)
        return current().also { DebugLog.log("volume set ${it.percent}% ($index/$maxIndex)") }
    }

    /**
     * Relative change by **one native device step** (`ADJUST_RAISE`/`ADJUST_LOWER`) — predictable single-notch
     * behaviour that respects the device's own volume curve, and unmutes as a side effect when raising.
     */
    fun adjust(raise: Boolean): VolumeState {
        cancelPendingMute()
        val dir = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(STREAM, dir, 0)
        return current().also { DebugLog.log("volume ${if (raise) "up" else "down"} → ${it.percent}%") }
    }

    /**
     * Real mute/unmute via [AudioManager.adjustStreamVolume] — preserves the underlying index and restores it
     * on unmute (unlike `setStreamVolume(0)`, which loses the prior level).
     *
     * **Mute is deferred** until the turn ends (via [AfterSpeech]): the assistant's voice shares [STREAM], so
     * muting immediately would silence its own "okay, muted" confirmation. We report muted=true now (so the
     * model confirms) but apply the actual mute once the confirmation has finished playing. Unmute applies
     * immediately so its confirmation is audible.
     */
    fun setMuted(muted: Boolean): VolumeState {
        if (!muted) {
            cancelPendingMute()
            audioManager.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
            return current().also { DebugLog.log("volume unmute → ${it.percent}% muted=${it.muted}") }
        }
        if (isMuted(audioManager.getStreamVolume(STREAM))) {
            // Already muting, muted, or at zero — don't schedule a redundant deferred apply.
            return current().also { DebugLog.log("volume mute=true (already pending/muted)") }
        }
        val r = Runnable {
            audioManager.adjustStreamVolume(STREAM, AudioManager.ADJUST_MUTE, 0)
            pendingMute = null
            DebugLog.log("volume mute applied (after speech)")
        }
        pendingMute = afterSpeech.post(r)
        DebugLog.log("volume mute=true (deferred to turn-end)")
        return VolumeState(current().percent, muted = true) // report intended state; model says "muted"
    }

    /**
     * Un-mute the **ring** stream. DnD-enable uses `INTERRUPTION_FILTER_NONE` (total silence), which mutes
     * STREAM_RING as a zen side effect that `INTERRUPTION_FILTER_ALL` doesn't reliably clear — so DnD-disable
     * calls this to guarantee "DnD off ⇒ the phone can ring". Lives here so [AudioManager] stays owned by this
     * one site. Best-effort: returns whether the unmute call succeeded, never throws.
     */
    fun unmuteRing(): Boolean = runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) }
        .isSuccess

    /**
     * Cancel any pending deferred mute. Call on conversation teardown so it can't fire into the next one.
     * Tradeoff (same as DND-enable): on an abnormal/early teardown the model may have confirmed "muted" but
     * the mute never applies — fine, the user didn't hear the confirmation and the session is gone.
     */
    fun dispose() = cancelPendingMute()

    // pendingMute (mute in-flight, confirmation playing) → report muted so the model stays consistent.
    private fun isMuted(index: Int): Boolean = pendingMute != null || audioManager.isStreamMute(STREAM) || index == 0

    private fun cancelPendingMute() {
        pendingMute?.let { afterSpeech.cancel(it) }
        pendingMute = null
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
    }
}
