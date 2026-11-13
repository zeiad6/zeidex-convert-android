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
import com.zeidex.convert.util.AtomicWriter
import com.zeidex.convert.util.StorageManager
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImageConverter {

    private const val MAX_DECODE_DIMENSION = 4096

    /**
     * Converts one image and returns the committed file. Output is staged beside the
     * destination and renamed into place, so a cancel or OOM never leaves a half-written image.
     */
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
        upscale2x: Boolean = false,
        cropRatio: String? = null,
        scalePercent: Int? = null,
        stripMetadata: Boolean = false
    ): File {
        @Suppress("NAME_SHADOWING")
        val targetDir = outputDir ?: StorageManager.getSubfolder(context, Category.IMAGE)
        val baseName = input.name.substringBeforeLast('.').ifBlank { "image" }
        val ext = targetExt.lowercase().let { if (it == "jpeg") "jpg" else it }
        val outName = "$baseName.$ext"

        val exifRotation = readExifRotation(context, input)
        var bitmap = decode(input, context)

        try {
            val totalRotation = (exifRotation + rotateDegrees) % 360f
            if (totalRotation != 0f) {
                bitmap = replace(bitmap, Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(totalRotation) }, true))
            }
            if (upscale2x) {
                val w = (bitmap.width * 2).coerceAtMost(8192)
                val h = (bitmap.height * 2).coerceAtMost(8192)
                bitmap = replace(bitmap, Bitmap.createScaledBitmap(bitmap, w, h, true))
            }
            scalePercent?.takeIf { it in 1..400 }?.let { percent ->
                val w = (bitmap.width * percent / 100f).toInt().coerceAtLeast(1)
                val h = (bitmap.height * percent / 100f).toInt().coerceAtLeast(1)
                bitmap = replace(bitmap, Bitmap.createScaledBitmap(bitmap, w, h, true))
            }
            maxWidth?.takeIf { it > 0 && bitmap.width > it }?.let { width ->
                val h = (bitmap.height * (width.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
                bitmap = replace(bitmap, Bitmap.createScaledBitmap(bitmap, width, h, true))
            }
            cropRatio?.let { ratio ->
                bitmap = cropToRatio(bitmap, ratio)
            }
            if (autoEnhance) bitmap = replace(bitmap, applyAutoEnhance(bitmap))
            if (sharpen) bitmap = replace(bitmap, applySharpen(bitmap))
            if (grayscale) bitmap = replace(bitmap, applyGrayscale(bitmap))

            val staged = AtomicWriter.stage(targetDir, outName)
            try {
                val safeQuality = quality.coerceIn(10, 100)
                when (ext) {
                    "jpg" -> FileOutputStream(staged).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, out) }
                    "png" -> FileOutputStream(staged).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    "webp" -> FileOutputStream(staged).use { out ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, safeQuality, out)
                        } else {
                            @Suppress("DEPRECATION")
                            bitmap.compress(Bitmap.CompressFormat.WEBP, safeQuality, out)
                        }
                    }
                    "bmp" -> writeBmp(bitmap, staged)
                    "pdf" -> writePdf(bitmap, staged)
                    "ico" -> writeIco(bitmap, staged)
                    else -> FileOutputStream(staged).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, out) }
                }
                val finalFile = AtomicWriter.commit(staged, targetDir, outName)
                if (stripMetadata) {
                    try {
                        MetadataStripper.stripInPlace(finalFile)
                    } catch (_: Exception) {
                        // Metadata removal is best-effort; the converted image is still valid.
                    }
                }
                return finalFile
            } catch (t: Throwable) {
                AtomicWriter.discard(staged)
                throw t
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun replace(previous: Bitmap, next: Bitmap): Bitmap {
        if (next !== previous && !previous.isRecycled) previous.recycle()
        return next
    }

    private fun readExifRotation(context: Context, input: FileItem): Float {
        return try {
            context.contentResolver.openInputStream(input.uri)?.use { stream ->
                val orientation = ExifInterface(stream)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) {
            0f
        }
    }

    private fun decode(input: FileItem, context: Context): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(input.uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_DECODE_DIMENSION ||
            bounds.outHeight / (sampleSize * 2) >= MAX_DECODE_DIMENSION
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(input.uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IllegalStateException("Cannot decode image: ${input.name}")
    }

    private fun cropToRatio(source: Bitmap, ratio: String): Bitmap {
        val parts = ratio.split(':', 'x', '/')
        val rw = parts.getOrNull(0)?.trim()?.toFloatOrNull() ?: return source
        val rh = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: return source
        if (rw <= 0f || rh <= 0f) return source
        val target = rw / rh
        val current = source.width.toFloat() / source.height
        val (w, h) = when {
            current > target -> (source.height * target).toInt() to source.height
            else -> source.width to (source.width / target).toInt()
        }
        if (w <= 0 || h <= 0) return source
        val x = (source.width - w) / 2
        val y = (source.height - h) / 2
        return replace(source, Bitmap.createBitmap(source, x, y, w, h))
    }

    private fun applyGrayscale(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(result).drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun applyAutoEnhance(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
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
        Canvas(result).drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun applySharpen(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val srcPixels = IntArray(width * height)
        val dstPixels = IntArray(width * height)
        source.getPixels(srcPixels, 0, width, 0, 0, width, height)

        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val idx = row + x
                val center = srcPixels[idx]
                val a = (center ushr 24) and 0xFF
                val r = (5 * ((center ushr 16) and 0xFF) - ((srcPixels[idx - width] ushr 16) and 0xFF) -
                    ((srcPixels[idx + width] ushr 16) and 0xFF) - ((srcPixels[idx - 1] ushr 16) and 0xFF) -
                    ((srcPixels[idx + 1] ushr 16) and 0xFF)).coerceIn(0, 255)
                val g = (5 * ((center ushr 8) and 0xFF) - ((srcPixels[idx - width] ushr 8) and 0xFF) -
                    ((srcPixels[idx + width] ushr 8) and 0xFF) - ((srcPixels[idx - 1] ushr 8) and 0xFF) -
                    ((srcPixels[idx + 1] ushr 8) and 0xFF)).coerceIn(0, 255)
                val b = (5 * (center and 0xFF) - (srcPixels[idx - width] and 0xFF) -
                    (srcPixels[idx + width] and 0xFF) - (srcPixels[idx - 1] and 0xFF) -
                    (srcPixels[idx + 1] and 0xFF)).coerceIn(0, 255)
                dstPixels[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        System.arraycopy(srcPixels, 0, dstPixels, 0, width)
        System.arraycopy(srcPixels, (height - 1) * width, dstPixels, (height - 1) * width, width)
        for (y in 0 until height) {
            dstPixels[y * width] = srcPixels[y * width]
            dstPixels[y * width + (width - 1)] = srcPixels[y * width + (width - 1)]
        }
        return Bitmap.createBitmap(dstPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun writePdf(bitmap: Bitmap, outFile: File) {
        val pdfDoc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
            val page = pdfDoc.startPage(pageInfo)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdfDoc.finishPage(page)
            FileOutputStream(outFile).use { out -> pdfDoc.writeTo(out) }
        } finally {
            pdfDoc.close()
        }
    }

    private fun writeIco(bitmap: Bitmap, outFile: File) {
        val side = 256.coerceAtMost(minOf(bitmap.width, bitmap.height))
        val scaled = Bitmap.createScaledBitmap(bitmap, side, side, true)
        try {
            val pngStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, pngStream)
            val pngBytes = pngStream.toByteArray()
            FileOutputStream(outFile).use { out ->
                val header = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                header.putShort(0)
                header.putShort(1)
                header.putShort(1)
                out.write(header.array())

                val entry = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                entry.put((side % 256).toByte())
                entry.put((side % 256).toByte())
                entry.put(0)
                entry.put(0)
                entry.putShort(1)
                entry.putShort(32)
                entry.putInt(pngBytes.size)
                entry.putInt(22)
                out.write(entry.array())
                out.write(pngBytes)
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
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
            header.put(0x42.toByte())
            header.put(0x4D.toByte())
            header.putInt(fileSize)
            header.putShort(0)
            header.putShort(0)
            header.putInt(54)
            header.putInt(40)
            header.putInt(width)
            header.putInt(height)
            header.putShort(1)
            header.putShort(24)
            header.putInt(0)
            header.putInt(imageSize)
            header.putInt(2835)
            header.putInt(2835)
            header.putInt(0)
            header.putInt(0)
            bos.write(header.array())

            val rowPixels = IntArray(width)
            val rowBytes = ByteArray(rowSize)
            for (y in height - 1 downTo 0) {
                bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
                var byteIdx = 0
                for (x in 0 until width) {
                    val color = rowPixels[x]
                    rowBytes[byteIdx++] = (color and 0xFF).toByte()
                    rowBytes[byteIdx++] = ((color ushr 8) and 0xFF).toByte()
                    rowBytes[byteIdx++] = ((color ushr 16) and 0xFF).toByte()
                }
                for (p in 0 until rowPadding) rowBytes[byteIdx++] = 0
                bos.write(rowBytes, 0, byteIdx)
            }
            bos.flush()
        }
    }
}
