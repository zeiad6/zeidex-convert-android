package com.zeidex.convert.engine

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.zeidex.convert.model.FileItem
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Document pipeline: text extraction (PDF/DOCX/TXT/Markdown) and writing (DOCX/TXT/Markdown).
 *
 * PDF text is read with PDFBox; the DOCX writer is ours. Scanned PDFs have no text layer and
 * are reported as such instead of producing an empty document — OCR would need a model, so the
 * caller gets a clear message rather than silence.
 */
object DocumentConverter {

    private val TEXT_EXTS = setOf("txt", "md", "markdown", "csv", "tsv", "log", "json", "xml", "html", "htm")

    fun isTextDocument(ext: String): Boolean = ext.lowercase() in TEXT_EXTS

    fun readText(context: Context, input: FileItem): String {
        val ext = input.ext.lowercase()
        return when {
            isTextDocument(ext) -> {
                context.contentResolver.openInputStream(input.uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw IOException("Cannot open ${input.name}")
            }
            ext == "docx" -> {
                context.contentResolver.openInputStream(input.uri)?.use { docxText(it) }
                    ?: throw IOException("Cannot open ${input.name}")
            }
            ext == "pdf" -> {
                context.contentResolver.openInputStream(input.uri)?.use { pdfText(it) }
                    ?: throw IOException("Cannot open ${input.name}")
            }
            else -> throw UnsupportedOperationException(
                "Reading .$ext is not supported on-device. Convert it to PDF or DOCX on the desktop app first."
            )
        }
    }

    /** Writes text as TXT, Markdown or DOCX, atomically, into [outputDir]. */
    fun writeText(text: String, targetExt: String, outputDir: File, baseName: String): File {
        val name = "$baseName.${targetExt.lowercase()}"
        return com.zeidex.convert.util.AtomicWriter.write(outputDir, name) { out ->
            when (targetExt.lowercase()) {
                "docx" -> DocxWriter.write(DocxWriter.blocksFromText(text), out)
                "md", "markdown" -> out.write(markdown(text).toByteArray(Charsets.UTF_8))
                else -> out.write(text.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * PDF → DOCX with paragraph grouping, heading detection by font size and simple table
     * reconstruction from aligned text columns.
     */
    fun pdfToDocx(context: Context, input: FileItem, outputDir: File): File {
        val stream = context.contentResolver.openInputStream(input.uri)
            ?: throw IOException("Cannot open ${input.name}")
        val blocks = stream.use { pdfBlocks(it) }
        if (blocks.isEmpty() || (blocks.size == 1 && blocks[0] is DocxWriter.Block.Paragraph &&
                (blocks[0] as DocxWriter.Block.Paragraph).text.isBlank())
        ) {
            throw IllegalStateException(
                "No text layer found in ${input.name}. This looks like a scanned PDF; OCR is available in the desktop app."
            )
        }
        val base = input.name.substringBeforeLast('.').ifBlank { "document" }
        return com.zeidex.convert.util.AtomicWriter.write(outputDir, "$base.docx") { out ->
            DocxWriter.write(blocks, out)
        }
    }

    // ---------- PDF ----------

    private data class Run(val text: String, val x: Float, val endX: Float, val size: Float)
    private data class Line(val runs: List<Run>, val y: Float, val size: Float)

    private class PositionStripper : PDFTextStripper() {
        val lines = mutableListOf<Line>()
        override fun writeString(text: String, positions: List<TextPosition>) {
            if (text.isBlank() || positions.isEmpty()) return
            val runs = mutableListOf<Run>()
            val builder = StringBuilder()
            var startX = positions.first().xDirAdj
            var lastEnd = startX
            var lastSize = positions.first().heightDir
            for (position in positions) {
                val gap = position.xDirAdj - lastEnd
                if (builder.isNotEmpty() && gap > 12f) {
                    runs.add(Run(builder.toString().trim(), startX, lastEnd, lastSize))
                    builder.setLength(0)
                    startX = position.xDirAdj
                }
                builder.append(position.unicode)
                lastEnd = position.xDirAdj + position.widthDirAdj
                lastSize = maxOf(lastSize, position.heightDir)
            }
            if (builder.isNotEmpty()) runs.add(Run(builder.toString().trim(), startX, lastEnd, lastSize))
            val filtered = runs.filter { it.text.isNotEmpty() }
            if (filtered.isNotEmpty()) {
                lines.add(Line(filtered, positions.first().yDirAdj, filtered.maxOf { it.size }))
            }
        }
    }

    private fun pdfText(stream: InputStream): String {
        PDDocument.load(stream).use { document ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
            }
            return stripper.getText(document).replace("\r\n", "\n").trim()
        }
    }

    private fun pdfBlocks(stream: InputStream): List<DocxWriter.Block> {
        val stripper = PositionStripper().apply { sortByPosition = true }
        PDDocument.load(stream).use { document -> stripper.getText(document) }
        val lines = stripper.lines
        if (lines.isEmpty()) return emptyList()

        val bodySize = median(lines.map { it.size })
        val blocks = mutableListOf<DocxWriter.Block>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]

            // A run of lines with the same number of separated runs is treated as a table.
            if (line.runs.size >= 2) {
                val columns = line.runs.size
                val rows = mutableListOf<List<String>>()
                var cursor = index
                while (cursor < lines.size && lines[cursor].runs.size == columns && abs(lines[cursor].size - line.size) < 0.5f) {
                    rows.add(alignColumns(lines[cursor], columns))
                    cursor++
                }
                if (rows.size >= 2) {
                    blocks.add(DocxWriter.Block.Table(rows))
                    index = cursor
                    continue
                }
                // Single separated line: keep it as text, not a one-row table.
                blocks.add(DocxWriter.Block.Paragraph(line.runs.joinToString("  ") { it.text }))
                index++
                continue
            }

            val heading = when {
                line.size > bodySize * 1.45f && line.runs.joinToString(" ") { it.text }.length < 120 -> 1
                line.size > bodySize * 1.15f && line.runs.joinToString(" ") { it.text }.length < 120 -> 2
                else -> 0
            }
            val text = StringBuilder(line.runs.joinToString(" ") { it.text })
            var cursor = index + 1
            if (heading == 0) {
                // A new paragraph starts when the vertical gap is wider than normal leading.
                while (cursor < lines.size && lines[cursor].runs.size == 1) {
                    val next = lines[cursor]
                    val gap = next.y - lines[cursor - 1].y
                    if (gap > 1.65f * maxOf(line.size, bodySize)) break
                    text.append(' ').append(next.runs.joinToString(" ") { it.text })
                    cursor++
                }
            }
            blocks.add(DocxWriter.Block.Paragraph(text.toString().trim(), heading))
            index = cursor
        }
        return blocks
    }

    private fun alignColumns(line: Line, columns: Int): List<String> {
        val cells = MutableList(columns) { "" }
        val sorted = line.runs.sortedBy { it.x }
        for ((i, run) in sorted.withIndex()) {
            if (i < columns) cells[i] = run.text
        }
        return cells
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 12f
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    // ---------- DOCX ----------

    /** Extracts visible text from `word/document.xml` without an XML parser dependency. */
    fun docxText(stream: InputStream): String {
        var xml: String? = null
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    xml = zip.readBytes().toString(Charsets.UTF_8)
                    break
                }
                zip.closeEntry()
            }
        }
        val body = xml ?: throw IOException("Not a Word document (missing word/document.xml)")
        val text = body
            .replace(Regex("<w:tab[^>]*/>"), "\t")
            .replace(Regex("<w:br[^>]*/>"), "\n")
            .replace(Regex("</w:p>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        return text.lines().joinToString("\n") { it.trimEnd() }.trim()
    }

    private fun markdown(text: String): String {
        val paragraphs = text.replace("\r\n", "\n").split(Regex("\n{2,}"))
        return paragraphs.joinToString("\n\n") { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            when {
                lines.isEmpty() -> ""
                lines.size == 1 && lines[0].length < 80 && !lines[0].endsWith(".") -> "## ${lines[0].trim()}"
                else -> lines.joinToString("\n\n") { it.trim() }
            }
        }
    }
}
