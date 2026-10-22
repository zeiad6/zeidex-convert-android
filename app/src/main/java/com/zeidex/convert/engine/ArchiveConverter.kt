package com.zeidex.convert.engine

import android.content.Context
import com.zeidex.convert.model.Category
import com.zeidex.convert.model.FileItem
import com.zeidex.convert.util.StorageManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ArchiveConverter {

    /**
     * Creates a ZIP archive containing the provided input files.
     */
    fun createZip(
        context: Context,
        inputs: List<FileItem>,
        outputDir: File,
        archiveName: String = "archive.zip",
        compressionLevel: Int = 6
    ): File {
        val targetDir = StorageManager.getSubfolder(context, Category.ARCHIVE)
        val outFile = File(targetDir, archiveName)
        
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            zos.setLevel(compressionLevel.coerceIn(0, 9))
            val buffer = ByteArray(64 * 1024)
            for (item in inputs) {
                val inputStream = context.contentResolver.openInputStream(item.uri) ?: continue
                val entry = ZipEntry(item.name)
                zos.putNextEntry(entry)
                var len: Int
                while (inputStream.read(buffer).also { len = it } > 0) {
                    zos.write(buffer, 0, len)
                }
                zos.closeEntry()
                inputStream.close()
            }
        }

        StorageManager.saveToPhoneStorage(context, outFile, Category.ARCHIVE)
        return outFile
    }

    /**
     * Safely extracts a ZIP or GZ archive directly into the dedicated Zeidex/Archives directory.
     */
    fun extractZip(
        context: Context,
        input: FileItem,
        outputDir: File
    ): List<File> {
        val inputStream = context.contentResolver.openInputStream(input.uri)
            ?: throw IllegalStateException("Cannot open ${input.name}")

        val targetDir = StorageManager.getSubfolder(context, Category.ARCHIVE)
        val baseFolder = File(targetDir, input.name.substringBeforeLast('.'))
        if (!baseFolder.exists()) baseFolder.mkdirs()

        val extractedFiles = mutableListOf<File>()
        val canonicalDest = baseFolder.canonicalPath

        if (input.ext.lowercase() == "gz" && !input.name.lowercase().endsWith(".tar.gz")) {
            // Single file GZIP extraction
            val uncompressedName = input.name.substringBeforeLast(".gz")
            val targetFile = File(baseFolder, uncompressedName)
            GZIPInputStream(inputStream).use { gis ->
                FileOutputStream(targetFile).use { fos ->
                    gis.copyTo(fos)
                }
            }
            extractedFiles.add(targetFile)
        } else {
            // Standard ZIP stream
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(64 * 1024)
                while (entry != null) {
                    val destFile = File(baseFolder, entry.name)
                    // ZipSlip security check
                    if (!destFile.canonicalPath.startsWith(canonicalDest)) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        destFile.mkdirs()
                    } else {
                        destFile.parentFile?.mkdirs()
                        FileOutputStream(destFile).use { fos ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                        extractedFiles.add(destFile)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        // Notify MediaScanner for all extracted files
        StorageManager.scanDirectory(context, baseFolder)

        return if (extractedFiles.isNotEmpty()) extractedFiles else listOf(baseFolder)
    }
}
