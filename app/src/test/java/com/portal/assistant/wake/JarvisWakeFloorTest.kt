package com.portal.assistant.wake

import com.portal.commons.audio.WakeMatcher
import com.portal.commons.audio.WakeMatcher.RecWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The "hey jarvis" keyword floor this app declares: [VoskDetector.JARVIS_MIN_CONF] for its own detector (gen2), and
 * the same value as manifest meta-data for portal-wake (gen1), so both judge a decode alike.
 */
class JarvisWakeFloorTest {

    private fun rec(vararg w: Pair<String, Double>) = w.map { (word, conf) -> RecWord(word, conf) }

    private fun match(vararg w: Pair<String, Double>) = WakeMatcher.match(rec(*w), listOf(VoskDetector.WAKE_WORD))

    @Test fun theSpeakerphoneFalseWakeIsRejected() {
        // Observed live: French from a FaceTime call on speaker across the room decoded a clean "hey jarvis" with
        // jarvis at 0.90, clearing every other gate. Genuine wakes on the Portal decode jarvis at 0.98 or above.
        assertNull(match("hey" to 0.94, "jarvis" to 0.90))
    }

    @Test fun aWakeAtTheFloorStillFires() {
        assertEquals("jarvis", match("hey" to 1.0, "jarvis" to 0.95))
    }

    @Test fun theManifestDeclaresTheSameFloorToPortalWake() {
        // Unit tests run in the module directory. XML can't reference the constant, so the literal is pinned here.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = checkNotNull(
            Regex("""com\.portal\.wake\.min_confidence"\s+android:value="([0-9.]+)"""").find(manifest),
        ) { "no com.portal.wake.min_confidence meta-data" }.groupValues[1]
        assertEquals(VoskDetector.JARVIS_MIN_CONF, declared.toDouble(), 0.0)
    }
}
