package com.portal.assistant.wake

import java.io.File

/**
 * The Foreground wake model on disk — the port [ForegroundWake] downloads and deletes it through. Production:
 * [WakeModelInstaller]; tests: an in-memory fake.
 */
interface WakeModel {
    /** Where the installed model lives — what a [Detector] loads. */
    val dir: File

    /** True only when a complete model is on disk. */
    fun isInstalled(): Boolean

    /**
     * Download + unpack the model. Blocking (run off the main thread); [onProgress] gets 0f–1f. False on any failure.
     */
    fun install(onProgress: (Float) -> Unit): Boolean

    /** Remove the model so the next [install] fetches a fresh copy. */
    fun delete()
}
