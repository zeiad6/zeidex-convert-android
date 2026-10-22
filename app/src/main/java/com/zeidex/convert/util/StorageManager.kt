package com.zeidex.convert.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.zeidex.convert.model.Category
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
            else -> "General"
        }
        val subfolder = File(root, folderName)
        if (!subfolder.exists()) subfolder.mkdirs()
        return subfolder
    }

    /**
     * Automatically saves a converted file to the phone's dedicated Zeidex storage
     * and notifies Android's MediaScanner so it immediately appears in Gallery/Files.
     */
    fun saveToPhoneStorage(
        context: Context,
        sourceFile: File,
        category: Category? = null,
        specialName: String? = null,
        targetFileName: String? = null
    ): File {
        val targetDir = getSubfolder(context, category, specialName)
        val fileName = targetFileName ?: sourceFile.name
        val destFile = File(targetDir, fileName)

        // If source is already in the target location, skip copying
        if (sourceFile.absolutePath != destFile.absolutePath) {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        // Notify MediaScanner for public visibility
        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(destFile.absolutePath),
                null,
                null
            )
        } catch (_: Exception) {}

        return destFile
    }

    /**
     * Scans an entire directory so extracted archives appear in file managers.
     */
    fun scanDirectory(context: Context, dir: File) {
        try {
            val paths = dir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList().toTypedArray()
            if (paths.isNotEmpty()) {
                MediaScannerConnection.scanFile(context.applicationContext, paths, null, null)
            }
        } catch (_: Exception) {}
    }
}
