package com.zeidex.convert.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
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
    /** Names another process may claim between our check and our move before we give up. */
    private const val MAX_NAME_RACES = 64
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
     * Never overwrites: `rename(2)` replaces an existing file, so the name is claimed with a hard
     * link (which fails instead) where the filesystem has them, and with a checked rename where
     * it does not. `@Synchronized` keeps parallel workers in this process from picking the same
     * free name.
     */
    @Synchronized
    fun commit(staged: File, dir: File, desiredName: String): File {
        if (!staged.exists()) throw IOException("Missing staged file ${staged.name}")
        sync(staged)
        val name = leafName(desiredName)
        place(staged, dir, name)?.let { return it }
        // Different mount or a filesystem that refuses rename: copy, fsync, then place the copy.
        val localPart = File(dir, ".$name.${System.nanoTime()}$PART_SUFFIX")
        try {
            staged.inputStream().use { input ->
                FileOutputStream(localPart).use { output -> input.copyTo(output, 64 * 1024) }
            }
            sync(localPart)
            return place(localPart, dir, name) ?: throw IOException("Cannot move output into place")
        } finally {
            if (localPart.exists()) localPart.delete()
            if (staged.exists()) staged.delete()
        }
    }

    /** Gives [src] the first free name in [dir]; null when the filesystem refuses the move. */
    private fun place(src: File, dir: File, name: String): File? {
        repeat(MAX_NAME_RACES) {
            val target = FileSafety.uniqueFile(dir, name)
            try {
                Files.createLink(target.toPath(), src.toPath())
                src.delete()
                return target
            } catch (_: FileAlreadyExistsException) {
                return@repeat
            } catch (_: Exception) {
                // No hard links here (FUSE shared storage, FAT): fall back to a checked rename.
            }
            if (target.exists()) return@repeat
            return if (src.renameTo(target)) target else null
        }
        throw IOException("Cannot find a free name for $name")
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
