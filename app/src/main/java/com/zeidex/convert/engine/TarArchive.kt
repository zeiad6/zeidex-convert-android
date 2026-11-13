package com.zeidex.convert.engine

import java.io.EOFException
import java.io.InputStream
import java.util.Locale

/**
 * Read-only ustar/pax/GNU tar reader, enough for extracting archives on-device.
 *
 * Symlinks, hard links and device nodes are skipped rather than recreated: an archive must
 * never be able to place a link that later writes outside the destination folder.
 */
object TarArchive {

    data class Entry(val name: String, val isDirectory: Boolean, val size: Long, val type: Char)

    private const val BLOCK = 512

    /**
     * Calls [onEntry] for every regular file or directory, then copies exactly `size` bytes.
     * The callback receives the entry and an [InputStream] limited to that entry.
     */
    fun read(input: InputStream, onEntry: (Entry, InputStream) -> Unit) {
        var pendingLongName: String? = null
        while (true) {
            val header = readBlock(input) ?: return
            if (header.all { it == 0.toByte() }) {
                // End-of-archive marker; a second zero block normally follows.
                return
            }
            verifyChecksum(header)
            val rawName = field(header, 0, 100)
            val prefix = field(header, 345, 155)
            val type = header[156].toInt().toChar()
            val size = parseNumber(header, 124, 12)
            val name = pendingLongName ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            pendingLongName = null

            // Metadata headers carry a payload of their own, padded like any other entry;
            // their content describes the entry that follows.
            if (type == 'L') {
                pendingLongName = readPayload(input, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                skipPadding(input, size)
                continue
            }
            if (type == 'K' || type == 'x' || type == 'g') {
                val payload = readPayload(input, size)
                // pax: honour a path= record, ignore the rest.
                if (type == 'x' || type == 'g') {
                    payload.toString(Charsets.UTF_8).lineSequence()
                        .firstOrNull { it.startsWith("path=") }
                        ?.let { pendingLongName = it.removePrefix("path=") }
                }
                skipPadding(input, size)
                continue
            }

            val isDirectory = type == '5' || name.endsWith("/")
            val entry = Entry(name, isDirectory, size, type)
            if (type == '0' || type == '\u0000' || type == '7') {
                val payload = LimitedInputStream(input, size)
                onEntry(entry, payload)
                payload.drain()
            } else if (isDirectory) {
                onEntry(entry, ByteArray(0).inputStream())
            } else {
                // symlink / hardlink / device: skip payload, never extract.
                skipFully(input, size)
            }
            skipPadding(input, size)
        }
    }

    private fun readBlock(input: InputStream): ByteArray? {
        val block = ByteArray(BLOCK)
        var read = 0
        while (read < BLOCK) {
            val n = input.read(block, read, BLOCK - read)
            if (n < 0) return if (read == 0) null else throw EOFException("Truncated tar header")
            read += n
        }
        return block
    }

    private fun verifyChecksum(header: ByteArray) {
        val stored = parseNumber(header, 148, 8)
        var sum = 0L
        for (i in header.indices) {
            sum += if (i in 148..155) 0x20 else (header[i].toInt() and 0xFF)
        }
        // Some old writers store a signed sum; accept either when a real checksum exists.
        if (stored != 0L && stored != sum) {
            throw IllegalStateException("Corrupt tar header (checksum mismatch)")
        }
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: (offset + length)
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun parseNumber(header: ByteArray, offset: Int, length: Int): Long {
        // GNU base-256 encoding for sizes above 8 GiB: high bit of the first byte is set.
        if (header[offset].toInt() and 0x80 != 0) {
            var value = (header[offset].toInt() and 0x7F).toLong()
            for (i in 1 until length) value = (value shl 8) or (header[offset + i].toLong() and 0xFF)
            return value
        }
        val text = String(header, offset, length, Charsets.US_ASCII)
            .trim { it == ' ' || it == '\u0000' }
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8) ?: 0
    }

    private fun readPayload(input: InputStream, size: Long): ByteArray {
        if (size > 8L * 1024 * 1024) throw IllegalStateException("Tar metadata entry too large")
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) throw EOFException("Truncated tar entry")
            read += n
        }
        return bytes
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = (BLOCK - (size % BLOCK)) % BLOCK
        if (pad > 0) skipFully(input, pad)
    }

    private fun skipFully(input: InputStream, size: Long) {
        var remaining = size
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0) {
            val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n < 0) return
            remaining -= n
        }
    }

    /** Bounds a single entry so a lying size field cannot over-read the container. */
    private class LimitedInputStream(private val inner: InputStream, private val limit: Long) : InputStream() {
        private var remaining = limit
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = inner.read()
            if (value >= 0) remaining--
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val n = inner.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
        fun drain() {
            val scratch = ByteArray(8 * 1024)
            while (remaining > 0) {
                val n = inner.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (n < 0) break
                remaining -= n
            }
        }
    }
}
