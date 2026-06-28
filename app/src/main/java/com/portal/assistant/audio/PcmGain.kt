package com.portal.assistant.audio

/**
 * Software gain for little-endian 16-bit mono PCM, applied to the assistant's **conversation** audio
 * before it's streamed to the model.
 *
 * Why: a sideloaded app on the Portal only gets the single **handset mic**, not the privileged
 * beamformed far-field array the native wake word uses, so room-distance speech arrives at roughly half
 * the close-range level — quiet enough that the Live server's VAD/transcription won't lock onto it
 * (verified on device: ~2800 RMS at arm's length transcribed; ~1400 at 4 m did not). Multiplying the
 * samples up brings distant speech into the transcribable range. In a quiet room this preserves SNR
 * (voice and faint noise scale together).
 *
 * This is safe here precisely because it's the *conversation* stream — unlike portal-wake's always-on
 * *wake* capture, where any gain/AGC masks the dead-mic silence signal the background-rebuild depends on.
 *
 * Pure (no Android, no I/O) so it is unit-tested.
 */
object PcmGain {

    /**
     * Return the first [length] bytes of [buf] with each 16-bit sample multiplied by [gain] and clamped
     * to the signed 16-bit range. Odd trailing byte is dropped. [gain] <= 1 still works (attenuates);
     * gain 1.0 is a pass-through copy.
     */
    fun amplify(buf: ByteArray, length: Int = buf.size, gain: Float): ByteArray {
        val n = minOf(length, buf.size).let { it - (it % 2) }
        if (n <= 0) return ByteArray(0)
        val out = ByteArray(n)
        var i = 0
        while (i < n) {
            val sample = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8) // signed 16-bit, little-endian
            val scaled = (sample * gain).toInt().coerceIn(MIN_16, MAX_16)
            out[i] = (scaled and 0xff).toByte()
            out[i + 1] = ((scaled shr 8) and 0xff).toByte()
            i += 2
        }
        return out
    }

    private const val MIN_16 = -32_768
    private const val MAX_16 = 32_767
}
