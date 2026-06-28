package com.portal.assistant.conversation.tools

import android.app.NotificationManager
import android.content.Context
import com.portal.assistant.conversation.AfterSpeech
import com.portal.commons.DebugLog
import org.json.JSONObject

/** Do Not Disturb state, echoed back to the model. */
data class DndState(val enabled: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled)
}

/**
 * The one Android site that touches Do Not Disturb, via [NotificationManager]'s interruption filter — a simple
 * on/off toggle (no timed auto-off; the user turns it back on/off explicitly). Thin shell (same pattern as
 * [VolumeController]); the only instance state is the deferred-enable [pendingEnable], so the owner must call
 * [dispose] on conversation teardown.
 *
 * **Enabling is deferred** to turn-end (via [AfterSpeech]) — the exact analog of [VolumeController]'s deferred
 * mute. `INTERRUPTION_FILTER_NONE` silences the assistant's own playback, so applying it immediately would eat
 * the spoken "okay, do not disturb is on" confirmation. We report enabled=true *now* (so the model confirms)
 * but apply the filter once the confirmation has finished playing. Disabling applies immediately so its
 * confirmation is audible. Reporting the intended state also sidesteps a real race: `setInterruptionFilter` is
 * asynchronous, so reading `currentInterruptionFilter` right after a write returns the stale value.
 *
 * **Requires notification-policy access** (`isNotificationPolicyAccessGranted`, granted in setup.sh). When it's
 * missing we return an error JSON rather than throwing — the model just reports it couldn't.
 */
class DoNotDisturbController(
    context: Context,
    private val afterSpeech: AfterSpeech,
    private val volume: VolumeController,
) {

    private val manager =
        context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // An enable queued on [afterSpeech] but not yet applied (see [setEnabled]) — the handle is the queued
    // Runnable. Cancelled by disable or teardown. @Volatile: scheduled/cancelled on the tool-exec thread,
    // cleared on the engine handler when AfterSpeech fires it.
    @Volatile private var pendingEnable: Runnable? = null

    /**
     * Tool-facing read. Guarded like the writes: without policy access `getCurrentInterruptionFilter` returns
     * `INTERRUPTION_FILTER_UNKNOWN` (not [NotificationManager.INTERRUPTION_FILTER_ALL]), which [isOn] would
     * misread as "on" — so we return an error JSON instead. A pending enable (confirmation playing) reports on,
     * so the model stays consistent.
     */
    fun current(): JSONObject {
        if (!manager.isNotificationPolicyAccessGranted) return JSONObject().put("error", "dnd access not granted")
        return DndState(isOn()).toJson()
    }

    fun setEnabled(enabled: Boolean): JSONObject {
        if (!manager.isNotificationPolicyAccessGranted) {
            DebugLog.log("dnd change skipped — notification policy access not granted")
            return JSONObject().put("error", "dnd access not granted")
        }
        if (!enabled) {
            cancelPendingEnable() // an explicit disable overrides a pending enable
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            // Our enable uses INTERRUPTION_FILTER_NONE (total silence), which mutes the ringer-affected
            // streams as a zen side effect — and FILTER_ALL does NOT reliably un-mute them, so after a DnD
            // on/off cycle the device can't ring an incoming call. Restore the ring via VolumeController (the
            // single AudioManager site) so "DnD off ⇒ the phone can ring" always holds. (Only the ring stream —
            // we never mute it elsewhere; VolumeController's mute targets STREAM_MUSIC.)
            val ringRestored = volume.unmuteRing()
            return DndState(false).toJson().also {
                DebugLog.log(if (ringRestored) "dnd disabled (ring un-muted)" else "dnd disabled (ring un-mute failed)")
            }
        }
        if (isOn()) {
            // Already enabling or on — don't schedule a redundant deferred apply.
            return DndState(true).toJson().also { DebugLog.log("dnd enable=true (already pending/on)") }
        }
        val r = Runnable {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            pendingEnable = null
            DebugLog.log("dnd enable applied (after speech)")
        }
        pendingEnable = afterSpeech.post(r)
        DebugLog.log("dnd enable=true (deferred to turn-end)")
        return DndState(true).toJson() // report intended state; model says "on"
    }

    /**
     * Cancel any pending deferred enable. Call on conversation teardown so it can't fire into the next one.
     *
     * Tradeoff (same class as deferred mute): if a conversation ends before the turn does — only on an
     * abnormal/early teardown, since normal flow plays the confirmation out — the model may have confirmed
     * "on" but the filter never applies. Acceptable: the user never heard the confirmation and the session
     * is gone.
     */
    fun dispose() = cancelPendingEnable()

    // pendingEnable (enable in-flight, confirmation playing) → report on to stay consistent with what we told the model.
    private fun isOn(): Boolean = pendingEnable != null || manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

    private fun cancelPendingEnable() {
        pendingEnable?.let { afterSpeech.cancel(it) }
        pendingEnable = null
    }
}
