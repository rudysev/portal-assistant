package com.portal.assistant.conversation.tools

import kotlin.math.roundToInt

/**
 * Pure brightness percent↔raw conversion — Android-free so it's unit-tested (same split as [VolumeMath] /
 * [Timers]). The Android shell ([BrightnessController]) wraps these around `Settings.System.SCREEN_BRIGHTNESS`,
 * whose value is a 0..[maxRaw] integer ([maxRaw] = 255 on the Portal / API 28).
 *
 * **Linear** mapping like volume — "50%" is the device's true half-scale — with one twist: a **minimum floor**
 * ([MIN_RAW]). Any computed raw is clamped *up* to [MIN_RAW], not just literal 0%, so a voice "set brightness to
 * 0/1%" can never drive the kitchen display fully black and out of reach of the next voice command. Because the
 * floor is real device state, [toPercent] reports the honest round-trip (post-floor) percent — so `set 0%` then
 * `get` reads back ~[MIN_RAW]-as-percent, not 0 (same "report the true device level" philosophy as volume).
 *
 * Relative up/down is computed here ([stepped]) — brightness has no native device step (unlike AudioManager's
 * ADJUST_RAISE/LOWER), so we define [STEP_PERCENT].
 */
object BrightnessMath {

    /** Smallest raw we'll ever write — keeps the display visible/recoverable after a "0%" voice command. */
    const val MIN_RAW = 12 // ~5% of 255

    /** One nudge for [stepped] / portal.adjust_brightness — our own step (no hardware notch for brightness). */
    const val STEP_PERCENT = 15

    /** A 0..100 percent → a [MIN_RAW]..[maxRaw] SCREEN_BRIGHTNESS value (rounded, clamped, floored). */
    fun toRaw(percent: Int, maxRaw: Int): Int {
        if (maxRaw <= 0) return 0
        val raw = (percent.coerceIn(0, 100) / 100.0 * maxRaw).roundToInt()
        return raw.coerceIn(MIN_RAW.coerceAtMost(maxRaw), maxRaw)
    }

    /** A 0..[maxRaw] SCREEN_BRIGHTNESS value → a 0..100 percent (rounded). */
    fun toPercent(raw: Int, maxRaw: Int): Int {
        if (maxRaw <= 0) return 0
        return (raw.coerceIn(0, maxRaw) * 100.0 / maxRaw).roundToInt()
    }

    /** Nudge a 0..100 percent up/down by [stepPercent], clamped to 0..100 (the floor is applied later in [toRaw]). */
    fun stepped(percent: Int, up: Boolean, stepPercent: Int = STEP_PERCENT): Int {
        val delta = if (up) stepPercent else -stepPercent
        return (percent + delta).coerceIn(0, 100)
    }
}
