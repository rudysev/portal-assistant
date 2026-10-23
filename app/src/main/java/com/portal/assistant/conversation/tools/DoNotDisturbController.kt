package com.portal.assistant.conversation.tools

import android.app.NotificationManager
import android.content.Context
import com.portal.commons.DebugLog
import org.json.JSONObject

/** Do Not Disturb state, echoed back to the model. */
data class DndState(val enabled: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled)
}

/**
 * The one Android site that touches Do Not Disturb, via [NotificationManager]'s interruption filter — a simple
 * on/off toggle (no timed auto-off; the user turns it back on/off explicitly). Thin shell (same pattern as
 * [VolumeController]); the only instance state is the [deferredEnable], so the owner must call [dispose] on
 * conversation teardown.
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
class DoNotDisturbController(context: Context, afterSpeech: AfterSpeech) {

    private val manager =
        context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // An enable queued for turn end but not yet applied (see [setEnabled]). Cancelled by disable or teardown.
    private val deferredEnable = DeferredToggle(afterSpeech)

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

    fun setEnabled(enabled: Boolean): JSONObject = when {
        !manager.isNotificationPolicyAccessGranted -> {
            DebugLog.log("dnd change skipped — notification policy access not granted")
            JSONObject().put("error", "dnd access not granted")
        }

        !enabled -> disable()

        // Already enabling or on — don't schedule a redundant deferred apply.
        isOn() -> DndState(true).toJson().also { DebugLog.log("dnd enable=true (already pending/on)") }

        else -> enableAfterSpeech()
    }

    private fun disable(): JSONObject {
        deferredEnable.cancel() // an explicit disable overrides a pending enable
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        // Leave the ringer alone. Our enable (INTERRUPTION_FILTER_NONE) puts the ringer mode into SILENT, and
        // ZenModeHelper puts the previous ringer mode back on its own, asynchronously, once zen is off. Touching
        // the ring stream here (an ADJUST_UNMUTE used to) races that restore: AudioService re-writes the SILENT
        // it read a moment earlier, and the device can no longer ring (device-verified on gen2).
        return DndState(false).toJson().also { DebugLog.log("dnd disabled") }
    }

    private fun enableAfterSpeech(): JSONObject {
        deferredEnable.schedule {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            DebugLog.log("dnd enable applied (after speech)")
        }
        DebugLog.log("dnd enable=true (deferred to turn-end)")
        return DndState(true).toJson() // report intended state; model says "on"
    }

    /** Drop any pending deferred enable. Call on conversation teardown so it can't fire into the next one. */
    fun dispose() = deferredEnable.dispose()

    // A pending enable (confirmation playing) → report on to stay consistent with what we told the model.
    private fun isOn(): Boolean = deferredEnable.isPending || manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
}
