package com.zeidex.convert.engine

import android.content.Context
import com.zeidex.convert.model.FileItem
import com.zeidex.convert.service.ConversionForegroundService
import com.zeidex.convert.util.AtomicWriter
import com.zeidex.convert.util.StorageManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

object MotionPhotoExtractor {

    private val FTYP_BYTES = byteArrayOf(0x66, 0x74, 0x79, 0x70) // "ftyp"
    private val ZIP_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val KNOWN_BOX_TYPES = listOf("ftyp", "moov", "mdat", "wide", "free", "skip")

    /**
     * Extracts motion/live video stream from Google Pixel, Samsung Galaxy, Xiaomi, Motorola
     * Motion Photos, and Apple Live Photos (.livp / HEIC / MOV).
     * Automatically saves to Download/ZeidexConvert/LivePhotos/ and posts a system notification.
     */
    fun extractMotionVideo(context: Context, input: FileItem, outputDir: File): File {
        val inputStream = context.contentResolver.openInputStream(input.uri)
            ?: throw IllegalStateException("Cannot access input file: ${input.name}")

        val tempFile = File(context.cacheDir, "temp_motion_${System.currentTimeMillis()}.tmp")
        tempFile.outputStream().use { out -> inputStream.copyTo(out) }
        inputStream.close()

        val fileLength = tempFile.length()
        if (fileLength < 64) {
            tempFile.delete()
            throw IllegalArgumentException("File is too small to contain a motion video")
        }

        val baseName = input.name.substringBeforeLast('.')
        val targetDir = StorageManager.getSubfolder(context, specialName = "LivePhotos")
        val outFile = AtomicWriter.stage(targetDir, "${baseName}_motion.mp4")

        try {
            // Strategy 0: Direct MOV / MP4 file (Apple Live Photo video track)
            if (input.name.endsWith(".mov", ignoreCase = true) || input.name.endsWith(".mp4", ignoreCase = true)) {
                tempFile.copyTo(outFile, overwrite = true)
                return finalizeExtractedVideo(context, outFile, "${baseName}_motion.mp4")
            }

            // Strategy 1: Apple .livp or ZIP Archive containing .mov / .mp4
            if (isZipOrLivp(tempFile, input.name)) {
                val extracted = extractFromZip(tempFile, outFile)
                if (extracted) {
                    return finalizeExtractedVideo(context, outFile, "${baseName}_motion.mp4")
                }
            }

            // Strategy 2: Fast XMP scan across first 4MB (Google Pixel, Samsung Galaxy, Xiaomi, Motorola)
            val fastXmpOffset = findXmpMotionPhotoFast(tempFile)
            if (fastXmpOffset != null && fastXmpOffset.first > 0 && fastXmpOffset.second > 0) {
                extractRangeToFile(tempFile, fastXmpOffset.first, fastXmpOffset.second, outFile)
                if (outFile.exists() && outFile.length() > 1024) {
                    return finalizeExtractedVideo(context, outFile, "${baseName}_motion.mp4")
                }
            }

            // Strategy 3: Samsung MotionPhoto_Data Trailer
            val samsungOffset = findSamsungMotionPhotoOffset(tempFile)
            if (samsungOffset != null && samsungOffset.first > 0 && samsungOffset.second > 0) {
                extractRangeToFile(tempFile, samsungOffset.first, samsungOffset.second, outFile)
                if (outFile.exists() && outFile.length() > 1024) {
                    return finalizeExtractedVideo(context, outFile, "${baseName}_motion.mp4")
                }
            }

            // Strategy 4: JPEG Marker-based XMP scan (fallback)
            val xmpOffset = findXmpMotionPhotoOffset(tempFile)
            if (xmpOffset != null && xmpOffset.first > 0 && xmpOffset.second > 0) {
                extractRangeToFile(tempFile, xmpOffset.first, xmpOffset.second, outFile)
                if (outFile.exists() && outFile.length() > 1024) {
                    return finalizeExtractedVideo(context, outFile, "${baseName}_motion.mp4")
                }
            }

            // Strategy 5: Universal Reverse Binary Scan for 'ftyp' box
            val ftypOffset = findFtypOffsetReverse(tempFile)
            if (ftypOffset != null && ftypOffset.first > 0 && ftypOffset.second > 0) {
                extractRangeToFile(tempFile, ftypOffset.first, ftypOffset.second, outFile)
                if (outFile.exists() && outFile.length() > 1024) {
                    return finalizeExtractedVideo(context, outFile, "${baseName}_motion.mp4")
                }
            }

            throw IllegalStateException(
                "No embedded Live Photo or Motion Photo video stream found in ${input.name}.\n" +
                "Make sure the photo was captured with Motion Photo / Live Photo enabled."
            )
        } finally {
            tempFile.delete()
            // Never leave a partial MP4 behind when a strategy failed.
            if (outFile.exists()) AtomicWriter.discard(outFile)
        }
    }

