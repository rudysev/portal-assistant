package com.portal.assistant.conversation.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Pure JVM tests for the linear, floored brightness percent↔raw math (max = 255, the Portal SCREEN_BRIGHTNESS range). */
class BrightnessMathTest {

    private val max = 255

    @Test fun toRawIsLinear() {
        assertEquals(128, BrightnessMath.toRaw(50, max)) // 50% of 255 = 127.5 → 128 (round, not floor)
        assertEquals(255, BrightnessMath.toRaw(100, max))
    }

    @Test fun toRawClampsRange() {
        assertEquals(BrightnessMath.MIN_RAW, BrightnessMath.toRaw(-20, max))
        assertEquals(255, BrightnessMath.toRaw(150, max))
    }

    @Test fun toRawFloorsLowValues() {
        // The floor applies to any computed raw below MIN_RAW, not just literal 0% — a voice "1%" stays visible.
        assertEquals(BrightnessMath.MIN_RAW, BrightnessMath.toRaw(0, max))
        assertEquals(BrightnessMath.MIN_RAW, BrightnessMath.toRaw(1, max))
        assertTrue(BrightnessMath.toRaw(2, max) >= BrightnessMath.MIN_RAW)
    }

    @Test fun toPercentIsLinear() {
        assertEquals(0, BrightnessMath.toPercent(0, max))
        assertEquals(50, BrightnessMath.toPercent(128, max))
        assertEquals(100, BrightnessMath.toPercent(255, max))
    }

    @Test fun roundTripWithinOneStepAboveFloor() {
        // Above the floor the round-trip is honest to ~1 raw step; below it the floor intentionally lifts it.
        val tolerance = 1
        for (p in 6..100) {
            val rt = BrightnessMath.toPercent(BrightnessMath.toRaw(p, max), max)
            assertTrue("p=$p round-tripped to $rt", abs(rt - p) <= tolerance)
        }
    }

    @Test fun steppedNudgesAndClamps() {
        assertEquals(65, BrightnessMath.stepped(50, up = true, stepPercent = 15))
        assertEquals(35, BrightnessMath.stepped(50, up = false, stepPercent = 15))
        assertEquals(100, BrightnessMath.stepped(95, up = true, stepPercent = 15))
        assertEquals(0, BrightnessMath.stepped(5, up = false, stepPercent = 15))
    }

    @Test fun maxRawZeroIsSafe() {
        assertEquals(0, BrightnessMath.toRaw(50, 0))
        assertEquals(0, BrightnessMath.toPercent(5, 0))
    }
}
