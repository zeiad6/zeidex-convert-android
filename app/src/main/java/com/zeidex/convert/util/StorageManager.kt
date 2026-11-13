package com.zeidex.convert.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.zeidex.convert.model.Category
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object StorageManager {

    private const val APP_DIR_NAME = "ZeidexConvert"

    /**
     * Gets the main ZeidexConvert root directory in the public Downloads directory.
     * Falls back to app-specific external files dir if public storage is unavailable.
     */
    fun getRootDir(context: Context): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = if (publicDownloads != null && (publicDownloads.exists() || publicDownloads.mkdirs())) {
            File(publicDownloads, APP_DIR_NAME)
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, APP_DIR_NAME)
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Returns the dedicated subfolder for a given media category or special name.
     */
    fun getSubfolder(context: Context, category: Category? = null, specialName: String? = null): File {
        val root = getRootDir(context)
        val folderName = when {
            specialName != null -> specialName
            category == Category.IMAGE -> "Images"
            category == Category.VIDEO -> "Videos"
            category == Category.AUDIO -> "Audio"
            category == Category.PDF -> "PDF"
            category == Category.ARCHIVE -> "Archives"
            category == Category.DOCUMENT -> "Documents"
            category == Category.SUBTITLE -> "Subtitles"
            else -> "General"
        }
        val subfolder = File(root, folderName)
        if (!subfolder.exists()) subfolder.mkdirs()
        return subfolder
    }

    /**
     * Commits a file an engine already staged in [dir] and returns its final path.
     * The engine stages next to the destination, so this is a plain atomic rename.
     */
    fun deliver(
        context: Context,
        staged: File,
        category: Category? = null,
        specialName: String? = null,
        targetFileName: String? = null
    ): File {
        val targetDir = getSubfolder(context, category, specialName)
        val name = targetFileName ?: staged.name
        val finalFile = AtomicWriter.commit(staged, targetDir, name)
        scan(context, finalFile)
        return finalFile
    }

    /**
     * True when [file] already lives under the shared ZeidexConvert tree.
     * Used to keep engines that write straight to the destination from being copied twice.
     */
    fun isInPublicStorage(context: Context, file: File): Boolean {
        return FileSafety.isInside(file, getRootDir(context))
    }

    /** Idempotent hand-off: copies only when the engine produced its output elsewhere. */
    fun finalizeOutput(context: Context, file: File, category: Category? = null): File {
        if (!file.exists()) throw IOException("Output disappeared: ${file.name}")
        if (isInPublicStorage(context, file)) {
            scan(context, file)
            return file
        }
        return deliver(context, file, category)
    }

    /**
     * Copies [sourceFile] into the shared tree without ever overwriting an existing name.
     * Kept for callers that still hand over a fully written file.
     */
    fun saveToPhoneStorage(
        context: Context,
        sourceFile: File,
        category: Category? = null,
        specialName: String? = null,
        targetFileName: String? = null
    ): File {
        val targetDir = getSubfolder(context, category, specialName)
        val name = targetFileName ?: sourceFile.name
        if (sourceFile.exists() && FileSafety.isInside(sourceFile, targetDir)) {
            scan(context, sourceFile)
            return sourceFile
        }
        val staged = AtomicWriter.stage(targetDir, name)
        try {
            sourceFile.inputStream().use { input ->
                FileOutputStream(staged).use { output -> input.copyTo(output, 64 * 1024) }
            }
            val finalFile = AtomicWriter.commit(staged, targetDir, name)
            scan(context, finalFile)
            return finalFile
        } catch (t: Throwable) {
            AtomicWriter.discard(staged)
            throw t
        }
    }

    /**
     * Scans an entire directory so extracted archives appear in file managers.
     * Bounded so a huge tree cannot stall the caller.
     */
    fun scanDirectory(context: Context, dir: File) {
        try {
            val paths = dir.walkTopDown()
                .onEnter { it == dir || it.name != ".thumbnails" }
                .filter { it.isFile }
                .take(2_000)
                .map { it.absolutePath }
                .toList()
                .toTypedArray()
            if (paths.isNotEmpty()) {
                MediaScannerConnection.scanFile(context.applicationContext, paths, null, null)
            }
        } catch (_: Exception) {
        }
    }

    private fun scan(context: Context, file: File) {
        try {
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), null, null)
        } catch (_: Exception) {
        }
    }
}
