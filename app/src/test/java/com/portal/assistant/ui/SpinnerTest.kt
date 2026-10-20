package com.portal.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM tests for the loading spinner's frame-clock rotation. */
class SpinnerTest {

    private val periodNanos = SPINNER_PERIOD_MS * 1_000_000L

    @Test fun startsAtZero() {
        assertEquals(0f, spinnerDegrees(0L), 0.01f)
    }

    @Test fun turnsInProportionToElapsedTime() {
        assertEquals(90f, spinnerDegrees(periodNanos / 4), 0.01f)
        assertEquals(180f, spinnerDegrees(periodNanos / 2), 0.01f)
    }

    @Test fun wrapsEveryFullTurnSoTheAngleStaysSmall() {
        assertEquals(0f, spinnerDegrees(periodNanos), 0.01f)
        assertEquals(90f, spinnerDegrees(1_000 * periodNanos + periodNanos / 4), 0.01f)
    }
}
