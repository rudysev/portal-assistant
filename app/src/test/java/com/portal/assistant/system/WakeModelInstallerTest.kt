package com.portal.assistant.system

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JVM tests for the file-side logic of [WakeModelInstaller] — the risky parts (completeness gate, unzip
 * root-strip, zip-slip guard, delete). The network download itself isn't exercised here.
 */
class WakeModelInstallerTest {

    private lateinit var filesDir: File
    private lateinit var installer: WakeModelInstaller

    @Before fun setup() {
        filesDir = File.createTempFile("wmi", "").apply { delete(); mkdirs() }
        installer = WakeModelInstaller(filesDir)
    }

    @After fun teardown() {
        filesDir.deleteRecursively()
    }

    private fun mkSubdirs(vararg names: String) {
        names.forEach { File(installer.modelDir(), it).mkdirs() }
    }

    // ---- isInstalled: requires ALL four model subdirs (a truncated unzip must not read as complete) ------

    @Test fun isInstalled_falseWhenEmpty() {
        assertFalse(installer.isInstalled())
    }

    @Test fun isInstalled_falseWithOnlyAm() {
        mkSubdirs("am")
        assertFalse("am/ alone must not count as installed", installer.isInstalled())
    }

    @Test fun isInstalled_falseMissingOne() {
        mkSubdirs("am", "conf", "graph") // no ivector
        assertFalse(installer.isInstalled())
    }

    @Test fun isInstalled_trueWithAllFour() {
        mkSubdirs("am", "conf", "graph", "ivector")
        assertTrue(installer.isInstalled())
    }

    @Test fun delete_removesModel() {
        mkSubdirs("am", "conf", "graph", "ivector")
        assertTrue(installer.isInstalled())
        installer.delete()
        assertFalse(installer.modelDir().exists())
        assertFalse(installer.isInstalled())
    }

    // ---- unpack: strips the archive's top dir, and refuses zip-slip traversal ---------------------------

    @Test fun unpack_stripsTopDirAndWritesFlat() {
        val zip = makeZip(
            dirs = listOf("$ZIP_ROOT/", "$ZIP_ROOT/am/"),
            files = mapOf("$ZIP_ROOT/am/final.mdl" to "model-bytes", "$ZIP_ROOT/conf/mfcc.conf" to "cfg"),
        )
        val into = File(filesDir, "out")
        installer.unpack(zip, into)

        assertFalse("top dir must be stripped", File(into, ZIP_ROOT).exists())
        assertEquals("model-bytes", File(into, "am/final.mdl").readText())
        assertEquals("cfg", File(into, "conf/mfcc.conf").readText())
    }

    @Test(expected = SecurityException::class) fun unpack_rejectsZipSlip() {
        val zip = makeZip(files = mapOf("$ZIP_ROOT/../evil.txt" to "pwned"))
        installer.unpack(zip, File(filesDir, "out"))
    }

    /** Build a zip under [filesDir] with the given directory entries and file entries (name → text content). */
    private fun makeZip(dirs: List<String> = emptyList(), files: Map<String, String> = emptyMap()): File {
        val zip = File(filesDir, "model.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            dirs.forEach { zos.putNextEntry(ZipEntry(it)); zos.closeEntry() }
            files.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    private companion object {
        const val ZIP_ROOT = "vosk-model-en-us-0.22-lgraph"
    }
}
