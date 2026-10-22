package com.zeidex.convert.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import com.zeidex.convert.model.FileItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImageConverter {

    fun convertImage(
        context: Context,
        input: FileItem,
        targetExt: String,
        outputDir: File,
        quality: Int = 90,
        grayscale: Boolean = false,
        rotateDegrees: Float = 0f,
        maxWidth: Int? = null
    ): File {
        val inputStream = context.contentResolver.openInputStream(input.uri)
            ?: throw IllegalStateException("Cannot open input stream for ${input.name}")

        var bitmap = BitmapFactory.decodeStream(inputStream)
            ?: throw IllegalStateException("Cannot decode image ${input.name}")
        inputStream.close()

        // Apply rotation if requested
        if (rotateDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotateDegrees) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        // Apply resize if requested
        if (maxWidth != null && maxWidth > 0 && bitmap.width > maxWidth) {
            val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
            val newHeight = (bitmap.height * ratio).toInt()
            bitmap = Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
        }

        // Apply grayscale if requested
        if (grayscale) {
            val grayBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(grayBitmap)
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            bitmap = grayBitmap
        }

        val baseName = input.name.substringBeforeLast('.')
        val outFile = File(outputDir, "${baseName}_converted.$targetExt")

        when (targetExt.lowercase()) {
            "jpg", "jpeg" -> {
                FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
            }
            "png" -> {
                FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            "webp" -> {
                FileOutputStream(outFile).use { out ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
                    }
                }
            }
            "pdf" -> {
                val pdfDoc = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
                val page = pdfDoc.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdfDoc.finishPage(page)
                FileOutputStream(outFile).use { out -> pdfDoc.writeTo(out) }
                pdfDoc.close()
            }
            "ico" -> {
                val pngStream = ByteArrayOutputStream()
                val scaled = Bitmap.createScaledBitmap(bitmap, 256.coerceAtMost(bitmap.width), 256.coerceAtMost(bitmap.height), true)
                scaled.compress(Bitmap.CompressFormat.PNG, 100, pngStream)
                val pngBytes = pngStream.toByteArray()

                FileOutputStream(outFile).use { out ->
                    // ICONDIR header: 6 bytes
                    val header = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                    header.putShort(0) // reserved
                    header.putShort(1) // type: 1 = icon
                    header.putShort(1) // count: 1 image
                    out.write(header.array())

                    // ICONDIRENTRY: 16 bytes
                    val entry = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    entry.put((scaled.width % 256).toByte())
                    entry.put((scaled.height % 256).toByte())
                    entry.put(0) // color count
                    entry.put(0) // reserved
                    entry.putShort(1) // planes
                    entry.putShort(32) // bpp
                    entry.putInt(pngBytes.size) // size of image
                    entry.putInt(22) // image offset (6 + 16)
                    out.write(entry.array())

                    out.write(pngBytes)
                }
            }
            else -> {
                FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
            }
        }

        return outFile
    }
}
