package com.portal.assistant.conversation.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the timer store — mirrors GeolocationTest. */
class TimersTest {

    private fun e(id: Int, label: String = "timer", fireAtMs: Long = 10_000, durationSec: Int = 10) = TimerEntry(id, label, fireAtMs, durationSec)

    @Test fun serializeParseRoundTrip() {
        val list = listOf(e(1, "pasta", 60_000, 60), e(2, "tea", 120_000, 120))
        val parsed = Timers.parse(Timers.serialize(list))
        assertEquals(list, parsed)
    }

    @Test fun parseMalformedIsEmpty() {
        assertTrue(Timers.parse("not json").isEmpty())
        assertTrue(Timers.parse(null).isEmpty())
        assertTrue(Timers.parse("").isEmpty())
        assertTrue(Timers.parse("{}").isEmpty()) // object, not array
    }

    @Test fun parseSkipsEntriesWithoutId() {
        val parsed = Timers.parse("""[{"label":"x","fireAtMs":1},{"id":5,"label":"ok","fireAtMs":2,"durationSec":1}]""")
        assertEquals(1, parsed.size)
        assertEquals(5, parsed[0].id)
    }

    @Test fun remainingSecFloorsAndClamps() {
        assertEquals(30L, Timers.remainingSec(e(1, fireAtMs = 30_500), nowMs = 0)) // 30.5s → 30
        assertEquals(0L, Timers.remainingSec(e(1, fireAtMs = 1_000), nowMs = 5_000)) // elapsed → 0, not negative
    }

    @Test fun activeDropsElapsed() {
        val list = listOf(e(1, fireAtMs = 5_000), e(2, fireAtMs = 20_000))
        val active = Timers.active(list, nowMs = 10_000)
        assertEquals(listOf(2), active.map { it.id })
    }

    @Test fun addAndRemove() {
        val list = Timers.withAdded(emptyList(), e(1))
        assertEquals(1, list.size)
        val after = Timers.withRemoved(list, 1)
        assertTrue(after.isEmpty())
        // removing an absent id is a no-op
        assertEquals(list, Timers.withRemoved(list, 99))
    }

    @Test fun findByLabelIsCaseInsensitiveAndTrimmed() {
        val list = listOf(e(1, "Pasta"), e(2, "Tea"))
        assertEquals(1, Timers.findByLabel(list, "  pasta ")!!.id)
        assertEquals(2, Timers.findByLabel(list, "TEA")!!.id)
        assertNull(Timers.findByLabel(list, "soup"))
    }

    @Test fun findByLabelReturnsFirstOnDuplicate() {
        val list = listOf(e(1, "timer"), e(2, "timer"))
        assertEquals(1, Timers.findByLabel(list, "timer")!!.id)
    }
}
