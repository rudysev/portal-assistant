package com.portal.assistant.conversation

import com.portal.commons.PcmCaptureFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for the Mic gate: whole sequences of mic frames, interleaved with listening started/stopped, driven
 * through its one interface. Frames are synthetic PCM, every sample at one known amplitude, so a sent chunk's first
 * sample says which frame it was (after the 2× mic gain). Times are capture end, on one monotonic clock, one frame
 * ([PcmCaptureFormat.FRAME_MS]) apart.
 */
class MicGateTest {

    /** One frame with every 16-bit sample at [amplitude]. */
    private fun frame(amplitude: Int): ByteArray = ByteArray(PcmCaptureFormat.FRAME_BYTES).also { bytes ->
        for (i in bytes.indices step 2) {
            bytes[i] = (amplitude and 0xff).toByte()
            bytes[i + 1] = ((amplitude shr 8) and 0xff).toByte()
        }
    }

    /** The amplitude of a sent chunk's first sample. */
    private fun amplitudeOf(chunk: ByteArray): Int = (chunk[0].toInt() and 0xff) or (chunk[1].toInt() shl 8)

    /** Feeds frames to the gate one [PcmCaptureFormat.FRAME_MS] apart, from [capturedUntilMs]. */
    private class FrameFeed(val gate: MicGate, var capturedUntilMs: Long = 10_000L) {
        fun capture(bytes: ByteArray): MicGate.FrameOutcome = gate.onFrame(bytes, bytes.size, capturedUntilMs).also {
            capturedUntilMs +=
                PcmCaptureFormat.FRAME_MS
        }
    }

    @Test fun aVoiceQueryBuffersWhileConnectingThenFlushesInCaptureOrderBeforeTheLiveFrame() {
        val mic = FrameFeed(MicGate(voiceQuery = true, playsListeningTone = false))

        assertEquals(emptyList<Int>(), mic.capture(frame(100)).audioToSend.map(::amplitudeOf))
        assertEquals(emptyList<Int>(), mic.capture(frame(200)).audioToSend.map(::amplitudeOf))
        mic.gate.listeningStarted()

        assertEquals(listOf(200, 400, 600), mic.capture(frame(300)).audioToSend.map(::amplitudeOf))
        assertEquals(listOf(800), mic.capture(frame(400)).audioToSend.map(::amplitudeOf))
    }

    @Test fun aChipBuffersNothingAndSendsLiveFramesOnceListening() {
        val mic = FrameFeed(MicGate(voiceQuery = false, playsListeningTone = false))

        assertEquals(emptyList<Int>(), mic.capture(frame(100)).audioToSend.map(::amplitudeOf))
        mic.gate.listeningStarted()

        assertEquals(listOf(400), mic.capture(frame(200)).audioToSend.map(::amplitudeOf))
    }

    @Test fun whenAModelTurnWinsTheRaceNothingBufferedIsEverSent() {
        // The model starts its answer before the first listening frame arrives: listening starts and stops with no
        // frame in between, so the connect buffer must never reach the backend, not even on the next listening window.
        val mic = FrameFeed(MicGate(voiceQuery = true, playsListeningTone = false))
        mic.capture(frame(100))
        mic.gate.listeningStarted()
        mic.gate.listeningStopped()

        assertEquals(emptyList<Int>(), mic.capture(frame(200)).audioToSend.map(::amplitudeOf)) // Jarvis speaking
        mic.gate.listeningStarted()
        assertEquals(listOf(600), mic.capture(frame(300)).audioToSend.map(::amplitudeOf))
    }

    @Test fun framesWhileJarvisSpeaksAreMuted() {
        val mic = FrameFeed(MicGate(voiceQuery = true, playsListeningTone = false))
        mic.gate.listeningStarted()
        mic.capture(frame(100))
        mic.gate.listeningStopped()

        val outcome = mic.capture(frame(2_000))

        assertEquals(emptyList<Int>(), outcome.audioToSend.map(::amplitudeOf))
        assertEquals(MicGate.Indicator.None, outcome.indicator)
        assertEquals(false, outcome.speechHeard)
    }

    @Test fun theConnectBufferCapDropsTheOldestFrame() {
        // A hung connect keeps the most recent 50 frames (~5 s), not the stale start.
        val mic = FrameFeed(MicGate(voiceQuery = true, playsListeningTone = false))
        for (amplitude in 1..51) mic.capture(frame(amplitude))
        mic.gate.listeningStarted()

        val sent = mic.capture(frame(1_000)).audioToSend.map(::amplitudeOf)

        assertEquals((2..51).map { it * 2 } + 2_000, sent)
    }

