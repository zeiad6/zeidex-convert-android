package com.zeidex.convert.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes every artifact to a hidden staging file in the destination folder, then renames it
 * into place. A crash, a cancelled coroutine or a full disk leaves the user's folder clean
 * instead of a truncated file that looks finished.
 *
 * Staging in the destination folder is required: `rename(2)` is atomic only inside one
 * filesystem, and the public Downloads folder is frequently a different mount.
 */
object AtomicWriter {

    private const val PART_SUFFIX = ".zeidex-part"
    private val counter = AtomicLong()

    /** A hidden sibling of the final file, on the same filesystem. Caller owns the bytes. */
    fun stage(dir: File, desiredName: String): File {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Cannot create ${dir.absolutePath}")
        }
        val safe = FileSafety.sanitizeFileName(leafName(desiredName)) ?: "output"
        val staged = File(dir, ".$safe.${System.nanoTime()}-${counter.incrementAndGet()}$PART_SUFFIX")
        if (staged.exists() && !staged.delete()) {
            throw IOException("Cannot replace ${staged.name}")
        }
        return staged
    }

    /** Stages, runs [block], flushes it to the device and commits. */
    fun write(dir: File, desiredName: String, block: (OutputStream) -> Unit): File {
        val staged = stage(dir, desiredName)
        try {
            BufferedOutputStream(FileOutputStream(staged)).use { out ->
                block(out)
                out.flush()
            }
            return commit(staged, dir, desiredName)
        } catch (t: Throwable) {
            discard(staged)
            throw t
        }
    }

    /**
     * Moves [staged] to the first free name based on [desiredName] and returns the final file.
     * Never overwrites: concurrent workers and repeated runs both get their own name.
     */
    fun commit(staged: File, dir: File, desiredName: String): File {
        if (!staged.exists()) throw IOException("Missing staged file ${staged.name}")
        sync(staged)
        val target = FileSafety.uniqueFile(dir, leafName(desiredName))
        if (staged.renameTo(target)) return target
        // Different mount or a filesystem that refuses rename: copy, fsync, then rename the copy.
        val localPart = File(dir, ".${target.name}.${System.nanoTime()}$PART_SUFFIX")
        try {
            staged.inputStream().use { input ->
                FileOutputStream(localPart).use { output -> input.copyTo(output, 64 * 1024) }
            }
            sync(localPart)
            if (!localPart.renameTo(target)) throw IOException("Cannot move output into place")
        } finally {
            if (localPart.exists()) localPart.delete()
            if (staged.exists()) staged.delete()
        }
        return target
    }

    fun discard(staged: File) {
        try {
            if (staged.exists()) staged.delete()
        } catch (_: Exception) {
        }
    }

    /** A name is a name, not a path: `../../x.txt` means `x.txt` here, never a directory above. */
    private fun leafName(desiredName: String): String =
        desiredName.substringAfterLast('/').substringAfterLast('\\')

    /** Best-effort fsync so a power cut cannot leave a rename to an empty file. */
    private fun sync(file: File) {
        try {
            FileOutputStream(file, true).use { it.fd.sync() }
        } catch (_: Exception) {
        }
    }
}
