package com.zeidex.convert.engine

import java.nio.charset.Charset

/**
 * Plain-text extraction from RTF.
 *
 * RTF is a 7-bit-safe format: every non-ASCII character arrives either as a `\uNNNN` escape or as
 * a `\'hh` byte in the document's code page (`\ansicpg1256` for Arabic, `1252` for Western).
 * Getting the code page wrong turns an Arabic contract into mojibake, so it is read from the
 * header and the bytes are decoded with it.
 *
 * Groups that describe formatting rather than text (font and colour tables, embedded pictures,
 * field instructions) are skipped by destination name, not by guessing.
 */
object RtfReader {

    fun blocks(bytes: ByteArray): List<DocxWriter.Block> =
        DocxWriter.blocksFromText(toText(bytes))

    fun toText(bytes: ByteArray): String {
        val out = StringBuilder()
        val groups = ArrayDeque<Boolean>()
        var ignored = false
        var charset: Charset = WESTERN
        var skip = 0
        var unicodeFallback = 1
        var index = 0

        fun appendText(value: String) {
            if (ignored) return
            if (skip > 0) {
                if (value.length <= skip) {
                    skip -= value.length
                    return
                }
                out.append(value, skip, value.length)
                skip = 0
                return
            }
            out.append(value)
        }

        while (index < bytes.size) {
            val byte = bytes[index].toInt() and 0xFF
            when {
                byte == '{'.code -> {
                    groups.addLast(ignored)
                    index++
                }

                byte == '}'.code -> {
                    if (groups.isNotEmpty()) ignored = groups.removeLast()
                    index++
                }

                byte == '\\'.code -> {
                    index++
                    if (index >= bytes.size) break
                    val control = bytes[index].toInt() and 0xFF
                    when {
                        control == '\''.code -> {
                            val hex = String(
                                byteArrayOf(bytes.getOrElse(index + 1) { 0 }, bytes.getOrElse(index + 2) { 0 }),
                                Charsets.US_ASCII
                            )
                            index += 3
                            val value = hex.trim().toIntOrNull(16)
                            if (value != null) appendText(String(byteArrayOf(value.toByte()), charset))
                        }

                        control == '*'.code -> {
                            ignored = true
                            index++
                        }

                        control == '\\'.code || control == '{'.code || control == '}'.code -> {
                            appendText(control.toChar().toString())
                            index++
                        }

                        control == '~'.code -> {
                            appendText("\u00A0")
                            index++
                        }

                        control == '_'.code -> {
                            appendText("\u2011")
                            index++
                        }

                        control == '-'.code -> index++

                        control == '\n'.code || control == '\r'.code -> {
                            appendText("\n")
                            index++
                        }

                        control.toChar().isLetter() -> {
                            val wordStart = index
                            while (index < bytes.size && (bytes[index].toInt() and 0xFF).toChar().isLetter()) index++
                            val word = String(bytes, wordStart, index - wordStart, Charsets.US_ASCII)
                            var numeric: Int? = null
                            val digitStart = index
                            if (index < bytes.size) {
                                val next = bytes[index].toInt() and 0xFF
                                if (next == '-'.code || next.toChar().isDigit()) {
                                    index++
                                    while (index < bytes.size && (bytes[index].toInt() and 0xFF).toChar().isDigit()) index++
                                    numeric = String(bytes, digitStart, index - digitStart, Charsets.US_ASCII).toIntOrNull()
                                }
                            }
                            if (index < bytes.size && (bytes[index].toInt() and 0xFF) == ' '.code) index++

                            when (word) {
                                "par", "line", "sect", "page", "row" -> appendText("\n")
                                "tab" -> appendText("\t")
                                "uc" -> unicodeFallback = numeric ?: 1
                                "u" -> {
                                    if (numeric != null) {
                                        val code = if (numeric < 0) numeric + 65536 else numeric
                                        appendText(code.toChar().toString())
                                        skip = unicodeFallback
                                    }
                                }

                                "ansicpg" -> {
                                    val codepage = numeric ?: 1252
                                    charset = runCatching { Charset.forName("windows-$codepage") }
                                        .getOrElse { WESTERN }
                                }

                                "bin" -> if (numeric != null) index = (index + numeric).coerceAtMost(bytes.size)
                                in SKIPPED_DESTINATIONS -> ignored = true
                            }
                        }

                        else -> index++
                    }
                }

                byte == '\r'.code || byte == '\n'.code -> index++
                // Raw high bytes are illegal RTF; they appear in files written by hand, so decode
                // them with the document code page instead of dropping them.
                else -> {
                    appendText(String(byteArrayOf(byte.toByte()), charset))
                    index++
                }
            }
        }

        return out.toString()
            .replace("\r\n", "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private val WESTERN: Charset = Charset.forName("windows-1252")

    private val SKIPPED_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "objdata",
        "header", "headerl", "headerr", "footer", "footerl", "footerr",
        "listtable", "listoverridetable", "revisiontable", "rsidtbl", "generator",
        "themedata", "colorschememapping", "datastore", "latentstyles", "xmlnstbl",
        "filetbl", "pgdsctbl", "bkmkstart", "bkmkend", "fldinst", "nonshppict",
        "shpinst", "sp", "sn", "sv", "pn", "fldrslt", "private"
    )
}
