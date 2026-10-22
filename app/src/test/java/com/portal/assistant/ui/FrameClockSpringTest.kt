package com.portal.assistant.ui

import androidx.compose.animation.core.Spring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Pure JVM tests for the frame-clock spring that eases the audio-reactive orb. */
class FrameClockSpringTest {

    // Compose's default spring: critically damped, StiffnessMedium → natural frequency ω = √stiffness rad/s.
    private val omega = sqrt(Spring.StiffnessMedium.toDouble())
    private fun nanos(seconds: Double) = (seconds * 1e9).toLong()

    @Test fun easesAlongTheDefaultSpringCurve() {
        // Worked example: from rest at 0 toward 1, after t = 1/ω the critically damped curve is at 1 − 2/e.
        val eased = FrameClockSpring(0f).step(target = 1f, elapsedNanos = nanos(1 / omega))
        assertEquals(0.2642f, eased.value, 0.001f)
    }

    @Test fun settlesExactlyOnTheTargetOnceUnderTheDisplayThreshold() {
        // The orb's full swing (scale 1 → 1.25) under the default 0.01 threshold settles at ωt ≈ 5, about 129 ms.
        val frame = nanos(1 / 60.0)
        var spring = FrameClockSpring(0f)
        var elapsed = 0L
        while (elapsed < nanos(0.100)) {
            spring = spring.step(target = 0.25f, elapsedNanos = frame)
            elapsed += frame
        }
        assertFalse(spring.isSettledAt(0.25f))
        while (elapsed < nanos(0.150)) {
            spring = spring.step(target = 0.25f, elapsedNanos = frame)
            elapsed += frame
        }
        assertEquals(FrameClockSpring(0.25f), spring)
        assertTrue(spring.isSettledAt(0.25f))
    }

    @Test fun neverOvershootsTheTarget() {
        var spring = FrameClockSpring(0f)
        repeat(60) {
            spring = spring.step(target = 0.25f, elapsedNanos = nanos(1 / 60.0))
            assertTrue("overshot to ${spring.value}", spring.value <= 0.25f)
        }
    }

    @Test fun aLongStallLandsOnTheTargetAndAZeroGapStaysPut() {
        assertEquals(FrameClockSpring(0.25f), FrameClockSpring(0f).step(target = 0.25f, elapsedNanos = nanos(3600.0)))
        val moving = FrameClockSpring(0.1f, velocity = 2f)
        assertEquals(moving, moving.step(target = 0.25f, elapsedNanos = 0L))
    }
}
