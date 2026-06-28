package com.portal.assistant.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 16-bit PCM gain: scaling, clamping, sign handling, odd-byte safety. */
class PcmGainTest {

    /** Little-endian bytes for explicit signed samples. */
    private fun pcm(vararg samples: Int): ByteArray {
        val b = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            b[i * 2] = (samples[i] and 0xff).toByte()
            b[i * 2 + 1] = ((samples[i] shr 8) and 0xff).toByte()
        }
        return b
    }

    /** Decode little-endian 16-bit samples back to ints. */
    private fun samples(b: ByteArray): List<Int> = (0 until b.size / 2).map { (b[it * 2].toInt() and 0xff) or (b[it * 2 + 1].toInt() shl 8) }

    @Test fun unityGainIsPassThrough() {
        val input = pcm(100, -100, 5000, -5000, 0)
        assertArrayEquals(input, PcmGain.amplify(input, gain = 1.0f))
    }

    @Test fun doublesSamples() {
        assertEquals(listOf(200, -200, 2000), samples(PcmGain.amplify(pcm(100, -100, 1000), gain = 2.0f)))
    }

    @Test fun clampsToSigned16BitRange() {
        // 20000 * 2 = 40000 → clamp 32767; -20000 * 2 = -40000 → clamp -32768.
        assertEquals(listOf(32767, -32768), samples(PcmGain.amplify(pcm(20000, -20000), gain = 2.0f)))
    }

    @Test fun attenuatesWhenGainBelowOne() {
        assertEquals(listOf(500, -500), samples(PcmGain.amplify(pcm(1000, -1000), gain = 0.5f)))
    }

    @Test fun honorsLengthAndDropsOddTrailingByte() {
        val buf = pcm(1000, 9999) + byteArrayOf(0x7f) // 5 bytes; measure only the first sample
        val out = PcmGain.amplify(buf, length = 3, gain = 2.0f) // length 3 → 1 whole sample
        assertEquals(listOf(2000), samples(out))
    }

    @Test fun emptyOrSubSampleIsEmpty() {
        assertEquals(0, PcmGain.amplify(ByteArray(0), gain = 2.0f).size)
        assertEquals(0, PcmGain.amplify(ByteArray(1), gain = 2.0f).size)
    }
}