    @Test fun theMicGainIsAppliedToTheSentAudio() {
        val mic = FrameFeed(MicGate(voiceQuery = false, playsListeningTone = false))
        mic.gate.listeningStarted()

        assertEquals(listOf(2_468), mic.capture(frame(1_234)).audioToSend.map(::amplitudeOf))
    }

    @Test fun whileListeningTheIndicatorCarriesTheRawLevel() {
        // A constant amplitude of 3000 is half of PcmLevel's 6000 full scale, before the gain.
        val mic = FrameFeed(MicGate(voiceQuery = false, playsListeningTone = false))
        assertEquals(MicGate.Indicator.None, mic.capture(frame(3_000)).indicator) // connecting
        mic.gate.listeningStarted()

        assertEquals(MicGate.Indicator.Level(0.5f), mic.capture(frame(3_000)).indicator)
    }

    @Test fun speechHeardIsFalseForSilenceAndTrueForLoudSpeech() {
        val mic = FrameFeed(MicGate(voiceQuery = false, playsListeningTone = false))
        mic.gate.listeningStarted()

        assertEquals(false, mic.capture(frame(0)).speechHeard)
        assertEquals(false, mic.capture(frame(200)).speechHeard)
        assertEquals(true, mic.capture(frame(2_000)).speechHeard)
    }

    @Test fun speechHeardIsMeasuredOnTheGainedFrame() {
        // Distant speech: below the voice-activity level as captured, above it once gained (what the server hears).
        val mic = FrameFeed(MicGate(voiceQuery = false, playsListeningTone = false))
        mic.gate.listeningStarted()

        assertEquals(true, mic.capture(frame(400)).speechHeard)
    }

    @Test fun speechWhileConnectingIsNotHeardUntilListening() {
        val mic = FrameFeed(MicGate(voiceQuery = true, playsListeningTone = false))

        assertEquals(false, mic.capture(frame(2_000)).speechHeard)
    }

    @Test fun theFlushLogLineIsWrittenOnce() {
        val mic = FrameFeed(MicGate(voiceQuery = true, playsListeningTone = false))
        mic.capture(frame(100))
        mic.capture(frame(200))
        assertEquals(emptyList<String>(), mic.capture(frame(300)).logLines)
        mic.gate.listeningStarted()

        assertEquals(
            listOf("connect buffer: flushing 3 frame(s) (~300ms) captured while connecting"),
            mic.capture(frame(400)).logLines,
        )
        assertEquals(emptyList<String>(), mic.capture(frame(500)).logLines)
        mic.gate.listeningStopped()
        mic.gate.listeningStarted()
        assertEquals(emptyList<String>(), mic.capture(frame(600)).logLines)
    }

    @Test fun anEmptyConnectBufferWritesNoFlushLine() {
        val mic = FrameFeed(MicGate(voiceQuery = true, playsListeningTone = false))
        mic.gate.listeningStarted()

        assertEquals(emptyList<String>(), mic.capture(frame(100)).logLines)
    }

    // --- The Listening tone ---

    /** A voice conversation whose Listening tone is still pending, already listening (the socket connected first). */
    private fun listeningWithTonePending(): FrameFeed =
        FrameFeed(MicGate(voiceQuery = true, playsListeningTone = true)).also {
            it.gate.listeningStarted()
        }

    private val windowMs = ListeningTone.suppressionWindowMs

    @Test fun whileTheToneIsPendingEverythingIsDroppedWithTheBarAtRest() {
        // The user hasn't heard the cue yet, so nothing may be sent, but the bar still shows on time.
        val mic = listeningWithTonePending()

        val outcome = mic.capture(frame(2_000))

        assertEquals(emptyList<Int>(), outcome.audioToSend.map(::amplitudeOf))
        assertEquals(MicGate.Indicator.BarAtRest, outcome.indicator)
        assertEquals(false, outcome.speechHeard)
    }

    @Test fun whileConnectingAToneDroppedFrameLeavesTheIndicatorsAlone() {
        val mic = FrameFeed(MicGate(voiceQuery = true, playsListeningTone = true))

        assertEquals(MicGate.Indicator.None, mic.capture(frame(2_000)).indicator)
    }

    @Test fun aFrameCapturedAtToneStartIsDropped() {
        val mic = listeningWithTonePending()
        mic.gate.toneStarted(atMs = 20_000L)
        mic.capturedUntilMs = 20_000L + PcmCaptureFormat.FRAME_MS // capture began at the tone's start

        assertEquals(emptyList<Int>(), mic.capture(frame(100)).audioToSend.map(::amplitudeOf))
    }

