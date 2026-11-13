package com.zeidex.convert.engine

import com.zeidex.convert.util.AtomicWriter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Receives files that the desktop app sends with "Send to phone" (LocalDrop).
 *
 * The desktop shows a QR code holding `zeidexdrop://v1?h=<ip>&p=<port>&k=<64 hex>&n=<name>`.
 * The 256-bit key exists only in that code, so nobody else on the Wi-Fi can read or alter the
 * transfer. Frames are a big-endian `u32` length plus AES-256-GCM ciphertext and tag; nonces
 * are implicit (`[direction, 0, 0, 0, counter as u64]`), so a replayed, dropped or reordered
 * frame fails authentication. The protocol is specified in `crates/localdrop/src/lib.rs`, and
 * a shared test vector keeps this file and the Rust sender byte-identical.
 */
object LocalDropReceiver {

    const val SCHEME = "zeidexdrop"
    private const val MAGIC = "ZDROP/1"
    const val KEY_LEN = 32
    private const val TAG_BITS = 128
    private const val TAG_LEN = TAG_BITS / 8
    private const val NONCE_LEN = 12
    private const val MAX_PLAIN = 1 shl 20
    const val MAX_FILES = 2_000
    internal const val TO_RECEIVER: Byte = 1
    internal const val TO_SENDER: Byte = 2
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val IO_TIMEOUT_MS = 60_000
    private const val MAX_NAME = 200
    private const val MAX_DEVICE_NAME = 40
    private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Where to connect and the session key, from a scanned code. */
    class Link(val host: InetAddress, val port: Int, val key: ByteArray, val name: String) {
        override fun toString(): String = "Link(${host.hostAddress}:$port, $name)"

        companion object {
            fun parse(uri: String): Link {
                val query = uri.trim().removePrefix("$SCHEME://v1?")
                if (query == uri.trim()) throw IOException("Not a LocalDrop code")
                val fields = query.split('&').mapNotNull { pair ->
                    val eq = pair.indexOf('=')
                    if (eq <= 0) null else pair.substring(0, eq) to pair.substring(eq + 1)
                }.toMap()
                val hostText = fields["h"] ?: throw IOException("The LocalDrop code is incomplete")
                // Only IP literals: a host name would trigger a DNS lookup chosen by the code.
                if (!IPV4_LITERAL.matches(hostText) && !hostText.contains(':')) {
                    throw IOException("The LocalDrop code is incomplete")
                }
                val host = try {
                    InetAddress.getByName(hostText)
                } catch (e: Exception) {
                    throw IOException("The LocalDrop code is incomplete")
                }
                val port = fields["p"]?.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw IOException("The LocalDrop code is incomplete")
                val key = fields["k"]?.let(::unhex)?.takeIf { it.size == KEY_LEN }
                    ?: throw IOException("The LocalDrop code is incomplete")
                if (!isLan(host)) throw IOException("LocalDrop only connects to devices on the local network")
                return Link(host, port, key, cleanDeviceName(fields["n"].orEmpty()))
            }
        }
    }

    data class Incoming(val name: String, val size: Long, val sha256: String)

    /** Private, link-local and loopback addresses only. */
    fun isLan(ip: InetAddress): Boolean {
        if (ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress) return true
        // IPv6 unique local addresses (fc00::/7) are the IPv6 private range.
        return ip is Inet6Address && (ip.address[0].toInt() and 0xfe) == 0xfc
    }

