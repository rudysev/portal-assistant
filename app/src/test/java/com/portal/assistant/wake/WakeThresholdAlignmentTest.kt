package com.portal.assistant.wake

import com.portal.commons.audio.OpenWakeWordDetector
import com.portal.commons.audio.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gen1 (portal-wake discovers this app's wake meta-data) and gen2 (foreground [WakeMicEngine])
 * must use the same openWakeWord score threshold.
 */
class WakeThresholdAlignmentTest {

    @Test fun pluginMetaMatchesOwwDefault() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val expected = OpenWakeWordDetector.DEFAULT_SCORE_THRESHOLD.toString()
        assertTrue(
            "gen1 plugin meta must match OpenWakeWordDetector.DEFAULT_SCORE_THRESHOLD ($expected)",
            manifest.contains("""com.portal.wake.min_confidence" android:value="$expected""""),
        )
    }

    @Test fun owwDefaultMatchesWakeWordDefault() {
        assertEquals(
            WakeWord.DEFAULT_SCORE_THRESHOLD,
            OpenWakeWordDetector.DEFAULT_SCORE_THRESHOLD.toDouble(),
            1e-9,
        )
    }
}