    @Test fun aFrameStraddlingTheWindowEndIsDroppedAndTheNextIsSent() {
        // The straddling frame's first samples can still hold the tone's echo, so the whole frame goes; the user's
        // words right after the window must reach the voice backend.
        val mic = listeningWithTonePending()
        mic.gate.toneStarted(atMs = 20_000L)
        mic.capturedUntilMs = 20_000L + windowMs - 1 + PcmCaptureFormat.FRAME_MS // began 1 ms before the window ends

        assertEquals(emptyList<Int>(), mic.capture(frame(100)).audioToSend.map(::amplitudeOf))
        mic.capturedUntilMs = 20_000L + windowMs + PcmCaptureFormat.FRAME_MS // began as the window ends
        assertEquals(listOf(400), mic.capture(frame(200)).audioToSend.map(::amplitudeOf))
    }

    @Test fun aFrameCapturedBeforeTheToneIsDroppedEvenWhenDeliveredAfterItStarted() {
        // Nothing captured before the cue may be sent, even a frame holding none of the tone itself.
        val mic = listeningWithTonePending()
        mic.gate.toneStarted(atMs = 20_000L)
        mic.capturedUntilMs = 19_950L

        assertEquals(emptyList<Int>(), mic.capture(frame(100)).audioToSend.map(::amplitudeOf))
    }

    @Test fun aToneThatFailedToPlaySendsFramesAgain() {
        val mic = listeningWithTonePending()
        mic.capture(frame(100))
        mic.gate.toneFailed()

        assertEquals(listOf(400), mic.capture(frame(200)).audioToSend.map(::amplitudeOf))
    }

    @Test fun theToneAndItsWindowNeverCountAsSpeechHeard() {
        // The tone would read as the user speaking: no level, no local-speech signal, inside the window.
        val mic = listeningWithTonePending()
        mic.gate.toneStarted(atMs = mic.capturedUntilMs)

        val outcome = mic.capture(frame(10_000))

        assertEquals(false, outcome.speechHeard)
        assertEquals(MicGate.Indicator.BarAtRest, outcome.indicator)
    }

    @Test fun theToneDropReportIsWrittenOnceAtTheFirstKeptFrame() {
        val mic = listeningWithTonePending() // frames end at 10_000, 10_100, …
        val logLines = mutableListOf<String>()
        repeat(2) { logLines += mic.capture(frame(100)).logLines } // pending
        mic.gate.toneStarted(atMs = 10_150L)
        repeat(7) { logLines += mic.capture(frame(100)).logLines } // 10_200..10_800, inside the 640 ms window
        assertEquals(emptyList<String>(), logLines)

        assertEquals(
            listOf(
                "listening tone: dropped 2 frame(s) while pending, then 7 frame(s) (700ms) " +
                    "captured -50..650ms from the tone's start in its window (640ms)",
            ),
            mic.capture(frame(100)).logLines, // 10_900: began at 10_800, after the window
        )
        assertEquals(emptyList<String>(), mic.capture(frame(100)).logLines)
    }

    @Test fun aFailedToneReportsItsPendingDropsOnce() {
        val mic = listeningWithTonePending()
        mic.capture(frame(100))
        mic.gate.toneFailed()

        assertEquals(
            listOf(
                "listening tone: dropped 1 frame(s) while pending, then the tone didn't play, so frames are sent again",
            ),
            mic.capture(frame(100)).logLines,
        )
        assertEquals(emptyList<String>(), mic.capture(frame(100)).logLines)
    }

    @Test fun onlyFramesCapturedAfterTheToneWindowButBeforeListeningAreFlushed() {
        // The tone's 640 ms window is longer than the ~440 ms connect, so with the tone on the connect buffer holds
        // little or nothing: frames inside the window are never buffered, only the ones after it.
        val mic = FrameFeed(MicGate(voiceQuery = true, playsListeningTone = true)) // frames end at 10_000, 10_100, …
        mic.capture(frame(1)) // pending
        mic.gate.toneStarted(atMs = 10_050L) // window ends at 10_690
        repeat(6) { mic.capture(frame(2)) } // 10_100..10_600: began before 10_690, so dropped
        mic.capture(frame(3)) // 10_700: began at 10_600, straddles the end, dropped
        mic.capture(frame(4)) // 10_800: began at 10_700, buffered
        mic.capture(frame(5)) // 10_900: buffered
        mic.gate.listeningStarted()

        assertEquals(listOf(8, 10, 12), mic.capture(frame(6)).audioToSend.map(::amplitudeOf))
    }
}
