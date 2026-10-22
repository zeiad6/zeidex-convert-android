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
import com.zeidex.convert.service.ConversionForegroundService
import com.zeidex.convert.util.StorageManager
import java.io.BufferedOutputStream
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
        outputDir: File? = null,
        quality: Int = 85,
        grayscale: Boolean = false,
        rotateDegrees: Float = 0f,
        maxWidth: Int? = null,
        sharpen: Boolean = false,
        autoEnhance: Boolean = false,
        upscale2x: Boolean = false
    ): File {
        val targetDir = outputDir ?: StorageManager.getSubfolder(context, Category.IMAGE)
        val baseName = input.name.substringBeforeLast('.')
        val outFile = File(targetDir, "${baseName}_converted.$targetExt")

        // First pass: check EXIF orientation
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

        // Apply 2X super-resolution upscaling if requested
        if (upscale2x) {
            val targetW = (bitmap.width * 2).coerceAtMost(8192)
            val targetH = (bitmap.height * 2).coerceAtMost(8192)
            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            if (scaled != bitmap) {
                bitmap.recycle()
                bitmap = scaled
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

        // Apply Auto-Enhance (Contrast, Dynamic Range, Vibrance)
        if (autoEnhance) {
            val enhanced = applyAutoEnhance(bitmap)
            if (enhanced != bitmap) {
                bitmap.recycle()
                bitmap = enhanced
            }
        }

        // Apply Sharpening (Laplacian edge detail enhancement)
        if (sharpen) {
            val sharpened = applySharpen(bitmap)
            if (sharpened != bitmap) {
                bitmap.recycle()
                bitmap = sharpened
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
            "bmp" -> {
                writeBmp(bitmap, outFile)
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
        val savedFile = StorageManager.saveToPhoneStorage(context, outFile, Category.IMAGE)

        // Show completion notification with saved path
        ConversionForegroundService.showCompletionNotification(
            context = context,
            title = "Zeidex Convert • تم حفظ الصورة",
            message = "تم حفظ الصورة بنجاح في:\n${savedFile.absolutePath}",
            file = savedFile
        )

        return savedFile
    }

    private fun applyAutoEnhance(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val contrast = 1.18f
        val brightness = -8f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))
        val satMatrix = ColorMatrix().apply { setSaturation(1.15f) }
        satMatrix.postConcat(contrastMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(satMatrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun applySharpen(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val srcPixels = IntArray(width * height)
        val dstPixels = IntArray(width * height)
        source.getPixels(srcPixels, 0, width, 0, 0, width, height)

        for (y in 1 until height - 1) {
            val yWidth = y * width
            for (x in 1 until width - 1) {
                val idx = yWidth + x
                val center = srcPixels[idx]
                val top = srcPixels[idx - width]
                val bottom = srcPixels[idx + width]
                val left = srcPixels[idx - 1]
                val right = srcPixels[idx + 1]

                val a = (center ushr 24) and 0xFF
                var r = (5 * ((center ushr 16) and 0xFF)) - ((top ushr 16) and 0xFF) - ((bottom ushr 16) and 0xFF) - ((left ushr 16) and 0xFF) - ((right ushr 16) and 0xFF)
                var g = (5 * ((center ushr 8) and 0xFF)) - ((top ushr 8) and 0xFF) - ((bottom ushr 8) and 0xFF) - ((left ushr 8) and 0xFF) - ((right ushr 8) and 0xFF)
                var b = (5 * (center and 0xFF)) - (top and 0xFF) - (bottom and 0xFF) - (left and 0xFF) - (right and 0xFF)

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                dstPixels[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // Copy border pixels
        System.arraycopy(srcPixels, 0, dstPixels, 0, width)
        System.arraycopy(srcPixels, (height - 1) * width, dstPixels, (height - 1) * width, width)
        for (y in 0 until height) {
            dstPixels[y * width] = srcPixels[y * width]
            dstPixels[y * width + (width - 1)] = srcPixels[y * width + (width - 1)]
        }

        return Bitmap.createBitmap(dstPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun writeBmp(bitmap: Bitmap, outFile: File) {
        val width = bitmap.width
        val height = bitmap.height
        val rowPadding = (4 - (width * 3) % 4) % 4
        val rowSize = width * 3 + rowPadding
        val imageSize = rowSize * height
        val fileSize = 54 + imageSize

        BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
            val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)

            // BITMAPFILEHEADER (14 bytes)
            header.put(0x42.toByte()) // 'B'
            header.put(0x4D.toByte()) // 'M'
            header.putInt(fileSize)
            header.putShort(0)
            header.putShort(0)
            header.putInt(54) // Offset to pixel data

            // BITMAPINFOHEADER (40 bytes)
            header.putInt(40) // biSize
            header.putInt(width)
            header.putInt(height) // positive = bottom-up
            header.putShort(1) // biPlanes
            header.putShort(24) // biBitCount
            header.putInt(0) // BI_RGB
            header.putInt(imageSize)
            header.putInt(2835) // biXPelsPerMeter (72 DPI)
            header.putInt(2835)
            header.putInt(0)
            header.putInt(0)

            bos.write(header.array())

            // Pixels bottom-up
            val rowPixels = IntArray(width)
            val rowBytes = ByteArray(rowSize)

            for (y in height - 1 downTo 0) {
                bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
                var byteIdx = 0
                for (x in 0 until width) {
                    val color = rowPixels[x]
                    rowBytes[byteIdx++] = (color and 0xFF).toByte()         // Blue
                    rowBytes[byteIdx++] = ((color ushr 8) and 0xFF).toByte()  // Green
                    rowBytes[byteIdx++] = ((color ushr 16) and 0xFF).toByte() // Red
                }
                for (p in 0 until rowPadding) {
                    rowBytes[byteIdx++] = 0
                }
                bos.write(rowBytes, 0, byteIdx)
            }
            bos.flush()
        }
    }
}
