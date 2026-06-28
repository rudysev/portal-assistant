package com.portal.assistant.conversation

import com.portal.assistant.system.DeviceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the device-context system-prompt enrichment (timezone in Phase 1.1). */
class SystemContextTest {

    @Test fun enrichAppendsHeaderAndLines() {
        val out = SystemContext.enrich("BASE", listOf("line one", "line two"))
        assertTrue("keeps the base prompt", out.startsWith("BASE"))
        assertTrue("includes the header", out.contains(SystemContext.HEADER))
        assertTrue(out.contains("line one"))
        assertTrue(out.contains("line two"))
    }

    @Test fun enrichEmptyLinesIsNoOp() {
        assertEquals("BASE", SystemContext.enrich("BASE", emptyList()))
    }

    @Test fun timeLineFormatsClockAndZone() {
        val line = SystemContext.timeLine("Saturday, June 21, 2026 at 9:30 PM", "America/Los_Angeles")
        assertEquals(
            "Local date/time: Saturday, June 21, 2026 at 9:30 PM (America/Los_Angeles).",
            line,
        )
    }

    @Test fun enrichComposesWithTimeLine() {
        val out = SystemContext.enrich("BASE", listOf(SystemContext.timeLine("now", "Z")))
        assertTrue(out.contains("Local date/time: now (Z)."))
    }

    @Test fun locationLineFormatsPlaceAndCoords() {
        val line = SystemContext.locationLine(
            DeviceLocation("Seattle", "Washington", "United States", 47.6062, -122.3321),
        )
        assertEquals(
            "Approximate device location: Seattle, Washington, United States (lat 47.61, lon -122.33).",
            line,
        )
    }

    @Test fun locationLineOmitsBlankParts() {
        val line = SystemContext.locationLine(DeviceLocation("Tokyo", "", "", 35.68, 139.69))
        assertTrue(line.contains("Approximate device location: Tokyo (lat 35.68, lon 139.69)."))
    }

    @Test fun overrideLineUsesUserText() {
        assertEquals(
            "Approximate device location: Mountain View, CA (set by the user).",
            SystemContext.overrideLine("Mountain View, CA"),
        )
    }
}
