package com.zeidex.convert.engine

import android.content.Context
import com.zeidex.convert.model.Category
import com.zeidex.convert.model.ConversionTask
import com.zeidex.convert.model.TaskStatus
import com.zeidex.convert.util.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ConversionEngine(private val context: Context) {

    suspend fun processTask(
        task: ConversionTask,
        compressionQuality: Int = 85,
        maxWidth: Int? = null,
        onProgress: (Float) -> Unit
    ): ConversionTask = withContext(Dispatchers.IO) {
        try {
            onProgress(0.1f)
            val targetExt = task.targetFormat.ext.lowercase()
            val input = task.inputFile

            val resultFile: File = when {
                // Motion Photo Extraction (JPEG -> MP4)
                targetExt == "mp4" && input.category == Category.IMAGE -> {
                    onProgress(0.3f)
                    val dir = StorageManager.getSubfolder(context, specialName = "LivePhotos")
                    MotionPhotoExtractor.extractMotionVideo(context, input, dir)
                }

                // Audio extraction from Video (Video -> M4A / MP3 / AAC)
                input.category == Category.VIDEO && (targetExt == "m4a" || targetExt == "mp3" || targetExt == "aac" || targetExt == "wav") -> {
                    onProgress(0.3f)
                    val dir = StorageManager.getSubfolder(context, Category.AUDIO)
                    MediaAudioExtractor.extractAudio(context, input, dir, targetExt) { p ->
                        onProgress(p)
                    }
                }

                // PDF to Image pages
                input.category == Category.PDF && (targetExt == "png" || targetExt == "jpg") -> {
                    onProgress(0.4f)
                    val pages = PdfConverter.pdfToImages(context, input, null, targetExt)
                    pages.firstOrNull() ?: throw IllegalStateException("No pages rendered from PDF")
                }

                // Single Image conversion (JPG, PNG, WEBP, PDF, ICO, etc.)
                input.category == Category.IMAGE -> {
                    onProgress(0.4f)
                    ImageConverter.convertImage(
                        context = context,
                        input = input,
                        targetExt = targetExt,
                        quality = compressionQuality,
                        maxWidth = maxWidth
                    )
                }

                // Archive Extraction
                input.category == Category.ARCHIVE && (targetExt == "extract" || targetExt == "unzip") -> {
                    onProgress(0.4f)
                    val targetDir = StorageManager.getSubfolder(context, Category.ARCHIVE)
                    val files = ArchiveConverter.extractZip(context, input, targetDir)
                    files.firstOrNull() ?: targetDir
                }

                // Archive Packaging (Any file -> ZIP)
                targetExt == "zip" -> {
                    onProgress(0.4f)
                    val targetDir = StorageManager.getSubfolder(context, Category.ARCHIVE)
                    ArchiveConverter.createZip(
                        context = context,
                        inputs = listOf(input),
                        outputDir = targetDir,
                        archiveName = "${input.name.substringBeforeLast('.')}.zip"
                    )
                }

                else -> {
                    // Fallback to image conversion if applicable or zip
                    if (input.mimeType?.startsWith("image/") == true) {
                        ImageConverter.convertImage(
                            context = context,
                            input = input,
                            targetExt = targetExt,
                            quality = compressionQuality,
                            maxWidth = maxWidth
                        )
                    } else {
                        throw UnsupportedOperationException("Conversion from .${input.ext} to .$targetExt is not supported yet")
                    }
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