    private fun finalizeExtractedVideo(context: Context, staged: File, name: String): File {
        if (!isPlausibleMp4(staged)) {
            AtomicWriter.discard(staged)
            throw IllegalStateException(
                "The embedded video is not a valid MP4. The photo may use an unsupported motion format."
            )
        }
        val saved = AtomicWriter.commit(staged, staged.parentFile ?: context.cacheDir, name)
        StorageManager.scanDirectory(context, saved.parentFile ?: context.cacheDir)
        return saved
    }

    /** A real MP4 starts with a size-prefixed box and carries `ftyp` in the first box. */
    private fun isPlausibleMp4(file: File): Boolean {
        if (!file.exists() || file.length() < 64) return false
        val head = ByteArray(12)
        FileInputStream(file).use { input -> if (input.read(head) != head.size) return false }
        val size = ((head[0].toLong() and 0xFF) shl 24) or ((head[1].toLong() and 0xFF) shl 16) or
            ((head[2].toLong() and 0xFF) shl 8) or (head[3].toLong() and 0xFF)
        val type = String(head, 4, 4, Charsets.US_ASCII)
        return size in 8..file.length() && type in KNOWN_BOX_TYPES
    }

    private fun isZipOrLivp(file: File, fileName: String): Boolean {
        if (fileName.endsWith(".livp", ignoreCase = true) || fileName.endsWith(".zip", ignoreCase = true)) {
            return true
        }
        val header = ByteArray(4)
        FileInputStream(file).use { fis ->
            if (fis.read(header) == 4) {
                return header.contentEquals(ZIP_HEADER)
            }
        }
        return false
    }

