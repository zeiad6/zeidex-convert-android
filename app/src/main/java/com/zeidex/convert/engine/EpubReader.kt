package com.zeidex.convert.engine

import java.io.InputStream

/**
 * Reads an EPUB as text: the archive is XHTML chapters plus a package document that declares the
 * reading order. Chapters are collected and sorted naturally (`chapter2` before `chapter10`),
 * which matches the spine in practice without parsing the OPF for a single-purpose export.
 */
object EpubReader {

    private const val MAX_PARTS = 600
    private const val MAX_PART_BYTES = 2 * 1024 * 1024

    fun blocks(stream: InputStream): List<DocxWriter.Block> {
        val chapters = mutableListOf<Pair<String, String>>()
        ZipSupport.forEachEntry(stream) { name, entry ->
            if (chapters.size >= MAX_PARTS) return@forEachEntry
            val lower = name.lowercase()
            if (!lower.endsWith(".xhtml") && !lower.endsWith(".html") && !lower.endsWith(".htm")) return@forEachEntry
            val bytes = readBounded(entry, MAX_PART_BYTES) ?: return@forEachEntry
            val html = String(bytes, Charsets.UTF_8)
            val text = HtmlText.toText(html)
            if (text.isNotBlank()) chapters.add(name to text)
        }

        val result = mutableListOf<DocxWriter.Block>()
        for ((_, text) in chapters.sortedWith(compareBy { naturalKey(it.first) })) {
            result.addAll(DocxWriter.blocksFromText(text))
        }
        if (result.isEmpty()) throw IllegalStateException("No readable text in this EPUB")
        return result
    }

    private fun readBounded(stream: InputStream, limit: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            total += read
            if (total > limit) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /** `OEBPS/chapter2.xhtml` → `OEBPS/chapter00000002.xhtml`, so 2 sorts before 10. */
    private fun naturalKey(name: String): String =
        Regex("\\d+").replace(name) { match -> match.value.padStart(10, '0') }
}
