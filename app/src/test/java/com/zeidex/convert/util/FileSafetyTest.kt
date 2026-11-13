package com.zeidex.convert.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * These rules are the only thing standing between an archive entry such as `../../data/x` and
 * the rest of the device, so they are tested as a contract, not as an implementation detail.
 */
class FileSafetyTest {

    @Test
    fun rejectAnythingThatCouldEscapeTheDestination() {
        for (bad in listOf(
            "../evil.zip",
            "a/../../b",
            "/etc/passwd",
            "\\\\server\\share\\x",
            "C:\\Windows\\x.dll",
            ".",
            "..",
            "   ",
            "nul\u0000byte",
        )) {
            assertNull("accepted `$bad`", FileSafety.safeSegments(bad))
        }
    }

    @Test
    fun keepNormalRelativeEntries() {
        assertEquals(listOf("a", "b.txt"), FileSafety.safeSegments("a/b.txt"))
        assertEquals(listOf("photo.jpg"), FileSafety.safeSegments("./photo.jpg"))
        assertEquals(listOf("x.txt"), FileSafety.safeSegments("x.txt"))
    }

    @Test
    fun sanitizeNamesForEveryPlatform() {
        assertEquals("a_b.txt", FileSafety.sanitizeFileName("a/b.txt"))
        assertEquals("_CON", FileSafety.sanitizeFileName("CON"))
        assertEquals("_con.txt", FileSafety.sanitizeFileName("con.txt"))
        assertEquals("trailing", FileSafety.sanitizeFileName("trailing..."))
        assertNull(FileSafety.sanitizeFileName(""))
        assertNull(FileSafety.sanitizeFileName(".."))
        val long = "x".repeat(300) + ".jpg"
        val safe = FileSafety.sanitizeFileName(long)!!
        assertTrue(safe.length <= 200 && safe.endsWith(".jpg"))
    }

    @Test
    fun containmentIsSeparatorAware() {
        val dir = File(System.getProperty("java.io.tmpdir"), "zeidex-safety-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        try {
            assertTrue(FileSafety.isInside(File(dir, "out.txt"), dir))
            assertFalse("sibling with the same prefix escaped", FileSafety.isInside(File(dir.path + "-other/x"), dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun collisionsBecomeTheNextFreeNumber() {
        val dir = File(System.getProperty("java.io.tmpdir"), "zeidex-unique-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        try {
            File(dir, "photo.jpg").writeText("1")
            assertEquals("photo 2.jpg", FileSafety.uniqueFile(dir, "photo.jpg").name)
            File(dir, "photo 2.jpg").writeText("2")
            File(dir, "photo 3.jpg").writeText("3")
            assertEquals("photo 4.jpg", FileSafety.uniqueFile(dir, "photo.jpg").name)
            // A dotless name has no extension to preserve.
            File(dir, "README").writeText("x")
            assertEquals("README 2", FileSafety.uniqueFile(dir, "README").name)
            // Directories never collide with files of the same name.
            File(dir, "shots").mkdir()
            assertEquals("shots 2", FileSafety.uniqueDir(dir, "shots").name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
