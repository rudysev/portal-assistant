package com.portal.assistant.conversation.tools

import android.content.Context
import android.provider.Settings
import com.portal.commons.DebugLog
import org.json.JSONObject

/** Display brightness state, echoed back to the model. */
data class BrightnessState(val percent: Int) {
    fun toJson(): JSONObject = JSONObject().put("level_percent", percent)
}

/**
 * The one Android site that touches display brightness, via `Settings.System.SCREEN_BRIGHTNESS` (a 0..[MAX_RAW]
 * system-wide integer). Pure percent↔raw math (with the visibility floor) lives in [BrightnessMath]; this is the
 * thin shell (same split as [VolumeController] / [VolumeMath]).
 *
 * Settings writes are synchronous and main-thread-safe, so these run directly on the engine's tool-executor
 * thread — no dispatch hop, no lock (brightness is system state, not app state). No instance state, so no
 * [dispose] needed.
 *
 * **Requires WRITE_SETTINGS** (an appop, granted in setup.sh). Reads usually work without it; only writes need
 * it — so [current] can succeed while [setPercent]/[adjust] return an error JSON when the grant is missing
 * (we never throw — the model just reports it couldn't).
 */
class BrightnessController(context: Context) {

    private val appContext = context.applicationContext

    /** True device level — reports the post-floor round-trip percent (honest), used by get_brightness. */
    fun current(): BrightnessState {
        val raw = Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
        return BrightnessState(BrightnessMath.toPercent(raw, MAX_RAW))
    }

    /** Set an absolute level (0..100). 0 maps to the visible floor, not off (see [BrightnessMath]). */
    fun setPercent(percent: Int): JSONObject {
        if (!Settings.System.canWrite(appContext)) return notGranted()
        val raw = BrightnessMath.toRaw(percent, MAX_RAW)
        writeBrightness(raw)
        val state = current()
        DebugLog.log("brightness set ${state.percent}% ($raw/$MAX_RAW)")
        return state.toJson()
    }

    /** Relative change by one [BrightnessMath.STEP_PERCENT] step — math-stepped (no hardware notch for brightness). */
    fun adjust(up: Boolean): JSONObject {
        if (!Settings.System.canWrite(appContext)) return notGranted()
        val target = BrightnessMath.stepped(current().percent, up)
        val raw = BrightnessMath.toRaw(target, MAX_RAW)
        writeBrightness(raw)
        val state = current()
        DebugLog.log("brightness ${if (up) "up" else "down"} → ${state.percent}%")
        return state.toJson()
    }

    // Force manual mode first, else auto-brightness overrides the write. Side effect: stays manual until the
    // user re-enables auto in Settings — expected for a deliberate voice brightness command.
    private fun writeBrightness(raw: Int) {
        Settings.System.putInt(
            appContext.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
    }

    private fun notGranted(): JSONObject {
        DebugLog.log("brightness write skipped — WRITE_SETTINGS not granted")
        return JSONObject().put("error", "brightness permission not granted")
    }

    private companion object {
        const val MAX_RAW = 255 // SCREEN_BRIGHTNESS int range on API 28
    }
}
