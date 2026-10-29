package com.portal.assistant.conversation

import com.portal.assistant.system.DeviceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the system prompt's Device Context section. */
class SystemContextTest {

    @Test fun enrichAppendsADeviceContextSectionOfBullets() {
        assertEquals(
            "BASE\n\n# Device Context\n- line one\n- line two",
            SystemContext.enrich("BASE", listOf("line one", "line two")),
        )
    }

    @Test fun enrichEmptyLinesIsNoOp() {
        assertEquals("BASE", SystemContext.enrich("BASE", emptyList()))
    }

    @Test fun timeLineSaysItIsTheTimeAtConversationStart() {
        val line = SystemContext.timeLine("Saturday, June 21, 2026 at 9:30 PM", "America/Los_Angeles")
        assertEquals(
            "Time at conversation start: Saturday, June 21, 2026 at 9:30 PM (America/Los_Angeles).",
            line,
        )
    }

    @Test fun enrichComposesWithTimeLine() {
        val out = SystemContext.enrich("BASE", listOf(SystemContext.timeLine("now", "Z")))
        assertTrue(out.contains("- Time at conversation start: now (Z)."))
    }

    @Test fun locationLineNamesThePlaceWithoutCoordinates() {
        val line = SystemContext.locationLine(
            DeviceLocation("Seattle", "Washington", "United States", 47.6062, -122.3321),
        )
        assertEquals(
            "Location: Seattle, Washington, United States.",
            line,
        )
    }

    @Test fun locationLineOmitsBlankParts() {
        val line = SystemContext.locationLine(DeviceLocation("Tokyo", "", "", 35.68, 139.69))
        assertEquals("Location: Tokyo.", line)
    }

    @Test fun overrideLineUsesUserText() {
        assertEquals(
            "Location: Mountain View, CA.",
            SystemContext.overrideLine("Mountain View, CA"),
        )
    }
}
