package com.portal.assistant.wake

import com.portal.commons.audio.CaptureLoss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * JVM tests for [ForegroundWake]'s rules — when it arms, downloads, retries and gives up — driven only through its
 * five calls and two callbacks, with a scripted [FakeWakeModel], a recording [FakeDetector] and hand-stepped `io` and
 * `main` executors (the test picks when the download runs and when its result lands).
 */
class ForegroundWakeTest {

    private val model = FakeWakeModel()
    private val io = SteppedExecutor()
    private val main = SteppedExecutor()
    private val later = DelayedExecutor()
    private val detectors = mutableListOf<FakeDetector>()
    private val wakes = mutableListOf<String>()
    private val setups = mutableListOf<ModelSetup>()
    private val events = mutableListOf<String>() // detector pauses and wakes, in order
    private var micPermitted = true
    private var online = true

    private fun foregroundWake(supported: Boolean = true) = ForegroundWake(
        supported = supported,
        micPermitted = { micPermitted },
        online = { online },
        model = model,
        detectors = { dir, listener -> FakeDetector(dir, listener, events).also { detectors += it } },
        io = io,
        main = main,
        later = later,
        onWake = { wakeId ->
            wakes += wakeId
            events += "onWake($wakeId)"
        },
        onSetup = { setups += it },
    )

    /** The one detector built so far. */
    private val detector get() = detectors.single()

    @Test fun anUnsupportedDeviceNeverBuildsADetectorOrDownloads() {
        val wake = foregroundWake(supported = false)
        wake.foregrounded()
        wake.conversationStarting()
        wake.conversationEnded()
        wake.backgrounded()
        wake.foregrounded()
        wake.close()

        assertTrue(detectors.isEmpty())
        assertEquals(0, model.installs)
        assertTrue(io.isEmpty())
        assertTrue(setups.isEmpty())
    }

    @Test fun withoutTheMicNothingArmsUntilTheFirstConversationEndsWithItGranted() {
        model.installed = true
        micPermitted = false
        val wake = foregroundWake()

        wake.foregrounded()
        assertTrue(detectors.isEmpty()) // no model load for a capture that can only fail

        wake.conversationStarting() // the first conversation is what asks for the mic
        micPermitted = true
        wake.conversationEnded()
        assertEquals(1, detector.starts)
    }

    @Test fun anInstalledModelArmsOnForegroundPausesOnBackgroundAndResumesTheSameDetector() {
        model.installed = true
        val wake = foregroundWake()

        wake.foregrounded()
        assertEquals(model.dir, detector.dir)
        assertEquals(1, detector.starts)

        wake.backgrounded()
        assertEquals(1, detector.pauses)
        assertFalse(detector.closed) // the model stays loaded

        wake.foregrounded()
        assertEquals(1, detectors.size) // resumed, not rebuilt
        assertEquals(2, detector.starts)
        assertEquals(0, model.installs)
    }

    @Test fun aFirstRunDownloadsOnceThenArms() {
        val wake = foregroundWake()

        wake.foregrounded()
        wake.foregrounded() // a second foreground during the download starts nothing
        assertEquals(listOf(ModelSetup.Downloading(0f)), setups)
        io.runAll()
        assertEquals(1, model.installs)
        assertEquals(listOf(ModelSetup.Downloading(0f)), setups) // progress waits for main

        main.runAll()
        assertEquals(listOf(ModelSetup.Downloading(0f), ModelSetup.Downloading(0.5f), ModelSetup.Idle), setups)
        assertEquals(1, detector.starts)
    }

    @Test fun aDownloadFinishingOffScreenDoesNotArmUntilTheNextForeground() {
        val wake = foregroundWake()
        wake.foregrounded()
        wake.backgrounded()
        io.runAll()
        main.runAll()
        assertTrue(detectors.isEmpty())

        wake.foregrounded()
        assertEquals(1, detector.starts)
        assertEquals(1, model.installs)
    }

    @Test fun aDownloadFinishingDuringAConversationArmsWhenItEnds() {
        val wake = foregroundWake()
        wake.foregrounded()
        wake.conversationStarting()
        io.runAll()
        main.runAll()
        assertTrue(detectors.isEmpty())

        wake.conversationEnded()
        assertEquals(1, detector.starts)
    }

