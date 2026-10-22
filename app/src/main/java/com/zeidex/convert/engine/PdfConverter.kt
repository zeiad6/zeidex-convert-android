package com.zeidex.convert.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.zeidex.convert.model.Category
import com.zeidex.convert.model.FileItem
import com.zeidex.convert.util.StorageManager
import java.io.File
import java.io.FileOutputStream

object PdfConverter {

    /**
     * Converts a PDF file into individual image pages (PNG or JPG).
     * Saves them automatically into /Download/ZeidexConvert/Images/
     */
    fun pdfToImages(
        context: Context,
        input: FileItem,
        outputDir: File? = null,
        targetExt: String = "png",
        dpiScale: Float = 2.0f
    ): List<File> {
        val targetDir = StorageManager.getSubfolder(context, Category.IMAGE)
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(input.uri, "r")
            ?: throw IllegalStateException("Cannot open PDF file descriptor")

        val renderer = PdfRenderer(pfd)
        val pageCount = renderer.pageCount
        val outputFiles = mutableListOf<File>()
        val baseName = input.name.substringBeforeLast('.')

        val format = if (targetExt.lowercase() == "jpg" || targetExt.lowercase() == "jpeg") {
            Bitmap.CompressFormat.JPEG
        } else {
            Bitmap.CompressFormat.PNG
        }

        for (i in 0 until pageCount) {
            val page = renderer.openPage(i)
            val width = (page.width * dpiScale).toInt()
            val height = (page.height * dpiScale).toInt()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val pageFile = File(targetDir, "${baseName}_page_${i + 1}.$targetExt")
            FileOutputStream(pageFile).use { out ->
                bitmap.compress(format, 95, out)
            }
            StorageManager.saveToPhoneStorage(context, pageFile, Category.IMAGE)
            outputFiles.add(pageFile)
        }

        renderer.close()
        pfd.close()
        return outputFiles
    }

    /**
     * Combines multiple images into a single multi-page PDF document.
     * Saves automatically into /Download/ZeidexConvert/PDF/
     */
    fun imagesToPdf(
        context: Context,
        inputs: List<FileItem>,
        outputDir: File? = null,
        pdfName: String = "combined.pdf"
    ): File {
        val targetDir = StorageManager.getSubfolder(context, Category.PDF)
        val pdfDoc = PdfDocument()
        var pageNum = 1

        for (item in inputs) {
            val inputStream = context.contentResolver.openInputStream(item.uri) ?: continue
            val bitmap = BitmapFactory.decodeStream(inputStream) ?: continue
            inputStream.close()

            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pageNum).create()
            val page = pdfDoc.startPage(pageInfo)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdfDoc.finishPage(page)
            pageNum++
        }

        val outFile = File(targetDir, pdfName)
        FileOutputStream(outFile).use { out -> pdfDoc.writeTo(out) }
        pdfDoc.close()

        StorageManager.saveToPhoneStorage(context, outFile, Category.PDF)
        return outFile
    }
}
