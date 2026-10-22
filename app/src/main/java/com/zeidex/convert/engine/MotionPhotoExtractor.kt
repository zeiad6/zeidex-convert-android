package com.zeidex.convert.engine

import android.content.Context
import com.zeidex.convert.model.FileItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MotionPhotoExtractor {

    fun extractMotionVideo(context: Context, input: FileItem, outputDir: File): File {
        val inputStream = context.contentResolver.openInputStream(input.uri)
            ?: throw IllegalStateException("Cannot open ${input.name}")

        val tempFile = File(outputDir, "temp_motion_input.tmp")
        tempFile.outputStream().use { out -> inputStream.copyTo(out) }
        inputStream.close()

        val fileLength = tempFile.length()
        val fis = FileInputStream(tempFile)

        // Read first 2 bytes to check for JPEG SOI
        val soi = ByteArray(2)
        if (fis.read(soi) != 2 || soi[0] != 0xFF.toByte() || soi[1] != 0xD8.toByte()) {
            fis.close()
            tempFile.delete()
            throw IllegalArgumentException("File is not a valid JPEG")
        }

        var videoLength: Long = -1
        val maxMetadata = (8 * 1024 * 1024).toLong().coerceAtMost(fileLength)
        var curPos: Long = 2

        while (curPos < maxMetadata) {
            val markerBuf = ByteArray(2)
            if (fis.read(markerBuf) != 2) break
            if (markerBuf[0] != 0xFF.toByte()) break
            val marker = markerBuf[1].toInt() and 0xFF

            if (marker == 0xDA || marker == 0xD9) {
                // SOS or EOI
                break
            }
            if (marker == 0xFF) {
                curPos++
                continue
            }

            val lenBuf = ByteArray(2)
            if (fis.read(lenBuf) != 2) break
            val segLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
            val payloadLen = segLen - 2

            if (marker == 0xE1 && payloadLen > 0) {
                // APP1 segment: check for XMP
                val payload = ByteArray(payloadLen)
                fis.read(payload)
                val payloadString = String(payload, Charsets.UTF_8)
                if (payloadString.contains("http://ns.adobe.com/xap/1.0/")) {
                    val len = parseXmpVideoLength(payloadString)
                    if (len != null && len > 0 && len < fileLength) {
                        videoLength = len
                        break
                    }
                }
            } else {
                fis.skip(payloadLen.toLong())
            }
            curPos += 2 + segLen
        }

        fis.close()

        if (videoLength <= 0) {
            tempFile.delete()
            throw IllegalStateException("No Motion Photo video stream found in ${input.name}")
        }

        val videoOffset = fileLength - videoLength
        val baseName = input.name.substringBeforeLast('.')
        val outFile = File(outputDir, "${baseName}_motion.mp4")

        FileInputStream(tempFile).use { inputFis ->
            inputFis.skip(videoOffset)
            FileOutputStream(outFile).use { fos ->
                val buffer = ByteArray(64 * 1024)
                var remaining = videoLength
                while (remaining > 0) {
                    val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                    val read = inputFis.read(buffer, 0, toRead)
                    if (read <= 0) break
                    fos.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }

        tempFile.delete()
        return outFile
    }

    private fun parseXmpVideoLength(xmp: String): Long? {
        val isMotion = xmp.contains("MotionPhoto=\"1\"") ||
                xmp.contains("MotionPhoto>1<") ||
                xmp.contains("MicroVideo=\"1\"") ||
                xmp.contains("MicroVideo>1<")

        if (!isMotion) return null

        // Try MicroVideoOffset
        val mvoAttr = "MicroVideoOffset=\""
        val mvoIdx = xmp.indexOf(mvoAttr)
        if (mvoIdx != -1) {
            val endIdx = xmp.indexOf("\"", mvoIdx + mvoAttr.length)
            if (endIdx != -1) {
                return xmp.substring(mvoIdx + mvoAttr.length, endIdx).trim().toLongOrNull()
            }
        }

        // Try Item:Length
        val lenAttr = "Item:Length=\""
        val lenIdx = xmp.indexOf(lenAttr)
        if (lenIdx != -1) {
            val endIdx = xmp.indexOf("\"", lenIdx + lenAttr.length)
            if (endIdx != -1) {
                return xmp.substring(lenIdx + lenAttr.length, endIdx).trim().toLongOrNull()
            }
        }

        // Generic Length attribute
        val lastLengthIdx = xmp.lastIndexOf("Length=\"")
        if (lastLengthIdx != -1) {
            val endIdx = xmp.indexOf("\"", lastLengthIdx + 8)
            if (endIdx != -1) {
                return xmp.substring(lastLengthIdx + 8, endIdx).trim().toLongOrNull()
            }
        }

        return null
    }
}
