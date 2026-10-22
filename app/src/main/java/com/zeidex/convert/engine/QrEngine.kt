package com.zeidex.convert.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.zeidex.convert.util.StorageManager
import java.io.File
import java.io.FileOutputStream

object QrEngine {

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
        val file = File(targetDir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        StorageManager.saveToPhoneStorage(context, file, specialName = "QR")
        return file
    }
}
