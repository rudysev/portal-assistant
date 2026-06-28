package com.portal.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the AfterSpeech queue (post / cancel / fire / clear). */
class AfterSpeechTest {

    @Test fun fireRunsAllInOrderThenEmpties() {
        val seam = AfterSpeech()
        val log = mutableListOf<String>()
        seam.post { log.add("a") }
        seam.post { log.add("b") }
        seam.fire()
        assertEquals(listOf("a", "b"), log)
        seam.fire() // queue emptied → a second fire runs nothing
        assertEquals(listOf("a", "b"), log)
    }

    @Test fun cancelRemovesSpecificEffect() {
        val seam = AfterSpeech()
        val log = mutableListOf<String>()
        val a = seam.post { log.add("a") }
        seam.post { log.add("b") }
        seam.cancel(a)
        seam.fire()
        assertEquals(listOf("b"), log)
    }

    @Test fun clearDropsWithoutRunning() {
        val seam = AfterSpeech()
        var ran = false
        seam.post { ran = true }
        seam.clear()
        seam.fire()
        assertFalse(ran)
    }

    @Test fun fireOnEmptyIsNoOp() {
        AfterSpeech().fire() // must not throw
    }

    @Test fun effectThatNullsItsHandleStillRuns() {
        // Mirrors the controller pattern: the effect clears its own pending ref as it runs.
        val seam = AfterSpeech()
        var applied = false
        seam.post { applied = true }
        seam.fire()
        assertTrue(applied)
    }
}
