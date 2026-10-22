package com.zeidex.convert.engine

import android.content.Context
import com.zeidex.convert.model.Category
import com.zeidex.convert.model.ConversionTask
import com.zeidex.convert.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ConversionEngine(private val context: Context) {

    private val outputDirectory: File
        get() {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Converted")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    suspend fun processTask(task: ConversionTask, onProgress: (Float) -> Unit): ConversionTask = withContext(Dispatchers.IO) {
        try {
            onProgress(0.1f)
            val targetExt = task.targetFormat.ext.lowercase()
            val input = task.inputFile

            val resultFile: File = when {
                // Motion Photo Extraction
                targetExt == "mp4" && input.category == Category.IMAGE -> {
                    onProgress(0.3f)
                    MotionPhotoExtractor.extractMotionVideo(context, input, outputDirectory)
                }

                // PDF to Image pages
                input.category == Category.PDF && (targetExt == "png" || targetExt == "jpg") -> {
                    onProgress(0.4f)
                    val pages = PdfConverter.pdfToImages(context, input, outputDirectory, targetExt)
                    pages.firstOrNull() ?: throw IllegalStateException("No pages rendered from PDF")
                }

                // Single Image conversion (JPG, PNG, WEBP, PDF, ICO, etc.)
                input.category == Category.IMAGE -> {
                    onProgress(0.4f)
                    ImageConverter.convertImage(context, input, targetExt, outputDirectory)
                }

                // Archive Extraction
                input.category == Category.ARCHIVE && targetExt == "extract" -> {
                    onProgress(0.4f)
                    val files = ArchiveConverter.extractZip(context, input, outputDirectory)
                    files.firstOrNull() ?: outputDirectory
                }

                // Archive Packaging
                targetExt == "zip" -> {
                    onProgress(0.4f)
                    ArchiveConverter.createZip(context, listOf(input), outputDirectory, "${input.name.substringBeforeLast('.')}.zip")
                }

                else -> {
                    throw UnsupportedOperationException("Conversion from .${input.ext} to .$targetExt is not supported yet")
                }
            }

            onProgress(1.0f)
            task.copy(
                status = TaskStatus.COMPLETED,
                progress = 1.0f,
                outputFile = resultFile
            )
        } catch (e: Exception) {
            task.copy(
                status = TaskStatus.FAILED,
                progress = 0f,
                error = e.localizedMessage ?: e.message ?: "Unknown error"
            )
        }
    }
}
