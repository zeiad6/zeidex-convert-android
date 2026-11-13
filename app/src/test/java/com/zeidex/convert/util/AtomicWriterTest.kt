package com.zeidex.convert.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The writer is what makes a cancelled job harmless, so every test checks the folder
 * afterwards: no staging leftovers, no half-written target, no overwritten original.
 */
class AtomicWriterTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "zeidex-atomic-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun leftovers(): List<String> =
        dir.listFiles().orEmpty().map { it.name }.filter { it.contains(".zeidex-part") }

    @Test
    fun commitLandsOnTheRequestedNameWithTheExactBytes() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val out = AtomicWriter.write(dir, "photo.webp") { it.write(bytes) }

        assertEquals("photo.webp", out.name)
        assertTrue(out.readBytes().contentEquals(bytes))
        assertEquals("staging files must not survive a commit", emptyList<String>(), leftovers())
    }

    @Test
    fun anExistingFileIsNeverOverwritten() {
        val original = File(dir, "photo.jpg").apply { writeText("original") }
        val out = AtomicWriter.write(dir, "photo.jpg") { it.write("new".toByteArray()) }

        assertEquals("photo 2.jpg", out.name)
        assertEquals("original", original.readText())
        assertEquals("new", out.readText())
    }

    @Test
    fun repeatedRunsGetConsecutiveNames() {
        val names = (1..3).map { i ->
            AtomicWriter.write(dir, "clip.mp4") { it.write("run$i".toByteArray()) }.name
        }
        assertEquals(listOf("clip.mp4", "clip 2.mp4", "clip 3.mp4"), names)
        assertEquals("run3", File(dir, "clip 3.mp4").readText())
    }

    @Test
    fun aThrowingBlockLeavesNothingBehind() {
        var failed = false
        try {
            AtomicWriter.write(dir, "broken.zip") { out ->
                out.write(ByteArray(4096))
                throw IOException("encoder died mid-stream")
            }
        } catch (_: IOException) {
            failed = true
        }

        assertTrue("the failure must reach the caller", failed)
        assertFalse("a partial file must not look finished", File(dir, "broken.zip").exists())
        assertEquals("the staging file must be cleaned up", emptyList<String>(), leftovers())
        assertEquals("the folder must be empty", 0, dir.listFiles().orEmpty().size)
    }

    @Test
    fun discardedStagingFilesAreRemoved() {
        val staged = AtomicWriter.stage(dir, "later.txt")
        staged.writeText("bytes")
        assertTrue(staged.exists())
        assertTrue("staging must be hidden", staged.name.startsWith("."))

        AtomicWriter.discard(staged)
        assertFalse(staged.exists())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun hostileNamesAreSanitizedBeforeStaging() {
        val out = AtomicWriter.write(dir, "../../escape.txt") { it.write("x".toByteArray()) }

        assertEquals("escape.txt", out.name)
        assertTrue(FileSafety.isInside(out, dir))
        assertFalse("nothing may be written above the destination", File(dir.parentFile, "escape.txt").exists())
    }

    @Test
    fun missingDestinationDirectoriesAreCreated() {
        val nested = File(dir, "a/b/c")
        val out = AtomicWriter.write(nested, "deep.bin") { it.write("x".toByteArray()) }

        assertTrue(nested.isDirectory)
        assertEquals("deep.bin", out.name)
        assertEquals("x", out.readText())
    }

    @Test
    fun emptyArtifactsStillCommit() {
        val out = AtomicWriter.write(dir, "empty.srt") { }
        assertTrue(out.exists())
        assertEquals(0L, out.length())
        assertEquals(emptyList<String>(), leftovers())
    }
}
