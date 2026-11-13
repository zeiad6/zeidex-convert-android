package com.zeidex.convert.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Removes metadata segments without re-encoding the pixels: EXIF/GPS, XMP, IPTC and comments.
 *
 * Re-encoding to drop metadata costs quality and time; editing the container keeps the image
 * bit-for-bit identical. Unknown formats are left untouched rather than corrupted.
 */
object MetadataStripper {

    /** Rewrites [file] in place through a sibling temp file. Returns true when bytes changed. */
    fun stripInPlace(file: File): Boolean {
        val bytes = file.readBytes()
        val cleaned = when {
            bytes.startsWith(0xFF, 0xD8) -> stripJpeg(bytes)
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> stripPng(bytes)
            bytes.size > 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> stripWebp(bytes)
            else -> return false
        }
        if (cleaned.contentEquals(bytes)) return false
        val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.meta")
        try {
            temp.writeBytes(cleaned)
            if (!temp.renameTo(file)) {
                file.writeBytes(cleaned)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        return true
    }

    private fun stripJpeg(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size)
        out.write(src, 0, 2) // SOI
        var i = 2
        while (i + 4 <= src.size) {
            if (src[i] != 0xFF.toByte()) break
            var marker = src[i + 1].toInt() and 0xFF
            // Fill bytes: FF FF ... FF marker
            while (marker == 0xFF && i + 2 < src.size) {
                i++
                marker = src[i + 1].toInt() and 0xFF
            }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                out.write(src, i, 2)
                i += 2
                continue
            }
            if (i + 4 > src.size) break
            val length = ((src[i + 2].toInt() and 0xFF) shl 8) or (src[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > src.size) {
                out.write(src, i, src.size - i)
                return out.toByteArray()
            }
            val drop = when (marker) {
                0xE1 -> true // EXIF / XMP
                0xED -> true // IPTC / Photoshop
                0xFE -> true // comment
                0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xEE, 0xEF -> true
                else -> false
            }
            if (!drop) out.write(src, i, 2 + length)
            i += 2 + length
            if (marker == 0xDA) { // start of scan: entropy-coded data follows
                out.write(src, i, src.size - i)
                return out.toByteArray()
            }
        }
        out.write(src, i, src.size - i)
        return out.toByteArray()
    }

    private fun stripPng(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size)
        out.write(src, 0, 8) // signature
        var i = 8
        while (i + 8 <= src.size) {
            val length = readInt(src, i)
            if (length < 0 || i + 12 + length > src.size) throw IOException("Corrupt PNG")
            val type = String(src, i + 4, 4, Charsets.US_ASCII)
            val keep = type !in setOf("eXIf", "tEXt", "iTXt", "zTXt", "tIME")
            if (keep) out.write(src, i, 12 + length)
            i += 12 + length
            if (type == "IEND") break
        }
        if (i < src.size) out.write(src, i, src.size - i)
        return out.toByteArray()
    }

    private fun stripWebp(src: ByteArray): ByteArray {
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        var i = 12
        while (i + 8 <= src.size) {
            val type = String(src, i, 4, Charsets.US_ASCII)
            val size = readIntLe(src, i + 4)
            if (size < 0 || i + 8 + size > src.size) break
            val payload = src.copyOfRange(i + 8, i + 8 + size)
            chunks.add(type to payload)
            i += 8 + size + (size and 1)
        }
        if (chunks.isEmpty()) return src
        val kept = chunks.filter { (type, _) -> type != "EXIF" && type != "XMP " }
        if (kept.size == chunks.size) return src
        val body = ByteArrayOutputStream(src.size)
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        for ((type, payload) in kept) {
            if (type == "VP8X" && payload.size >= 4) {
                // Bits 3 (EXIF) and 2 (XMP) of the flags byte advertise what we just removed.
                val flags = payload[0].toInt() and 0xFF
                payload[0] = (flags and 0b1110_0011).toByte()
            }
            body.write(type.toByteArray(Charsets.US_ASCII))
            body.write(intLe(payload.size))
            body.write(payload)
            if (payload.size and 1 == 1) body.write(0)
        }
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream(bodyBytes.size + 8)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intLe(bodyBytes.size))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun readInt(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 24) or ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or (src[offset + 3].toInt() and 0xFF)

    private fun readIntLe(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or ((src[offset + 1].toInt() and 0xFF) shl 8) or
            ((src[offset + 2].toInt() and 0xFF) shl 16) or ((src[offset + 3].toInt() and 0xFF) shl 24)

    private fun intLe(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        return prefix.withIndex().all { (i, b) -> this[i] == b.toByte() }
    }
}