    private fun extractFromZip(zipFile: File, destFile: File): Boolean {
        FileInputStream(zipFile).use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (name.endsWith(".mov") || name.endsWith(".mp4")) {
                        FileOutputStream(destFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        return true
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return false
    }

    /**
     * Fast search for XMP MicroVideoOffset or Item:Length across first 4MB of ANY container (JPEG, HEIC, WebP)
     */
    private fun findXmpMotionPhotoFast(file: File): Pair<Long, Long>? {
        val fileLength = file.length()
        val readSize = (4 * 1024 * 1024L).coerceAtMost(fileLength).toInt()
        val buffer = ByteArray(readSize)

        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(buffer)
            val headerText = String(buffer, Charsets.ISO_8859_1)

            val videoLen = parseXmpVideoLength(headerText)
            if (videoLen != null && videoLen in 1024 until fileLength) {
                val videoOffset = fileLength - videoLen
                if (verifyMediaBoxAt(raf, videoOffset)) {
                    return Pair(videoOffset, videoLen)
                }
                // Try as direct offset from start
                if (verifyMediaBoxAt(raf, videoLen)) {
                    return Pair(videoLen, fileLength - videoLen)
                }
            }
        }
        return null
    }

    /**
     * Samsung stores "MotionPhoto_Data" in the last 128 bytes of the file, followed by
     * a 4-byte big-endian integer giving the video length.
     */
    private fun findSamsungMotionPhotoOffset(file: File): Pair<Long, Long>? {
        val len = file.length()
        if (len < 100) return null

        RandomAccessFile(file, "r").use { raf ->
            val trailerSize = 256.coerceAtMost(len.toInt())
            val trailer = ByteArray(trailerSize)
            raf.seek(len - trailerSize)
            raf.readFully(trailer)

            val tag = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
            val tagPos = indexOfBytes(trailer, tag)
            if (tagPos != -1) {
                val intPos = tagPos + tag.size
                if (intPos + 4 <= trailer.size) {
                    val videoLen = ((trailer[intPos].toLong() and 0xFF) shl 24) or
                            ((trailer[intPos + 1].toLong() and 0xFF) shl 16) or
                            ((trailer[intPos + 2].toLong() and 0xFF) shl 8) or
                            (trailer[intPos + 3].toLong() and 0xFF)

                    if (videoLen in 1024 until len) {
                        val videoOffset = len - videoLen
                        if (verifyMediaBoxAt(raf, videoOffset)) {
                            return Pair(videoOffset, videoLen)
                        }
                        val altOffset = len - trailerSize + tagPos - videoLen
                        if (altOffset >= 0 && verifyMediaBoxAt(raf, altOffset)) {
                            return Pair(altOffset, videoLen)
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Google Pixel and Android XMP parser: MicroVideoOffset or Item:Length in APP1 markers
     */
    private fun findXmpMotionPhotoOffset(file: File): Pair<Long, Long>? {
        val fileLength = file.length()
        val maxScan = 16 * 1024 * 1024L.coerceAtMost(fileLength)

        RandomAccessFile(file, "r").use { raf ->
            var pos = 2L
            raf.seek(pos)

            while (pos < maxScan) {
                val markerBuf = ByteArray(2)
                if (raf.read(markerBuf) != 2) break
                if (markerBuf[0] != 0xFF.toByte()) break
                val marker = markerBuf[1].toInt() and 0xFF

                if (marker == 0xDA || marker == 0xD9) break // SOS or EOI
                if (marker == 0xFF) {
                    pos++
                    raf.seek(pos)
                    continue
                }

                val lenBuf = ByteArray(2)
                if (raf.read(lenBuf) != 2) break
                val segLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                val payloadLen = segLen - 2

                if (marker == 0xE1 && payloadLen > 0) {
                    val payload = ByteArray(payloadLen.coerceAtMost(256 * 1024))
                    val read = raf.read(payload)
                    val str = String(payload, 0, read, Charsets.UTF_8)
                    if (str.contains("http://ns.adobe.com/xap/1.0/") || str.contains("GCamera")) {
                        val videoLen = parseXmpVideoLength(str)
                        if (videoLen != null && videoLen in 1024 until fileLength) {
                            val videoOffset = fileLength - videoLen
                            if (verifyMediaBoxAt(raf, videoOffset)) {
                                return Pair(videoOffset, videoLen)
                            }
                        }
                    }
                    if (payloadLen > payload.size) {
                        raf.seek(pos + 2 + segLen)
                    }
                } else {
                    raf.seek(pos + 2 + segLen)
                }
                pos += 2 + segLen
            }
        }
        return null
    }

    private fun parseXmpVideoLength(xmp: String): Long? {
        val patterns = listOf(
            "MicroVideoOffset=\"",
            "MicroVideoOffset>",
            "Item:Length=\"",
            "Item:Length>",
            "Length=\"",
            "MotionPhoto_DataLength=\""
        )

        for (pattern in patterns) {
            val idx = xmp.indexOf(pattern)
            if (idx != -1) {
                val start = idx + pattern.length
                val terminator = if (pattern.endsWith(">")) '<' else '"'
                val end = xmp.indexOf(terminator, start)
                if (end != -1) {
                    val numStr = xmp.substring(start, end).trim()
                    numStr.toLongOrNull()?.let { if (it > 1024) return it }
                }
            }
        }
        return null
    }

    /**
     * Universal Reverse Scanner: Scans from EOF backward looking for 'ftyp' box
     * Works for ALL Google, Samsung, Xiaomi, Motorola, and custom motion photos!
     */
    private fun findFtypOffsetReverse(file: File): Pair<Long, Long>? {
        val fileLength = file.length()
        if (fileLength < 1024) return null

        RandomAccessFile(file, "r").use { raf ->
            val scanLimit = (64 * 1024 * 1024L).coerceAtMost(fileLength - 16)
            val chunkSize = 64 * 1024
            val buffer = ByteArray(chunkSize + 8)

            var searchEnd = fileLength
            var searchStart = (searchEnd - chunkSize).coerceAtLeast(1024)

            while (searchEnd > (fileLength - scanLimit)) {
                val bytesToRead = (searchEnd - searchStart).toInt()
                raf.seek(searchStart)
                raf.readFully(buffer, 0, bytesToRead)

                for (i in (bytesToRead - 8) downTo 0) {
                    if (buffer[i + 4] == FTYP_BYTES[0] &&
                        buffer[i + 5] == FTYP_BYTES[1] &&
                        buffer[i + 6] == FTYP_BYTES[2] &&
                        buffer[i + 7] == FTYP_BYTES[3]) {

                        val boxSize = ((buffer[i].toInt() and 0xFF) shl 24) or
                                ((buffer[i + 1].toInt() and 0xFF) shl 16) or
                                ((buffer[i + 2].toInt() and 0xFF) shl 8) or
                                (buffer[i + 3].toInt() and 0xFF)

                        if (boxSize in 16..256) {
                            val candidateOffset = searchStart + i
                            if (candidateOffset > 0 && candidateOffset < fileLength - 100) {
                                val videoLen = fileLength - candidateOffset
                                return Pair(candidateOffset, videoLen)
                            }
                        }
                    }
                }

                if (searchStart <= 1024) break
                searchEnd = searchStart + 8
                searchStart = (searchEnd - chunkSize).coerceAtLeast(1024)
            }
        }
        return null
    }

    private fun verifyMediaBoxAt(raf: RandomAccessFile, offset: Long): Boolean {
        if (offset < 0 || offset > raf.length() - 8) return false
        val buf = ByteArray(8)
        raf.seek(offset)
        if (raf.read(buf) != 8) return false
        val boxType = String(buf, 4, 4, Charsets.US_ASCII)
        return boxType in KNOWN_BOX_TYPES
    }

    private fun extractRangeToFile(srcFile: File, offset: Long, length: Long, destFile: File) {
        RandomAccessFile(srcFile, "r").use { raf ->
            raf.seek(offset)
            FileOutputStream(destFile).use { fos ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break
                    fos.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun indexOfBytes(source: ByteArray, target: ByteArray): Int {
        for (i in 0..source.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
