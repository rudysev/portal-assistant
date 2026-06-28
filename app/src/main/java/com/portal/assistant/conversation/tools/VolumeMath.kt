package com.portal.assistant.conversation.tools

import kotlin.math.roundToInt

/**
 * Pure volume percent↔index conversion — Android-free so it's unit-tested (same pattern as [Timers] /
 * [com.portal.assistant.system.Geolocation]). The Android shell ([VolumeController]) wraps these around
 * AudioManager's integer stream-volume index (0..maxIndex, device-specific — 18 on the Portal).
 *
 * **Linear** mapping: "50%" is the device's true half-scale (index 9/18 on the Portal). We deliberately do
 * NOT apply a perceptual curve — what's loud on one device may not be on another, so the percent reflects the
 * actual stream level. Relative up/down is the device's own native single step (see [VolumeController.adjust]),
 * not computed here.
 */
object VolumeMath {

    /** A 0..100 percent → a 0..maxIndex AudioManager index (rounded, clamped). */
    fun toIndex(percent: Int, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        return (percent.coerceIn(0, 100) / 100.0 * maxIndex).roundToInt().coerceIn(0, maxIndex)
    }

    /** A 0..maxIndex AudioManager index → a 0..100 percent (rounded). */
    fun toPercent(index: Int, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        return (index.coerceIn(0, maxIndex) * 100.0 / maxIndex).roundToInt()
    }
}
