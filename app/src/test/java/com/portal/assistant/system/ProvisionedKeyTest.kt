package com.portal.assistant.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The import-once step a key staged by `setup.sh` goes through, for the Gemini API key and the Search key alike. */
class ProvisionedKeyTest {

    @get:Rule val dir = TemporaryFolder()

    private val stored = mutableListOf<String>()
    private val storeOk: (String) -> Boolean = {
        stored += it
        true
    }

    private fun staged(name: String, text: String): File = File(dir.root, name).apply { writeText(text) }

    @Test fun aStagedKeyIsStoredTrimmedAndItsFileRemoved() {
        val file = staged("search_key.txt", "  tvly-abc123\n")

        ProvisionedKey.importOnce(file, storeOk)

        assertEquals(listOf("tvly-abc123"), stored)
        assertFalse(file.exists())
    }

    @Test fun noStagedFileStoresNothing() {
        ProvisionedKey.importOnce(File(dir.root, "search_key.txt"), storeOk)

        assertTrue(stored.isEmpty())
    }

    @Test fun aBlankStagedFileIsDroppedWithoutTouchingTheStoredKey() {
        val file = staged("api_key.txt", " \n")

        ProvisionedKey.importOnce(file, storeOk)

        assertTrue(stored.isEmpty())
        assertFalse(file.exists())
    }

    @Test fun aFailedStoreKeepsTheFileForTheNextLaunch() {
        val file = staged("api_key.txt", "AIzaKey")

        ProvisionedKey.importOnce(file) { false }

        assertTrue(file.exists())
    }

    @Test fun theSearchKeyHasItsOwnStagedFile() {
        assertEquals("api_key.txt", ProvisionedKey.GEMINI_FILE)
        assertEquals("search_key.txt", ProvisionedKey.SEARCH_FILE)
    }
}
