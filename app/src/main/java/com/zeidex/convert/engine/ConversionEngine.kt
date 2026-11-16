package com.zeidex.convert.engine

import android.content.Context
import com.zeidex.convert.R
import com.zeidex.convert.model.Category
import com.zeidex.convert.model.ConversionTask
import com.zeidex.convert.model.TaskStatus
import com.zeidex.convert.service.ConversionForegroundService
import com.zeidex.convert.util.AtomicWriter
import com.zeidex.convert.util.StorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Routes one task to the engine that can actually do it.
 *
 * Cancellation is cooperative: every stage checks `ensureActive()` and `CancellationException`
 * is re-thrown instead of being turned into a "failed" task, which is what used to happen when
 * the user pressed pause.
 */
class ConversionEngine(private val context: Context) {

    suspend fun processTask(
        task: ConversionTask,
        compressionQuality: Int = 85,
        maxWidth: Int? = null,
        scalePercent: Int? = null,
        cropRatio: String? = null,
        stripMetadata: Boolean = false,
        onProgress: (Float) -> Unit
    ): ConversionTask = withContext(Dispatchers.IO) {
        try {
            ensureActive()
            onProgress(0.02f)
            val targetExt = task.targetFormat.ext.lowercase()
            val input = task.inputFile

            val resultFile: File = when {
                // Live/Motion photo: the embedded video, not a transcode.
                targetExt == "mp4" && input.category == Category.IMAGE -> {
                    onProgress(0.15f)
                    val dir = StorageManager.getSubfolder(context, specialName = "LivePhotos")
                    MotionPhotoExtractor.extractMotionVideo(context, input, dir)
                }

                // Audio track out of a video (or a different container for an audio file).
                (input.category == Category.VIDEO || input.category == Category.AUDIO) &&
                    (targetExt == "m4a" || targetExt == "aac") -> {
                    onProgress(0.1f)
                    val dir = StorageManager.getSubfolder(context, Category.AUDIO)
                    MediaAudioExtractor.extractAudio(context, input, dir, "m4a") { p -> onProgress(0.1f + p * 0.8f) }
                }

                input.category == Category.VIDEO && targetExt == "mp4" -> {
                    onProgress(0.1f)
                    val dir = StorageManager.getSubfolder(context, Category.VIDEO)
                    val mode = if (stripMetadata) VideoEnhancer.RemuxMode.STRIP_METADATA else VideoEnhancer.RemuxMode.FAST
                    VideoEnhancer.remuxVideo(context, input, dir, mode) { p -> onProgress(0.1f + p * 0.8f) }
                }

                input.category == Category.PDF && (targetExt == "png" || targetExt == "jpg") -> {
                    onProgress(0.1f)
                    val dir = StorageManager.getSubfolder(context, Category.IMAGE)
                    val pages = PdfConverter.pdfToImages(context, input, dir, targetExt) { p -> onProgress(0.1f + p * 0.8f) }
                    pages.firstOrNull() ?: throw IllegalStateException("No pages rendered from the PDF")
                }

                input.category == Category.PDF && targetExt == "docx" -> {
                    onProgress(0.2f)
                    DocumentConverter.pdfToDocx(context, input, StorageManager.getSubfolder(context, Category.DOCUMENT))
                }

                input.category == Category.PDF && targetExt == "txt" -> {
                    onProgress(0.2f)
                    val job = coroutineContext[kotlinx.coroutines.Job]
                    DocumentConverter.convert(
                        context = context,
                        input = input,
                        targetExt = "txt",
                        outputDir = StorageManager.getSubfolder(context, Category.DOCUMENT),
                        onProgress = { progress -> onProgress(0.2f + progress * 0.7f) },
                        checkActive = { if (job?.isActive == false) throw CancellationException("Cancelled") }
                    )
                }

                input.category == Category.DOCUMENT && targetExt in setOf("pdf", "docx", "txt", "md") -> {
                    onProgress(0.15f)
                    val job = coroutineContext[kotlinx.coroutines.Job]
                    val destination = if (targetExt == "pdf") Category.PDF else Category.DOCUMENT
                    DocumentConverter.convert(
                        context = context,
                        input = input,
                        targetExt = targetExt,
                        outputDir = StorageManager.getSubfolder(context, destination),
                        onProgress = { progress -> onProgress(0.15f + progress * 0.8f) },
                        checkActive = { if (job?.isActive == false) throw CancellationException("Cancelled") }
                    )
                }

                input.category == Category.SUBTITLE && targetExt in SubtitleConverter.SUPPORTED -> {
                    onProgress(0.3f)
                    val dir = StorageManager.getSubfolder(context, Category.SUBTITLE)
                    val base = input.name.substringBeforeLast('.').ifBlank { "subtitle" }
                    val staged = AtomicWriter.stage(dir, "$base.$targetExt")
                    try {
                        context.contentResolver.openInputStream(input.uri)?.use { stream ->
                            staged.outputStream().use { out ->
                                SubtitleConverter.convert(stream, input.ext, targetExt, out)
                            }
                        } ?: throw IllegalStateException("Cannot open ${input.name}")
                        AtomicWriter.commit(staged, dir, "$base.$targetExt")
                    } catch (t: Throwable) {
                        AtomicWriter.discard(staged)
                        throw t
                    }
                }

                input.category == Category.IMAGE -> {
                    onProgress(0.2f)
                    ImageConverter.convertImage(
                        context = context,
                        input = input,
                        targetExt = targetExt,
                        outputDir = StorageManager.getSubfolder(context, Category.IMAGE),
                        quality = compressionQuality,
                        maxWidth = maxWidth,
                        scalePercent = scalePercent,
                        cropRatio = cropRatio,
                        stripMetadata = stripMetadata
                    )
                }

                input.category == Category.ARCHIVE && (targetExt == "extract" || targetExt == "unzip") -> {
                    onProgress(0.2f)
                    val targetDir = StorageManager.getSubfolder(context, Category.ARCHIVE)
                    val files = ArchiveConverter.extractArchive(context, input, targetDir)
                    files.firstOrNull() ?: targetDir
                }

                targetExt == "zip" -> {
                    onProgress(0.2f)
                    ArchiveConverter.createZip(
                        context = context,
                        inputs = listOf(input),
                        outputDir = StorageManager.getSubfolder(context, Category.ARCHIVE),
                        archiveName = "${input.name.substringBeforeLast('.')}.zip"
                    )
                }

                else -> throw UnsupportedOperationException(
                    "Conversion from .${input.ext} to .$targetExt is not supported on-device. The desktop app covers this format."
                )
            }

            ensureActive()
            onProgress(0.98f)
            val savedFile = StorageManager.finalizeOutput(context, resultFile, task.targetFormat.category)
            onProgress(1f)

            ConversionForegroundService.showCompletionNotification(
                context = context,
                title = context.getString(R.string.conversion_done_title),
                message = context.getString(R.string.conversion_done_message, savedFile.absolutePath),
                file = savedFile
            )

            task.copy(status = TaskStatus.COMPLETED, progress = 1f, outputFile = savedFile)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            task.copy(
                status = TaskStatus.FAILED,
                progress = 0f,
                error = e.localizedMessage ?: e.message ?: e::class.java.simpleName
            )
        }
    }
}
