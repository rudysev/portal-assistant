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

    /** Feeds frames at successive capture times. */
    private class Mic(val gate: MicGate, var capturedUntilMs: Long = 10_000L) {
        fun capture(bytes: ByteArray): MicGate.FrameOutcome =
            gate.onFrame(bytes, bytes.size, capturedUntilMs).also { capturedUntilMs += PcmCaptureFormat.FRAME_MS }
    }

    @Test fun aVoiceQueryBuffersWhileConnectingThenFlushesInCaptureOrderBeforeTheLiveFrame() {
        val mic = Mic(MicGate(voiceQuery = true))

        assertEquals(emptyList<Int>(), mic.capture(frame(100)).audioToSend.map(::amplitudeOf))
        assertEquals(emptyList<Int>(), mic.capture(frame(200)).audioToSend.map(::amplitudeOf))
        mic.gate.listeningStarted()

        assertEquals(listOf(200, 400, 600), mic.capture(frame(300)).audioToSend.map(::amplitudeOf))
        assertEquals(listOf(800), mic.capture(frame(400)).audioToSend.map(::amplitudeOf))
    }

    @Test fun aChipBuffersNothingAndSendsLiveFramesOnceListening() {
        val mic = Mic(MicGate(voiceQuery = false))

        assertEquals(emptyList<Int>(), mic.capture(frame(100)).audioToSend.map(::amplitudeOf))
        mic.gate.listeningStarted()

        assertEquals(listOf(400), mic.capture(frame(200)).audioToSend.map(::amplitudeOf))
    }

    @Test fun whenAModelTurnWinsTheRaceNothingBufferedIsEverSent() {
        // The model starts its answer before the first listening frame arrives: listening starts and stops with no
        // frame in between, so the connect buffer must never reach the backend, not even on the next listening window.
        val mic = Mic(MicGate(voiceQuery = true))
        mic.capture(frame(100))
        mic.gate.listeningStarted()
        mic.gate.listeningStopped()

        assertEquals(emptyList<Int>(), mic.capture(frame(200)).audioToSend.map(::amplitudeOf)) // Jarvis speaking
        mic.gate.listeningStarted()
        assertEquals(listOf(600), mic.capture(frame(300)).audioToSend.map(::amplitudeOf))
    }

    @Test fun framesWhileJarvisSpeaksAreMuted() {
        val mic = Mic(MicGate(voiceQuery = true))
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
        val mic = Mic(MicGate(voiceQuery = true))
        for (amplitude in 1..51) mic.capture(frame(amplitude))
        mic.gate.listeningStarted()

        val sent = mic.capture(frame(1_000)).audioToSend.map(::amplitudeOf)

        assertEquals((2..51).map { it * 2 } + 2_000, sent)
    }

    @Test fun theMicGainIsAppliedToTheSentAudio() {
        val mic = Mic(MicGate(voiceQuery = false))
        mic.gate.listeningStarted()

        assertEquals(listOf(2_468), mic.capture(frame(1_234)).audioToSend.map(::amplitudeOf))
    }

    @Test fun whileListeningTheIndicatorCarriesTheRawLevel() {
        // A constant amplitude of 3000 is half of PcmLevel's 6000 full scale, before the gain.
        val mic = Mic(MicGate(voiceQuery = false))
        assertEquals(MicGate.Indicator.None, mic.capture(frame(3_000)).indicator) // connecting
        mic.gate.listeningStarted()

        assertEquals(MicGate.Indicator.Level(0.5f), mic.capture(frame(3_000)).indicator)
    }

    @Test fun speechHeardIsFalseForSilenceAndTrueForLoudSpeech() {
        val mic = Mic(MicGate(voiceQuery = false))
        mic.gate.listeningStarted()

        assertEquals(false, mic.capture(frame(0)).speechHeard)
        assertEquals(false, mic.capture(frame(200)).speechHeard)
        assertEquals(true, mic.capture(frame(2_000)).speechHeard)
    }

    @Test fun speechHeardIsMeasuredOnTheGainedFrame() {
        // Distant speech: below the voice-activity level as captured, above it once gained (what the server hears).
        val mic = Mic(MicGate(voiceQuery = false))
        mic.gate.listeningStarted()

        assertEquals(true, mic.capture(frame(400)).speechHeard)
    }

    @Test fun speechWhileConnectingIsNotHeardUntilListening() {
        val mic = Mic(MicGate(voiceQuery = true))

        assertEquals(false, mic.capture(frame(2_000)).speechHeard)
    }

    @Test fun theFlushLogLineIsWrittenOnce() {
        val mic = Mic(MicGate(voiceQuery = true))
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
        val mic = Mic(MicGate(voiceQuery = true))
        mic.gate.listeningStarted()

        assertEquals(emptyList<String>(), mic.capture(frame(100)).logLines)
    }
}
