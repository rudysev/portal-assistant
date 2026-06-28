package com.portal.assistant.conversation.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TimerStore] is the process-wide observable the UI reads; [TimerScheduler.mutate] calls [publish] after
 * every store write. These cover that contract on the pure flow (the scheduler's prefs/AlarmManager I/O is
 * Android and out of scope here). Singleton, so reset after each test.
 */
class TimerStoreTest {

    @After fun reset() = TimerStore.publish(emptyList())

    @Test fun publishUpdatesTheFlow() {
        assertEquals(emptyList<TimerEntry>(), TimerStore.timers.value)
        val entries = listOf(TimerEntry(id = 1, label = "Pasta", fireAtMs = 123_000L, durationSec = 120))
        TimerStore.publish(entries)
        assertEquals(entries, TimerStore.timers.value)
    }

    @Test fun publishEmptyClearsTheFlow() {
        TimerStore.publish(listOf(TimerEntry(id = 2, label = "Tea", fireAtMs = 9_000L, durationSec = 9)))
        TimerStore.publish(emptyList())
        assertEquals(emptyList<TimerEntry>(), TimerStore.timers.value)
    }
}
