package com.portal.assistant.system

import java.io.File

/**
 * Import-once for a key `setup.sh` stages in the app's external files dir (adb can write there without root):
 * the Gemini API key in [GEMINI_FILE], the Search key in [SEARCH_FILE]. On app start the key moves into prefs
 * and its file is removed, so it never lingers in plaintext on disk. The value is never logged. Pure over the
 * file and the store, so it's JVM-tested; [AppPrefs.importProvisionedKeys] wires it to prefs.
 */
internal object ProvisionedKey {

    /** The staged Gemini API key (mirrored as `KEY_PROVISION_FILE` in `provisioning/keyprov.sh`). */
    const val GEMINI_FILE = "api_key.txt"

    /** The staged Search key (mirrored as `SEARCH_PROVISION_FILE` in `setup.sh`). */
    const val SEARCH_FILE = "search_key.txt"

    /**
     * Move the key staged in [file] into [store] and delete the file. [store] returns whether the key is durably
     * written; only then is the file deleted, so a crash or kill in between can't leave the key in neither place.
     * A read failure keeps the file for the next launch to retry. A blank file is dropped without calling
     * [store], so an empty staging never clears a key the user already has. No file: nothing happens.
     */
    fun importOnce(file: File, store: (String) -> Boolean) {
        val provisioned = (if (file.exists()) runCatching { file.readText().trim() }.getOrNull() else null) ?: return
        if (provisioned.isEmpty()) {
            file.delete() // nothing to import — drop the empty file
            return
        }
        if (store(provisioned)) file.delete()
    }
}
