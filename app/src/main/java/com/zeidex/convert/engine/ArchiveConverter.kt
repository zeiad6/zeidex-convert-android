package com.zeidex.convert.engine

import android.content.Context
import com.zeidex.convert.model.FileItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ArchiveConverter {

    fun createZip(
        context: Context,
        inputs: List<FileItem>,
        outputDir: File,
        archiveName: String = "archive.zip"
    ): File {
        val outFile = File(outputDir, archiveName)
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
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
        return outFile
    }

    fun extractZip(
        context: Context,
        input: FileItem,
        outputDir: File
    ): List<File> {
        val inputStream = context.contentResolver.openInputStream(input.uri)
            ?: throw IllegalStateException("Cannot open ${input.name}")

        val extractedFiles = mutableListOf<File>()
        val extractTargetDir = File(outputDir, input.name.substringBeforeLast('.'))
        extractTargetDir.mkdirs()

        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(64 * 1024)
            while (entry != null) {
                val newFile = File(extractTargetDir, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                    extractedFiles.add(newFile)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return extractedFiles
    }
}
