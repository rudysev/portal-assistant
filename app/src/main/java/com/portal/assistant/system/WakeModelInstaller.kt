package com.portal.assistant.system

import com.portal.assistant.util.Http
import com.portal.commons.DebugLog
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Fetches the Vosk wake model at **runtime** so it isn't shipped in the APK.
 *
 * openWakeWord is the primary gen2 foreground detector (bundled ONNX). This model is only for the **parallel
 * Vosk shadow** used in on-device benchmarking. Bundling it would be dead weight on gen1, where portal-wake
 * owns wake and ships its own copy. The assistant downloads it on first gen2 use, unpacks it into `filesDir`,
 * and passes the directory to `WakeDetectors.vosk(modelDir = …)`. Idempotent: once [isInstalled] it never
 * re-fetches.
 *
 * [install] is blocking (network fetch + unzip) — call it off the main thread; it reports 0f‑1f progress for
 * the setup UI. Failure (offline, truncated, bad zip) leaves no partial model and returns false. The device
 * already needs network for Gemini, so a one-time model fetch is no new dependency.
 */
class WakeModelInstaller(private val filesDir: File) {

    /** The unpacked model directory Vosk loads from (contains `am/ conf/ graph/ ivector/` once installed). */
    fun modelDir(): File = File(filesDir, MODEL_DIR)

    /**
     * True only when a **complete** model is on disk. A real Vosk model has all of [MODEL_SUBDIRS], so require
     * every one: a truncated download that landed only the earlier dirs (unzip order is fs-dependent, and `am/`
     * sorts first) must read as NOT installed and re-download, rather than committing a partial model that
     * `Model()` would then fail to load. (Belt-and-suspenders: [install] also verifies bytes vs Content-Length
     * via OkHttp, but that only bites when the server sends a length.)
     */
    fun isInstalled(): Boolean = MODEL_SUBDIRS.all { File(modelDir(), it).isDirectory }

    /** Remove the on-disk model so the next [install] re-fetches it — used when a present model fails to load. */
    fun delete() {
        modelDir().deleteRecursively()
    }

    /**
     * Download + unpack the model. No-op returning true if already [isInstalled]. Blocking; run off-thread.
     * [onProgress] receives 0f–1f (download is the bulk; unzip finishes it). Returns false on any failure,
     * having cleaned up partial state so a retry starts fresh.
     */
    fun install(onProgress: (Float) -> Unit): Boolean {
        if (isInstalled()) {
            onProgress(1f)
            return true
        }
        val dir = modelDir()
        val zip = File(filesDir, "$MODEL_DIR.zip.part")
        val staging = File(filesDir, "$MODEL_DIR.staging")
        return try {
            download(zip, onProgress)
            unpack(zip, staging)
            // Atomic-ish swap: only expose the model dir once the unzip fully succeeded (so a crash mid-unzip
            // can't leave a half-model that isInstalled() would accept). staging → dir is a rename on the same fs.
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) throw IllegalStateException("could not move staged model into place")
            check(isInstalled()) { "unpacked model incomplete — truncated/bad archive" }
            onProgress(1f)
            DebugLog.log("wake model installed (${dir.absolutePath})")
            true
        } catch (t: Throwable) {
            DebugLog.log("wake model install failed: ${t.message}")
            staging.deleteRecursively()
            dir.deleteRecursively() // never leave a partial that isInstalled() would trust
            false
        } finally {
            zip.delete()
        }
    }

    /** Stream the zip to [dest], reporting download progress into the 0f–[DOWNLOAD_FRACTION] band. */
    private fun download(dest: File, onProgress: (Float) -> Unit) {
        val req = Request.Builder().url(MODEL_URL).build()
        // Derive from the shared client to reuse its pool but relax the read timeout for a long transfer
        // (the default 10 s is per-read; a stalled CDN mid-download would otherwise abort a good fetch).
        val client = Http.shared.newBuilder()
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            val body = resp.body ?: throw IllegalStateException("empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // Emit only when the shown integer percent changes — a large fetch in small chunks
                        // otherwise fires thousands of StateFlow writes (UI recompositions) for ~100 values.
                        if (total > 0) {
                            val pct = (100 * done / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(DOWNLOAD_FRACTION * done / total)
                            }
                        }
                    }
                    // Explicit truncation guard: OkHttp throws on a short read only when the server sent a
                    // Content-Length, so make that check visible and cover the known-length case here. (When the
                    // length is unknown, the all-dirs isInstalled() check + the caller's bounded retry catch it.)
                    if (total > 0 && done != total) throw IllegalStateException("truncated download ($done/$total bytes)")
                }
            }
        }
    }

    /** Unzip [zip] into [into], stripping the archive's top-level `[ZIP_ROOT]/` folder so the model root is flat. */
    internal fun unpack(zip: File, into: File) {
        into.deleteRecursively()
        into.mkdirs()
        val root = into.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                // Strip the well-known top dir; entries outside it (e.g. a stray README) are dropped.
                val rel = entry.name.substringAfter("$ZIP_ROOT/", "")
                if (rel.isEmpty()) { zin.closeEntry(); continue }
                val outFile = File(into, rel)
                // Zip-slip guard: refuse any entry that would escape [into] via "../" traversal.
                if (!outFile.canonicalFile.path.startsWith(root.path + File.separator)) {
                    throw SecurityException("zip entry escapes target: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zin.copyTo(it) }
                }
                zin.closeEntry()
            }
        }
    }

    companion object {
        // Vosk's official host. The lgraph model is the accurate one needed for room-distance wake (the same
        // model portal-wake ships and the assistant used to bundle). HTTPS — no cleartext concern.
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip"

        // Unpacked model lives here under filesDir; WakeDetectors.vosk(modelDir = modelDir()) loads it.
        private const val MODEL_DIR = "wake-model"

        // Every dir a complete Vosk model has — all must be present for [isInstalled] to trust the install
        // (so a truncated unzip that landed only the earlier ones doesn't read as complete).
        private val MODEL_SUBDIRS = listOf("am", "conf", "graph", "ivector")

        // The archive's single top-level folder, stripped so the model root is flat (am/ conf/ …).
        private const val ZIP_ROOT = "vosk-model-en-us-0.22-lgraph"

        // Download is ~99% of the work; reserve the last sliver for the (fast) unzip so progress reaches 1f there.
        private const val DOWNLOAD_FRACTION = 0.97f
    }
}
