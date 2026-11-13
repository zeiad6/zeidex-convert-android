package com.zeidex.convert.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.zeidex.convert.model.Category
import com.zeidex.convert.model.FileItem
import com.zeidex.convert.util.AtomicWriter
import com.zeidex.convert.util.StorageManager
import java.io.File
import java.io.FileOutputStream

object PdfConverter {

    private const val MAX_PAGE_PIXELS = 4096

    /**
     * Renders each PDF page to its own image. Every page is committed atomically and the
     * bitmap is recycled before the next page, so a 300-page document does not grow the heap.
     */
    fun pdfToImages(
        context: Context,
        input: FileItem,
        outputDir: File? = null,
        targetExt: String = "png",
        dpiScale: Float = 2.0f,
        onProgress: ((Float) -> Unit)? = null
    ): List<File> {
        val targetDir = outputDir ?: StorageManager.getSubfolder(context, Category.IMAGE)
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(input.uri, "r")
            ?: throw IllegalStateException("Cannot open PDF file descriptor")
        val renderer = PdfRenderer(pfd)
        val outputFiles = mutableListOf<File>()
        val baseName = input.name.substringBeforeLast('.').ifBlank { "document" }
        val ext = if (targetExt.lowercase() == "jpeg") "jpg" else targetExt.lowercase()
        val format = if (ext == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        try {
            val pageCount = renderer.pageCount
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                try {
                    var scale = dpiScale.coerceIn(0.5f, 4f)
                    val longest = maxOf(page.width, page.height) * scale
                    if (longest > MAX_PAGE_PIXELS) scale *= MAX_PAGE_PIXELS / longest
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val name = "${baseName}_page_${i + 1}.$ext"
                        val staged = AtomicWriter.stage(targetDir, name)
                        try {
                            FileOutputStream(staged).use { out -> bitmap.compress(format, 95, out) }
                            outputFiles.add(AtomicWriter.commit(staged, targetDir, name))
                        } catch (t: Throwable) {
                            AtomicWriter.discard(staged)
                            throw t
                        }
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    page.close()
                }
                onProgress?.invoke((i + 1).toFloat() / pageCount)
            }
        } finally {
            try { renderer.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
        return outputFiles
    }

    /**
     * Combines multiple images into a single multi-page PDF document.
     */
    fun imagesToPdf(
        context: Context,
        inputs: List<FileItem>,
        outputDir: File? = null,
        pdfName: String = "combined.pdf"
    ): File {
        val targetDir = outputDir ?: StorageManager.getSubfolder(context, Category.PDF)
        val pdfDoc = PdfDocument()
        var pageNum = 1
        try {
            for (item in inputs) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= MAX_PAGE_PIXELS || bounds.outHeight / (sample * 2) >= MAX_PAGE_PIXELS) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: continue
                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pageNum).create()
                    val page = pdfDoc.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    pdfDoc.finishPage(page)
                    pageNum++
                } finally {
                    bitmap.recycle()
                }
            }
            if (pageNum == 1) throw IllegalStateException("No readable images in the selection")
            val staged = AtomicWriter.stage(targetDir, pdfName)
            try {
                FileOutputStream(staged).use { out -> pdfDoc.writeTo(out) }
                return AtomicWriter.commit(staged, targetDir, pdfName)
            } catch (t: Throwable) {
                AtomicWriter.discard(staged)
                throw t
            }
        } finally {
            pdfDoc.close()
        }
    }
}
