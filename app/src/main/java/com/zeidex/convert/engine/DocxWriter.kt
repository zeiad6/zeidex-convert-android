package com.zeidex.convert.engine

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free Office Open XML writer (`.docx`).
 *
 * A DOCX is a ZIP with three required parts. Keeping the writer here instead of pulling in a
 * word-processing library means the PDF and text pipelines can produce a real Word file
 * offline, on any Android version, with predictable output.
 */
object DocxWriter {

    sealed class Block {
        /** [heading] 1 or 2 maps to Heading1/Heading2, 0 is body text. */
        data class Paragraph(val text: String, val heading: Int = 0) : Block()
        data class Table(val rows: List<List<String>>) : Block()
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr>
<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Arial"/>
<w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-US" w:bidi="ar-SA"/>
</w:rPr></w:rPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/>
<w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr>
<w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/>
<w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr>
<w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>
<w:style w:type="table" w:styleId="Grid"><w:name w:val="Table Grid"/><w:tblPr>
<w:tblBorders><w:top w:val="single" w:sz="4" w:color="808080"/><w:left w:val="single" w:sz="4" w:color="808080"/>
<w:bottom w:val="single" w:sz="4" w:color="808080"/><w:right w:val="single" w:sz="4" w:color="808080"/>
<w:insideH w:val="single" w:sz="4" w:color="808080"/><w:insideV w:val="single" w:sz="4" w:color="808080"/></w:tblBorders>
</w:tblPr></w:style>
</w:styles>"""

    fun write(blocks: List<Block>, output: OutputStream) {
        val zip = ZipOutputStream(output)
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        zip.write(CONTENT_TYPES.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("_rels/.rels"))
        zip.write(ROOT_RELS.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
        zip.write(DOC_RELS.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("word/styles.xml"))
        zip.write(STYLES.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("word/document.xml"))
        zip.write(document(blocks).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
    }

    fun writeFile(file: File, blocks: List<Block>) {
        file.outputStream().use { write(blocks, it) }
    }

    /** Turns extracted plain text into paragraphs, keeping Markdown-ish headings and bullets. */
    fun blocksFromText(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks.add(Block.Paragraph(paragraph.toString().trim()))
            paragraph.setLength(0)
        }
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                line.startsWith("# ") -> {
                    flush()
                    blocks.add(Block.Paragraph(line.removePrefix("# ").trim(), heading = 1))
                }
                line.startsWith("## ") -> {
                    flush()
                    blocks.add(Block.Paragraph(line.removePrefix("## ").trim(), heading = 2))
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flush()
                    blocks.add(Block.Paragraph("• " + line.substring(2).trim()))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
        }
        flush()
        return blocks.ifEmpty { listOf(Block.Paragraph("")) }
    }

    private fun document(blocks: List<Block>): String {
        val body = StringBuilder()
        for (block in blocks) {
            when (block) {
                is Block.Paragraph -> body.append(paragraph(block.text, block.heading))
                is Block.Table -> body.append(table(block.rows))
            }
        }
        if (body.isEmpty()) body.append(paragraph("", 0))
        body.append(
            """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""" +
                """<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" """ +
                """w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>"""
        )
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>$body</w:body></w:document>"""
    }

    private fun paragraph(text: String, heading: Int): String {
        val style = when (heading) {
            1 -> """<w:pPr><w:pStyle w:val="Heading1"/></w:pPr>"""
            2 -> """<w:pPr><w:pStyle w:val="Heading2"/></w:pPr>"""
            else -> """<w:pPr><w:spacing w:after="120"/></w:pPr>"""
        }
        val runs = text.split('\n').mapIndexed { index, line ->
            val br = if (index > 0) "<w:br/>" else ""
            """<w:r>$br<w:t xml:space="preserve">${escape(line)}</w:t></w:r>"""
        }.joinToString("")
        return "<w:p>$style${runs.ifEmpty { "<w:r><w:t/></w:r>" }}</w:p>"
    }

    private fun table(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val columns = rows.maxOf { it.size }.coerceAtLeast(1)
        val width = (9360 / columns).coerceAtLeast(600)
        val out = StringBuilder("""<w:tbl><w:tblPr><w:tblStyle w:val="Grid"/><w:tblW w:w="0" w:type="auto"/></w:tblPr>""")
        for (row in rows) {
            out.append("<w:tr>")
            for (index in 0 until columns) {
                val cell = row.getOrElse(index) { "" }
                out.append("""<w:tc><w:tcPr><w:tcW w:w="$width" w:type="dxa"/></w:tcPr>""")
                out.append(paragraph(cell, 0))
                out.append("</w:tc>")
            }
            out.append("</w:tr>")
        }
        out.append("</w:tbl>")
        // Word needs a paragraph between a table and the next block.
        out.append("<w:p/>")
        return out.toString()
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch.code >= 0x20 || ch == '\t') append(ch)
            }
        }
    }
}