    @Test fun offlineSkipsTheDownloadQuietlyWithoutCountingAnAttempt() {
        model.failNext(2)
        online = false
        val wake = foregroundWake()

        repeat(5) {
            wake.backgrounded()
            wake.foregrounded()
        } // more offline visits than the retry limit
        assertTrue(io.isEmpty())
        assertTrue(setups.isEmpty())

        online = true
        repeat(3) {
            // two failures and a success still fit: no offline visit was counted
            wake.backgrounded()
            wake.foregrounded()
            io.runAll()
            main.runAll()
        }
        assertEquals(3, model.installs)
        assertEquals(1, detector.starts)
    }

    @Test fun threeFailedDownloadsEachShowFailedThenItGivesUp() {
        model.failNext(3)
        val wake = foregroundWake()

        repeat(3) {
            wake.backgrounded()
            wake.foregrounded()
            io.runAll()
            main.runAll()
            assertEquals(ModelSetup.Failed, setups.last())
        }
        setups.clear()

        wake.backgrounded()
        wake.foregrounded()
        wake.conversationStarting()
        wake.conversationEnded()
        assertTrue(io.isEmpty())
        assertTrue(setups.isEmpty())
        assertEquals(3, model.installs)
        assertTrue(detectors.isEmpty())
    }

    @Test fun aSuccessfulDownloadResetsTheFailureCount() {
        model.failNext(2)
        val wake = foregroundWake()
        repeat(3) {
            // fail, fail, succeed
            wake.backgrounded()
            wake.foregrounded()
            io.runAll()
            main.runAll()
        }
        assertEquals(ModelSetup.Idle, setups.last())

        model.delete() // e.g. a load failure deleted it; two more failures now still don't give up
        model.failNext(2)
        repeat(3) {
            wake.backgrounded()
            wake.foregrounded()
            io.runAll()
            main.runAll()
        }
        assertEquals(6, model.installs)
        assertEquals(ModelSetup.Idle, setups.last())
    }

    @Test fun aModelThatWontLoadIsReDownloadedOnceThenGivenUp() {
        model.installed = true
        val wake = foregroundWake()
        wake.foregrounded()

        detectors[0].reportsUnavailable()
        main.runAll()
        assertTrue(detectors[0].closed) // releases the mic the failed load opened
        assertEquals(1, model.deletes)
        io.runAll()
        main.runAll()
        assertEquals(1, model.installs) // a fresh copy, then armed on a new detector
        assertEquals(1, detectors[1].starts)

        setups.clear()
        detectors[1].reportsUnavailable()
        main.runAll()
        assertEquals(2, model.deletes)
        assertEquals(listOf(ModelSetup.Failed), setups)
        assertTrue(io.isEmpty())

        detectors[1].reportsUnavailable() // a repeated report after giving up does nothing
        main.runAll()
        wake.backgrounded()
        wake.foregrounded()
        assertEquals(2, model.deletes)
        assertEquals(listOf(ModelSetup.Failed), setups)
        assertEquals(2, detectors.size)
        assertEquals(1, model.installs)
    }

    @Test fun aWakePausesBeforeHandingOffAndReArmsWhenTheConversationEnds() {
        model.installed = true
        val wake = foregroundWake()
        wake.foregrounded()

        detector.hears("jarvis")
        assertTrue(wakes.isEmpty()) // delivered on main
        main.runAll()
        assertEquals(listOf("pause", "onWake(jarvis)"), events)

        wake.conversationStarting() // the service's usual call — a harmless repeat
        wake.conversationEnded()
        assertEquals(2, detector.starts)
        assertEquals(1, detectors.size)
    }

    @Test fun aRefusedTriggerReArmsOnScreenButNotInTheBackground() {
        model.installed = true
        val wake = foregroundWake()
        wake.foregrounded()

        wake.conversationStarting()
        wake.conversationEnded() // refused: nothing opened
        assertEquals(2, detector.starts)

        wake.backgrounded()
        wake.conversationStarting()
        wake.conversationEnded()
        assertEquals(2, detector.starts)
    }

