package com.portal.assistant.conversation.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Pure JVM tests for the linear volume percent↔index math (max = 18, the Portal's STREAM_MUSIC range). */
class VolumeMathTest {

    private val max = 18

    @Test fun toIndexIsLinear() {
        assertEquals(0, VolumeMath.toIndex(0, max))
        assertEquals(9, VolumeMath.toIndex(50, max)) // true device half-scale
        assertEquals(18, VolumeMath.toIndex(100, max))
        assertEquals(6, VolumeMath.toIndex(33, max)) // 33% of 18 = 5.94 → 6 (round, not floor)
    }

    @Test fun toIndexClamps() {
        assertEquals(0, VolumeMath.toIndex(-20, max))
        assertEquals(18, VolumeMath.toIndex(150, max))
    }

    @Test fun toPercentIsLinear() {
        assertEquals(0, VolumeMath.toPercent(0, max))
        assertEquals(50, VolumeMath.toPercent(9, max))
        assertEquals(100, VolumeMath.toPercent(18, max))
    }

    @Test fun roundTripExactForCleanValues() {
        for (p in listOf(0, 50, 100)) {
            assertEquals(p, VolumeMath.toPercent(VolumeMath.toIndex(p, max), max))
        }
    }

    @Test fun roundTripWithinOneStepOtherwise() {
        val tolerance = (100 + max - 1) / max // ceil(100/max)
        for (p in 0..100) {
            val rt = VolumeMath.toPercent(VolumeMath.toIndex(p, max), max)
            assertTrue("p=$p round-tripped to $rt", abs(rt - p) <= tolerance)
        }
    }

    @Test fun maxIndexZeroIsSafe() {
        assertEquals(0, VolumeMath.toIndex(50, 0))
        assertEquals(0, VolumeMath.toPercent(5, 0))
    }
}
