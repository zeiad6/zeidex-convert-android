package com.zeidex.convert.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.zeidex.convert.util.AtomicWriter
import com.zeidex.convert.util.StorageManager
import java.io.File

object QrEngine {

    /** Longest side a photo is decoded at: plenty for a QR code, small enough for memory. */
    private const val DECODE_MAX_SIDE = 1600

    /** The text of the QR code in a photo, or null when none is found. */
    fun decodeFromFile(file: File): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > DECODE_MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val hints = mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            )
            return try {
                MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (_: NotFoundException) {
                null
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun generateQrBitmap(content: String, width: Int = 512, height: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    fun saveQrToFile(context: Context, content: String, fileName: String = "qrcode.png"): File {
        val bitmap = generateQrBitmap(content)
        val targetDir = StorageManager.getSubfolder(context, specialName = "QR")
        // Same rule as every other output: stage, fsync, rename, never overwrite a QR the user
        // already saved (`qrcode 2.png`).
        val file = AtomicWriter.write(targetDir, fileName) { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        StorageManager.saveToPhoneStorage(context, file, specialName = "QR")
        return file
    }
}
