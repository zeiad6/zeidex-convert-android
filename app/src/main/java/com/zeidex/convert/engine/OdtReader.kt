package com.zeidex.convert.engine

import com.zeidex.convert.engine.XmlSupport.attr
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream

/**
 * Reads an OpenDocument text file (`.odt`) from its `content.xml`.
 *
 * OpenDocument uses the same "paragraphs and tables" shape as a Word document, so the reader
 * produces the same block model: headings from `text:h`, body text from `text:p`, grids from
 * `table:table`. Whitespace is significant in the format (`text:s` is a run of spaces), and
 * dropping it would run every word together.
 */
object OdtReader {

    /** OpenDocument expresses empty spans as counts; these caps keep a stray value finite. */
    private const val MAX_CELL_REPEAT = 256
    private const val MAX_ROW_REPEAT = 200

    fun blocks(stream: InputStream): List<DocxWriter.Block> {
        var found = false
        val blocks = mutableListOf<DocxWriter.Block>()
        ZipSupport.forEachEntry(stream) { name, entry ->
            if (name == "content.xml") {
                found = true
                val handler = ContentHandler()
                try {
                    XmlSupport.parse(entry, handler)
                } catch (e: org.xml.sax.SAXException) {
                    throw XmlSupport.wrap(e, "the OpenDocument file")
                }
                blocks.addAll(handler.blocks())
            }
        }
        if (!found) throw java.io.IOException("Not an OpenDocument file (missing content.xml)")
        return blocks
    }

    private fun org.xml.sax.Attributes.repeat(name: String, cap: Int): Int =
        (attr(name)?.toIntOrNull() ?: 1).coerceIn(1, cap)

    private class ContentHandler : DefaultHandler() {

        private val result = mutableListOf<DocxWriter.Block>()
        private val paragraph = StringBuilder()
        private var heading = 0
        private var inParagraph = false
        private var tableDepth = 0
        private var rows: MutableList<List<String>>? = null
        private var row: MutableList<String>? = null
        private var cell: StringBuilder? = null
        private var column = 0
        private var rowRepeat = 1
        private var cellRepeat = 1

        fun blocks(): List<DocxWriter.Block> = result

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (localName) {
                "h" -> {
                    paragraph.setLength(0)
                    inParagraph = true
                    heading = (attributes.attr("outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 2)
                }

                "p" -> {
                    paragraph.setLength(0)
                    inParagraph = true
                    heading = 0
                }

                "s" -> {
                    val count = attributes.attr("c")?.toIntOrNull()?.coerceIn(1, 200) ?: 1
                    append(" ".repeat(count))
                }

                "tab" -> append('\t')
                "line-break" -> append('\n')
                "table" -> {
                    if (tableDepth == 0) rows = mutableListOf()
                    tableDepth++
                }

                "table-row" -> if (tableDepth == 1) {
                    row = mutableListOf()
                    column = 0
                    rowRepeat = attributes.repeat("number-rows-repeated", MAX_ROW_REPEAT)
                }

                "table-cell", "covered-table-cell" -> if (tableDepth == 1) {
                    cell = StringBuilder()
                    cellRepeat = attributes.repeat("number-columns-repeated", MAX_CELL_REPEAT)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            val target = cell
            if (target != null) {
                target.append(ch, start, length)
            } else if (inParagraph) {
                paragraph.append(ch, start, length)
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            when (localName) {
                "p", "h" -> flushParagraph()
                "table-cell", "covered-table-cell" -> {
                    if (tableDepth == 1) {
                        // A repeated empty cell is a span, not data: only the column counter moves,
                        // otherwise a trailing repeat of 1024 would pad every row to 1024 cells.
                        val text = cell?.toString()?.trim().orEmpty()
                        val current = row
                        if (current != null && text.isNotEmpty()) {
                            while (current.size <= column) current.add("")
                            current[column] = text
                        }
                        column += cellRepeat
                        cell = null
                        cellRepeat = 1
                    }
                }

                "table-row" -> {
                    if (tableDepth == 1) {
                        val current = row
                        if (current != null && current.any { c -> c.isNotBlank() }) {
                            val copies = rowRepeat.coerceIn(1, MAX_ROW_REPEAT)
                            repeat(copies) { rows?.add(current.toList()) }
                        }
                        row = null
                        rowRepeat = 1
                    }
                }

                "table" -> {
                    if (tableDepth == 1) {
                        rows?.let { if (it.isNotEmpty()) result.add(DocxWriter.Block.Table(it)) }
                        rows = null
                    }
                    if (tableDepth > 0) tableDepth--
                }
            }
        }

        private fun append(ch: Char) = append(ch.toString())

        private fun append(value: String) {
            if (value.isEmpty()) return
            val target = cell
            if (target != null) target.append(value) else if (inParagraph) paragraph.append(value)
        }

        private fun flushParagraph() {
            val text = paragraph.toString().trim()
            paragraph.setLength(0)
            inParagraph = false
            val target = cell
            if (target != null) {
                // A paragraph inside a cell is a line of that cell, not a new block.
                if (text.isNotEmpty()) {
                    if (target.isNotEmpty()) target.append(' ')
                    target.append(text)
                }
                return
            }
            if (text.isEmpty()) return
            if (heading > 0) result.add(DocxWriter.Block.Paragraph(text, heading))
            else result.add(DocxWriter.Block.Paragraph(text))
            heading = 0
        }
    }
}
