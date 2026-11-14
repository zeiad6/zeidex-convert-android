package com.zeidex.convert.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class LocalDropReceiverTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "zeidex-drop-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Same frame as `shared_test_vector` in crates/localdrop: the two sides agree on the wire. */
    @Test
    fun matchesTheRustSender() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val frame = LocalDropReceiver.seal(key, LocalDropReceiver.TO_RECEIVER, 0, "ZDROP/1 END".toByteArray())
        assertEquals("0000001bb868d070f480dd270f4a41b71ab30684ab4877f81d724561a00169", LocalDropReceiver.hex(frame))
    }

    @Test
    fun linksAreCheckedBeforeConnecting() {
        val key = "07".repeat(32)
        val link = LocalDropReceiver.Link.parse("zeidexdrop://v1?h=192.168.1.20&p=5050&k=$key&n=My-PC")
        assertEquals("192.168.1.20", link.host.hostAddress)
        assertEquals(5050, link.port)
        assertEquals("My-PC", link.name)
        assertTrue(!link.toString().contains("0707"))

        expectFailure { LocalDropReceiver.Link.parse("zeidexdrop://v1?h=8.8.8.8&p=5050&k=$key") }
        expectFailure { LocalDropReceiver.Link.parse("zeidexdrop://v1?h=evil.example&p=5050&k=$key") }
        expectFailure { LocalDropReceiver.Link.parse("zeidexdrop://v1?h=192.168.1.20&p=5050&k=07") }
        expectFailure { LocalDropReceiver.Link.parse("https://example.com") }
        assertTrue(LocalDropReceiver.isLan(InetAddress.getByName("fd12::1")))
        assertTrue(!LocalDropReceiver.isLan(InetAddress.getByName("2001:db8::1")))
    }

    @Test
    fun namesAndOffersMirrorTheRustRules() {
        assertEquals(".._.._etc_passwd", LocalDropReceiver.cleanFileName("../../etc/passwd"))
        assertEquals("file", LocalDropReceiver.cleanFileName(".."))
        assertEquals("a_b_c_.txt", LocalDropReceiver.cleanFileName("a:b*c?.txt"))
        assertEquals("صورة 1.jpg", LocalDropReceiver.cleanFileName("صورة 1.jpg"))
        val h = "a".repeat(64)
        assertEquals("x.txt", LocalDropReceiver.parseOffer("ZDROP/1 OFFER 1 5\n5 $h x.txt\n")[0].name)
        expectFailure { LocalDropReceiver.parseOffer("ZDROP/1 OFFER 2 5\n5 $h x.txt\n") }
        expectFailure { LocalDropReceiver.parseOffer("ZDROP/1 OFFER 1 6\n5 $h x.txt\n") }
        expectFailure { LocalDropReceiver.parseOffer("ZDROP/1 OFFER 1 5\n5 nothex x.txt\n") }
    }

    @Test
    fun receivesFilesWithoutReplacingExistingOnes() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val big = ByteArray(256 * 1024 * 2 + 999) { (it * 31 % 251).toByte() }
        File(dir, "video.bin").writeText("already here")
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val sender = thread {
            server.accept().use { s ->
                val ch = LocalDropReceiver.Channel(s, key, sender = true)
                check(String(ch.recv()).startsWith("ZDROP/1 HELLO\n"))
                val sha = LocalDropReceiver.hex(MessageDigest.getInstance("SHA-256").digest(big))
                val empty = LocalDropReceiver.hex(MessageDigest.getInstance("SHA-256").digest(ByteArray(0)))
                ch.send("ZDROP/1 OFFER 2 ${big.size}\n${big.size} $sha video.bin\n0 $empty ../empty.txt\n".toByteArray())
                check(String(ch.recv()) == "ZDROP/1 ACCEPT")
                var at = 0
                while (at < big.size) {
                    val n = minOf(256 * 1024, big.size - at)
                    ch.send(big.copyOfRange(at, at + n))
                    at += n
                }
                ch.send("ZDROP/1 END".toByteArray())
                check(String(ch.recv()) == "ZDROP/1 DONE 2")
            }
        }
        val link = LocalDropReceiver.Link.parse(
            "zeidexdrop://v1?h=127.0.0.1&p=${server.localPort}&k=${LocalDropReceiver.hex(key)}&n=Desk",
        )
        val saved = LocalDropReceiver.connect(link, "Phone").use { session ->
            assertEquals(listOf("video.bin", ".._empty.txt"), session.files.map { it.name })
            session.accept(dir) { _, _ -> }
        }
        sender.join(10_000)
        server.close()
        assertEquals(listOf(File(dir, "video 2.bin"), File(dir, ".._empty.txt")), saved)
        assertArrayEquals(big, saved[0].readBytes())
        assertEquals("already here", File(dir, "video.bin").readText())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".zeidex-part") })
    }

    @Test
    fun aWrongKeyNeverSeesTheOffer() {
        val key = ByteArray(32) { 1 }
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val sender = thread {
            server.accept().use { s ->
                val ch = LocalDropReceiver.Channel(s, key, sender = true)
                // The real sender drops a connection whose HELLO does not authenticate.
                try {
                    ch.recv()
                } catch (_: IOException) {
                }
            }
        }
        val wrong = "02".repeat(32)
        val link = LocalDropReceiver.Link.parse("zeidexdrop://v1?h=127.0.0.1&p=${server.localPort}&k=$wrong")
        expectFailure { LocalDropReceiver.connect(link, "Phone") }
        sender.join(10_000)
        server.close()
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        } catch (_: ArithmeticException) {
            return
        }
        throw AssertionError("expected a failure")
    }
}