    @Test fun closeReleasesTheDetectorAndNothingArmsAfterwards() {
        model.installed = true
        val wake = foregroundWake()
        wake.foregrounded()

        wake.close()
        assertTrue(detector.closed)

        wake.foregrounded()
        wake.conversationEnded()
        detector.hears("jarvis") // a wake already in flight from the capture thread
        main.runAll()
        assertEquals(1, detectors.size)
        assertEquals(1, detector.starts)
        assertTrue(wakes.isEmpty())
    }

    @Test fun aDownloadLandingAfterCloseArmsNothing() {
        val wake = foregroundWake()
        wake.foregrounded()
        wake.close()
        io.runAll()
        main.runAll()
        assertTrue(detectors.isEmpty())
    }

    @Test fun aDroppedCaptureRetriesStartOnceAfterADelayThenWaitsForTheNextArm() {
        model.installed = true
        val wake = foregroundWake()
        wake.foregrounded()

        detector.loses(CaptureLoss.Dropped("device died"))
        main.runAll()
        assertEquals(1, detector.starts) // not inline: the retry waits ~1 s
        assertEquals(listOf(1_000L), later.delays)
        later.runAll()
        assertEquals(2, detector.starts)

        detector.loses(CaptureLoss.Dropped("device died again")) // the retry dropped too: only once
        main.runAll()
        later.runAll()
        assertEquals(2, detector.starts)

        wake.conversationStarting()
        wake.conversationEnded() // the next arm restarts, and earns a fresh retry
        assertEquals(3, detector.starts)
        detector.loses(CaptureLoss.Dropped("device died a third time"))
        main.runAll()
        later.runAll()
        assertEquals(4, detector.starts)
    }

    @Test fun aRetryDueAfterTheMicWasReleasedStartsNothing() {
        model.installed = true
        val wake = foregroundWake()
        wake.foregrounded()

        detector.loses(CaptureLoss.Dropped("device died"))
        main.runAll()
        wake.backgrounded()
        later.runAll()
        assertEquals(1, detector.starts)
    }

    @Test fun aNotGrantedLossWaitsForTheNextArm() {
        model.installed = true
        val wake = foregroundWake()
        wake.foregrounded()

        detector.loses(CaptureLoss.NotGranted("mic busy"))
        main.runAll()
        assertTrue(later.delays.isEmpty())
        assertEquals(1, detector.starts)

        wake.backgrounded()
        wake.foregrounded()
        assertEquals(2, detector.starts)
    }

    /** An in-memory [WakeModel]; each scripted result is used by one [install], then installs succeed. */
    private class FakeWakeModel : WakeModel {
        var installed = false
        var installs = 0
        var deletes = 0
        private val results = ArrayDeque<Boolean>()

        override val dir = File("wake-model")

        fun failNext(times: Int) = repeat(times) { results.add(false) }

        override fun isInstalled() = installed

        override fun install(onProgress: (Float) -> Unit): Boolean {
            installs++
            onProgress(0.5f)
            val ok = results.pollFirst() ?: true
            installed = ok
            return ok
        }

        override fun delete() {
            deletes++
            installed = false
        }
    }

    /** Records what [ForegroundWake] asks of it; the test fires its wake or reports it unavailable. */
    private class FakeDetector(
        val dir: File,
        private val listener: Detector.Listener,
        private val events: MutableList<String>,
    ) : Detector {
        var starts = 0
        var pauses = 0
        var closed = false

        override fun start() {
            starts++
        }

        override fun pause() {
            pauses++
            events += "pause"
        }

        override fun close() {
            closed = true
        }

        fun hears(wakeId: String) = listener.onWake(wakeId)

        fun reportsUnavailable() = listener.onUnavailable()

        fun loses(loss: CaptureLoss) = listener.onCaptureLost(loss)
    }

    /** Records each delay and runs the delayed tasks only when the test says so. */
    private class DelayedExecutor : (Long, Runnable) -> Unit {
        val delays = mutableListOf<Long>()
        private val queue = ArrayDeque<Runnable>()

        override fun invoke(delayMs: Long, task: Runnable) {
            delays += delayMs
            queue.add(task)
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    /** Runs a task only when the test says so. */
    private class SteppedExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queue.add(command)
        }

        fun isEmpty() = queue.isEmpty()

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }
}