    fun cleanDeviceName(raw: String): String =
        raw.map { c -> if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in "._-") c else '-' }
            .joinToString("")
            .take(MAX_DEVICE_NAME)

    /** Same rule as the Rust sender: a name, never a path, valid on every system. */
    fun cleanFileName(raw: String): String {
        val mapped = raw.map { c -> if (Character.isISOControl(c) || c in "/\\:*?\"<>|") '_' else c }
            .joinToString("")
        var name = mapped.trim().trimEnd('.', ' ')
        if (name.codePointCount(0, name.length) > MAX_NAME) {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_NAME))
        }
        return if (name.isEmpty() || name == "." || name == "..") "file" else name
    }

    /** Parses `ZDROP/1 OFFER`, refusing anything that does not add up. */
    fun parseOffer(text: String): List<Incoming> {
        fun bad(): Nothing = throw IOException("Malformed LocalDrop offer")
        val lines = text.split('\n').filter { it.isNotEmpty() }
        val head = lines.firstOrNull()?.removePrefix("$MAGIC OFFER ") ?: bad()
        if (head == lines.first()) bad()
        val parts = head.split(' ')
        if (parts.size != 2) bad()
        val count = parts[0].toIntOrNull() ?: bad()
        val total = parts[1].toLongOrNull() ?: bad()
        if (count <= 0 || count > MAX_FILES) bad()
        val files = lines.drop(1).map { line ->
            val f = line.split(' ', limit = 3)
            if (f.size != 3) bad()
            val size = f[0].toLongOrNull()?.takeIf { it >= 0 } ?: bad()
            val sha = f[1].takeIf { h -> h.length == 64 && h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }
                ?: bad()
            Incoming(cleanFileName(f[2]), size, sha.lowercase())
        }
        var sum = 0L
        for (f in files) {
            sum = Math.addExact(sum, f.size)
        }
        if (files.size != count || sum != total) bad()
        return files
    }

    /** Connects to the computer in [link], proves the key and reads its offer. */
    fun connect(link: Link, deviceName: String): Session {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(link.host, link.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IO_TIMEOUT_MS
            socket.tcpNoDelay = true
            val ch = Channel(socket, link.key, sender = false)
            ch.send("$MAGIC HELLO\n${cleanDeviceName(deviceName)}".toByteArray(Charsets.UTF_8))
            val files = parseOffer(String(ch.recv(), Charsets.UTF_8))
            return Session(socket, ch, link.name, files)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    /** An open offer: answer with [accept] or [decline]; [close] aborts at any time. */
    class Session internal constructor(
        private val socket: Socket,
        private val ch: Channel,
        val sender: String,
        val files: List<Incoming>,
    ) : Closeable {

        val totalBytes: Long get() = files.sumOf { it.size }

        fun decline() {
            ch.send("$MAGIC DECLINE".toByteArray(Charsets.UTF_8))
        }

        /**
         * Receives every file into [dest]. Each one is staged, checked against its SHA-256 and
         * committed without replacing existing files (`name 2.ext`).
         */
        fun accept(dest: File, onProgress: (received: Long, total: Long) -> Unit): List<File> {
            ch.send("$MAGIC ACCEPT".toByteArray(Charsets.UTF_8))
            val total = totalBytes
            val saved = mutableListOf<File>()
            var staged: File? = null
            try {
                var got = 0L
                for (f in files) {
                    val part = AtomicWriter.stage(dest, f.name)
                    staged = part
                    val digest = MessageDigest.getInstance("SHA-256")
                    FileOutputStream(part).use { out ->
                        var left = f.size
                        while (left > 0) {
                            val data = ch.recv()
                            if (data.isEmpty() || data.size > left) {
                                throw IOException("The computer sent more data than it announced")
                            }
                            out.write(data)
                            digest.update(data)
                            left -= data.size
                            got += data.size
                            onProgress(got, total)
                        }
                        out.fd.sync()
                    }
                    if (hex(digest.digest()) != f.sha256) {
                        throw IOException("${f.name} arrived damaged (checksum mismatch)")
                    }
                    saved += AtomicWriter.commit(part, dest, f.name)
                    staged = null
                }
                if (String(ch.recv(), Charsets.UTF_8) != "$MAGIC END") {
                    throw IOException("The computer did not finish the transfer")
                }
            } catch (e: Exception) {
                staged?.let(AtomicWriter::discard)
                runCatching { ch.send("$MAGIC FAIL ${e.message ?: e.javaClass.simpleName}".toByteArray(Charsets.UTF_8)) }
                throw e
            }
            ch.send("$MAGIC DONE ${saved.size}".toByteArray(Charsets.UTF_8))
            return saved
        }

        override fun close() {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    /** One end of the encrypted conversation (the sender side exists for the tests). */
    internal class Channel(socket: Socket, key: ByteArray, sender: Boolean) {
        private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val output = BufferedOutputStream(socket.getOutputStream())
        private val keySpec = SecretKeySpec(key, "AES")
        private val sendDir = if (sender) TO_RECEIVER else TO_SENDER
        private val recvDir = if (sender) TO_SENDER else TO_RECEIVER
        private var sent = 0L
        private var received = 0L

        fun send(plain: ByteArray) {
            output.write(seal(keySpec, sendDir, sent++, plain))
            output.flush()
        }

        fun recv(): ByteArray {
            val len = input.readInt()
            if (len < TAG_LEN || len > MAX_PLAIN + TAG_LEN) throw IOException("Malformed LocalDrop frame")
            val buf = ByteArray(len)
            input.readFully(buf)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce(recvDir, received)))
            val plain = try {
                cipher.doFinal(buf)
            } catch (e: AEADBadTagException) {
                throw IOException("The computer does not hold this code's key, or the data was altered")
            }
            received++
            return plain
        }
    }

    internal fun nonce(dir: Byte, counter: Long): ByteArray =
        ByteBuffer.allocate(NONCE_LEN).put(dir).put(0).put(0).put(0).putLong(counter).array()

    /** One frame on the wire: length prefix, ciphertext, tag. */
    internal fun seal(key: SecretKeySpec, dir: Byte, counter: Long, plain: ByteArray): ByteArray {
        if (plain.size > MAX_PLAIN) throw IOException("LocalDrop frame too large")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(dir, counter)))
        val sealed = cipher.doFinal(plain)
        return ByteBuffer.allocate(4 + sealed.size).putInt(sealed.size).put(sealed).array()
    }

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return try {
            ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
