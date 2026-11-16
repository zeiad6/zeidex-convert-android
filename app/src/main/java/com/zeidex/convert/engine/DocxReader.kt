package com.zeidex.convert.engine

import com.zeidex.convert.engine.XmlSupport.attr
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream

/**
 * Reads `word/document.xml` into the same block model the DOCX writer emits, so a Word file can
 * be turned into a PDF (or back into DOCX) with its headings and tables intact instead of a wall
 * of flat text.
 */
object DocxReader {

    fun blocks(stream: InputStream): List<DocxWriter.Block> {
        var found = false
        val blocks = mutableListOf<DocxWriter.Block>()
        ZipSupport.forEachEntry(stream) { name, entry ->
            if (name == "word/document.xml") {
                found = true
                val handler = DocumentHandler()
                try {
                    XmlSupport.parse(entry, handler)
                } catch (e: org.xml.sax.SAXException) {
                    throw XmlSupport.wrap(e, "the Word document")
                }
                blocks.addAll(handler.blocks())
            }
        }
        if (!found) throw java.io.IOException("Not a Word document (missing word/document.xml)")
        return blocks
    }

    private class DocumentHandler : DefaultHandler() {

        private val result = mutableListOf<DocxWriter.Block>()
        private val paragraph = StringBuilder()
        private var heading = 0
        private var inText = false
        private var tableDepth = 0
        private var rows: MutableList<List<String>>? = null
        private var row: MutableList<String>? = null
        private var cell: StringBuilder? = null

        fun blocks(): List<DocxWriter.Block> = result

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (localName) {
                "p" -> {
                    paragraph.setLength(0)
                    heading = 0
                }

                "tbl" -> {
                    if (tableDepth == 0) rows = mutableListOf()
                    tableDepth++
                }

                "tr" -> if (tableDepth == 1) row = mutableListOf()
                "tc" -> if (tableDepth == 1) cell = StringBuilder()
                "t" -> inText = true
                "tab" -> paragraph.append('\t')
                "br", "cr" -> paragraph.append('\n')
                "pStyle" -> {
                    val value = attributes.attr("val") ?: return
                    heading = when {
                        value.startsWith("Heading", ignoreCase = true) -> {
                            val level = value.filter { it.isDigit() }.toIntOrNull() ?: 1
                            level.coerceIn(1, 2)
                        }

                        value == "Title" -> 1
                        else -> 0
                    }
                }

                "outlineLvl" -> {
                    val level = attributes.attr("val")?.toIntOrNull() ?: return
                    if (heading == 0 && level in 0..1) heading = level + 1
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) paragraph.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            when (localName) {
                "t" -> inText = false
                "p" -> flushParagraph()
                "tbl" -> {
                    if (tableDepth == 1) {
                        rows?.let { if (it.isNotEmpty()) result.add(DocxWriter.Block.Table(it)) }
                        rows = null
                    }
                    if (tableDepth > 0) tableDepth--
                }

                "tr" -> {
                    if (tableDepth == 1) {
                        row?.let { if (it.any { c -> c.isNotBlank() }) rows?.add(it) }
                        row = null
                    }
                }

                "tc" -> {
                    if (tableDepth == 1) {
                        row?.add(cell?.toString()?.trim().orEmpty())
                        cell = null
                    }
                }
            }
        }

        private fun flushParagraph() {
            val text = paragraph.toString().trim()
            paragraph.setLength(0)
            val target = cell
            if (target != null) {
                if (text.isNotEmpty()) {
                    if (target.isNotEmpty()) target.append(' ')
                    target.append(text)
                }
                return
            }
            if (text.isNotEmpty()) result.add(DocxWriter.Block.Paragraph(text, heading))
        }
    }
}
