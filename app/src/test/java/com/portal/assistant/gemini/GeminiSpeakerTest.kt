package com.portal.assistant.gemini

import com.portal.assistant.gemini.GeminiSpeaker.Gender.FEMALE
import com.portal.assistant.gemini.GeminiSpeaker.Gender.MALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for the Speaker catalog. */
class GeminiSpeakerTest {

    /** The 30 prebuilt voices as Google labels them (docs.cloud.google.com/text-to-speech/docs/gemini-tts). */
    private val documented = mapOf(
        "Achernar" to FEMALE, "Achird" to MALE, "Algenib" to MALE, "Algieba" to MALE, "Alnilam" to MALE,
        "Aoede" to FEMALE, "Autonoe" to FEMALE, "Callirrhoe" to FEMALE, "Charon" to MALE, "Despina" to FEMALE,
        "Enceladus" to MALE, "Erinome" to FEMALE, "Fenrir" to MALE, "Gacrux" to FEMALE, "Iapetus" to MALE,
        "Kore" to FEMALE, "Laomedeia" to FEMALE, "Leda" to FEMALE, "Orus" to MALE, "Pulcherrima" to FEMALE,
        "Puck" to MALE, "Rasalgethi" to MALE, "Sadachbia" to MALE, "Sadaltager" to MALE, "Schedar" to MALE,
        "Sulafat" to FEMALE, "Umbriel" to MALE, "Vindemiatrix" to FEMALE, "Zephyr" to FEMALE, "Zubenelgenubi" to MALE,
    )

    @Test fun catalogHoldsTheThirtyDocumentedSpeakersWithTheirGenders() {
        assertEquals(documented, GeminiSpeaker.AVAILABLE.associate { it.name to it.gender })
        assertEquals(30, GeminiSpeaker.AVAILABLE.size)
    }

    @Test fun catalogIsAlphabeticalByName() {
        val names = GeminiSpeaker.AVAILABLE.map { it.name }
        assertEquals("Achernar", names.first())
        assertEquals("Zubenelgenubi", names.last())
        assertEquals(names.sortedBy { it.lowercase() }, names)
    }

    @Test fun labelIsNameDotGender() {
        assertEquals("Charon · Male", GeminiSpeaker.find("Charon")!!.label)
        assertEquals("Kore · Female", GeminiSpeaker.find("Kore")!!.label)
    }

    @Test fun findReturnsTheCatalogSpeaker() {
        assertEquals(GeminiSpeaker.Speaker("Puck", MALE), GeminiSpeaker.find("Puck"))
    }

    @Test fun findRejectsNamesOutsideTheCatalog() {
        // Exact match only — prefs store catalog names verbatim, so anything else is an orphan.
        assertNull(GeminiSpeaker.find("Bogus"))
        assertNull(GeminiSpeaker.find("charon"))
        assertNull(GeminiSpeaker.find(""))
        assertNull(GeminiSpeaker.find(null))
    }
}
