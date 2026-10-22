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
import android.media.ExifInterface
import android.os.Build
import com.zeidex.convert.model.Category
import com.zeidex.convert.model.FileItem
import com.zeidex.convert.util.StorageManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImageConverter {

    fun convertImage(
        context: Context,
        input: FileItem,
        targetExt: String,
        outputDir: File? = null,
        quality: Int = 85,
        grayscale: Boolean = false,
        rotateDegrees: Float = 0f,
        maxWidth: Int? = null
    ): File {
        val targetDir = StorageManager.getSubfolder(context, Category.IMAGE)
        val baseName = input.name.substringBeforeLast('.')
        val outFile = File(targetDir, "${baseName}_converted.$targetExt")

        // First pass: check EXIF orientation and bounds
        var exifRotation = 0f
        try {
            context.contentResolver.openInputStream(input.uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                exifRotation = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        } catch (_: Exception) {}

        // Safe decode with memory limits
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(input.uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }

        var sampleSize = 1
        val maxDimension = 4096
        if (boundsOptions.outWidth > maxDimension || boundsOptions.outHeight > maxDimension) {
            val halfHeight = boundsOptions.outHeight / 2
            val halfWidth = boundsOptions.outWidth / 2
            while ((halfHeight / sampleSize) >= maxDimension || (halfWidth / sampleSize) >= maxDimension) {
                sampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        var bitmap = context.contentResolver.openInputStream(input.uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: throw IllegalStateException("Cannot decode image: ${input.name}")

        // Apply EXIF auto-rotation + user rotation
        val totalRotation = (exifRotation + rotateDegrees) % 360f
        if (totalRotation != 0f) {
            val matrix = Matrix().apply { postRotate(totalRotation) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
                bitmap = rotated
            }
        }

        // Apply resize if requested
        if (maxWidth != null && maxWidth > 0 && bitmap.width > maxWidth) {
            val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
            val newHeight = (bitmap.height * ratio).toInt()
            val scaled = Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
            if (scaled != bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        // Apply grayscale if requested
        if (grayscale) {
            val grayBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(grayBitmap)
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            bitmap.recycle()
            bitmap = grayBitmap
        }

        val safeQuality = quality.coerceIn(10, 100)

        when (targetExt.lowercase()) {
            "jpg", "jpeg" -> {
                FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, out)
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
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, safeQuality, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, safeQuality, out)
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
                    val header = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                    header.putShort(0)
                    header.putShort(1)
                    header.putShort(1)
                    out.write(header.array())

                    val entry = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    entry.put((scaled.width % 256).toByte())
                    entry.put((scaled.height % 256).toByte())
                    entry.put(0)
                    entry.put(0)
                    entry.putShort(1)
                    entry.putShort(32)
                    entry.putInt(pngBytes.size)
                    entry.putInt(22)
                    out.write(entry.array())

                    out.write(pngBytes)
                }
                if (scaled != bitmap) scaled.recycle()
            }
            else -> {
                FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, out)
                }
            }
        }

        bitmap.recycle()
        StorageManager.saveToPhoneStorage(context, outFile, Category.IMAGE)
        return outFile
    }
}
