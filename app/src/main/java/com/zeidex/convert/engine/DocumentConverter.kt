package com.zeidex.convert.engine

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.zeidex.convert.model.FileItem
import com.zeidex.convert.util.AtomicWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Document pipeline: reading (PDF, Word, Excel, PowerPoint, OpenDocument, RTF, HTML, EPUB, text)
 * and writing (PDF, DOCX, TXT, Markdown).
 *
 * Every format is reduced to the same block model — paragraphs with a heading level plus tables —
 * which is what makes "any document to PDF" and "any document to Word" the same code path instead
 * of one bespoke converter per extension. Formats that cannot be read on a phone (Office 97-2003
 * binaries, scanned PDFs without a text layer) say so in a message the user can act on.
 */
object DocumentConverter {

    private val TEXT_EXTS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "log", "json", "xml", "html", "htm",
        "yml", "yaml", "ini", "srt", "vtt"
    )
    private val MARKUP_EXTS = setOf("html", "htm")
    private val WORD_EXTS = setOf("docx", "docm", "dotx", "dotm")
    private val SPREADSHEET_EXTS = setOf("xlsx", "xlsm", "xltx", "xltm", "xls", "ods")
    private val SLIDE_EXTS = setOf("pptx", "pptm", "ppsx", "ppsm")
    private val ODF_TEXT_EXTS = setOf("odt", "ott")
    private val APPLE_EXTS = setOf("pages", "numbers", "key")

    /** Rows written to a PDF per sheet. Beyond this the export is a wall of paper, not a document. */
    private const val MAX_SHEET_ROWS = 5000

    fun isTextDocument(ext: String): Boolean = ext.lowercase() in TEXT_EXTS

    // ---------- reading ----------

    fun readText(context: Context, input: FileItem): String {
        val ext = input.ext.lowercase()
        return when {
            // Markup is read as text, not as source: a page converted to TXT should not arrive
            // with its tags attached.
            ext in MARKUP_EXTS -> HtmlText.toText(openText(context, input))
            isTextDocument(ext) -> openText(context, input)
            ext in WORD_EXTS -> docxText(context, input)
            ext == "pdf" -> open(context, input).use { pdfText(it) }
            else -> flatten(readBlocks(context, input))
        }
    }

    /**
     * The document as blocks. Tables stay tables: a spreadsheet read as flat text loses the only
     * structure it had, and a PDF or Word export of it becomes unreadable columns.
     */
    fun readBlocks(context: Context, input: FileItem): List<DocxWriter.Block> {
        val ext = input.ext.lowercase()
        return when {
            ext in MARKUP_EXTS -> HtmlText.toBlocks(openText(context, input))
            ext == "csv" -> CsvReader.toBlocks(openText(context, input))
            ext == "tsv" -> CsvReader.toBlocks(openText(context, input), preferredDelimiter = '\t')
            isTextDocument(ext) -> DocxWriter.blocksFromText(openText(context, input))
            ext == "pdf" -> open(context, input).use { pdfBlocks(it) }
            ext in SPREADSHEET_EXTS -> spreadsheetBlocks(context, input, ext)
            ext in SLIDE_EXTS -> open(context, input).use { PptxReader.blocks(it) }
            ext in ODF_TEXT_EXTS -> open(context, input).use { OdtReader.blocks(it) }
            ext == "epub" -> open(context, input).use { EpubReader.blocks(it) }
            ext in WORD_EXTS || ext == "rtf" || ext == "doc" -> wordBlocks(context, input)
            isLegacyOle(peek(context, input, 8)) -> throw UnsupportedOperationException(legacyMessage(ext))
            ext in APPLE_EXTS -> throw UnsupportedOperationException(
                ".${ext.uppercase()} is an Apple iWork document. Export it as PDF, DOCX or XLSX first."
            )
            else -> throw UnsupportedOperationException(unsupportedMessage(ext))
        }
    }

    private fun spreadsheetBlocks(
        context: Context,
        input: FileItem,
        ext: String
    ): List<DocxWriter.Block> {
        if (ext == "ods") return open(context, input).use { OdtReader.blocks(it) }
        val head = peek(context, input, 8)
        if (!isZip(head)) {
            if (isLegacyOle(head)) throw UnsupportedOperationException(legacyMessage(ext))
            // Excel happily writes "xls" files that are really CSV or HTML.
            return openText(context, input).let { text ->
                if (text.trimStart().startsWith("<")) HtmlText.toBlocks(text)
                else CsvReader.toBlocks(text)
            }
        }

        val sheets = XlsxReader.read { open(context, input) }
        if (sheets.isEmpty()) throw IllegalStateException("No sheets found in ${input.name}.")
        val blocks = mutableListOf<DocxWriter.Block>()
        for (sheet in sheets) {
            val rows = sheet.rows
                .map { trimRow(it) }
                .filter { it.any { cell -> cell.isNotBlank() } }
            if (rows.isEmpty()) continue
            blocks.add(DocxWriter.Block.Paragraph(sheet.name, heading = 2))
            blocks.add(DocxWriter.Block.Table(rows.take(MAX_SHEET_ROWS)))
            if (rows.size > MAX_SHEET_ROWS) {
                blocks.add(
                    DocxWriter.Block.Paragraph(
                        "(First $MAX_SHEET_ROWS rows of ${rows.size} shown. Open ${input.name} on the desktop app for the rest.)"
                    )
                )
            }
        }
        if (blocks.isEmpty()) throw IllegalStateException("${input.name} has no readable cells.")
        return blocks
    }

    private fun wordBlocks(context: Context, input: FileItem): List<DocxWriter.Block> {
        val ext = input.ext.lowercase()
        val head = peek(context, input, 8)
        return when {
            isZip(head) -> try {
                open(context, input).use { DocxReader.blocks(it) }
            } catch (_: Exception) {
                // A damaged package still carries text in word/document.xml; the flat extractor
                // is more forgiving than the structural reader.
                open(context, input).use { DocxWriter.blocksFromText(legacyDocxText(it)) }
            }

            isRtf(head) -> open(context, input).use { RtfReader.blocks(it.readBytes()) }
            isLegacyOle(head) -> throw UnsupportedOperationException(legacyMessage(ext))
            else -> openText(context, input).let { text ->
                if (text.trimStart().startsWith("<")) HtmlText.toBlocks(text)
                else DocxWriter.blocksFromText(text)
            }
        }
    }

    // ---------- writing ----------

    /** Writes text as TXT, Markdown or DOCX, atomically, into [outputDir]. */
    fun writeText(text: String, targetExt: String, outputDir: File, baseName: String): File {
        val name = "$baseName.${targetExt.lowercase()}"
        return AtomicWriter.write(outputDir, name) { out ->
            when (targetExt.lowercase()) {
                "docx" -> DocxWriter.write(DocxWriter.blocksFromText(text), out)
                "md", "markdown" -> out.write(markdown(text).toByteArray(Charsets.UTF_8))
                else -> out.write(text.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * One entry point for every document target: the input is read once into blocks and each
     * writer consumes that model.
     */
    fun convert(
        context: Context,
        input: FileItem,
        targetExt: String,
        outputDir: File,
        onProgress: ((Float) -> Unit)? = null,
        checkActive: (() -> Unit)? = null
    ): File {
        val ext = targetExt.lowercase()
        val base = input.name.substringBeforeLast('.').ifBlank { "document" }
        if (ext == "txt" || ext == "md" || ext == "markdown") {
            val text = readText(context, input)
            // An empty file is not a conversion: a scanned PDF has no text layer, and shipping a
            // zero-byte .txt for it looks like success.
            if (text.isBlank()) throw emptyDocument(input)
            return writeText(text, ext, outputDir, base)
        }
        val blocks = readBlocks(context, input)
        ensureReadable(blocks, input)
        return when (ext) {
            "pdf" -> AtomicWriter.write(outputDir, "$base.pdf") { out ->
                PdfWriter.write(blocks, input.name, out, onProgress, checkActive)
            }

            "docx" -> AtomicWriter.write(outputDir, "$base.docx") { out ->
                DocxWriter.write(blocks, out)
            }

            else -> throw UnsupportedOperationException(
                "Writing .$ext on-device is not supported. PDF, DOCX, TXT and Markdown are available."
            )
        }
    }

    fun toPdf(
        context: Context,
        input: FileItem,
        outputDir: File,
        onProgress: ((Float) -> Unit)? = null,
        checkActive: (() -> Unit)? = null
    ): File = convert(context, input, "pdf", outputDir, onProgress, checkActive)

    /**
     * PDF → DOCX with paragraph grouping, heading detection by font size and simple table
     * reconstruction from aligned text columns.
     */
    fun pdfToDocx(context: Context, input: FileItem, outputDir: File): File {
        val blocks = open(context, input).use { pdfBlocks(it) }
        ensureReadable(blocks, input)
        val base = input.name.substringBeforeLast('.').ifBlank { "document" }
        return AtomicWriter.write(outputDir, "$base.docx") { out ->
            DocxWriter.write(blocks, out)
        }
    }

    private fun ensureReadable(blocks: List<DocxWriter.Block>, input: FileItem) {
        val empty = blocks.isEmpty() ||
            blocks.all { it is DocxWriter.Block.Paragraph && it.text.isBlank() }
        if (!empty) return
        throw emptyDocument(input)
    }

    private fun emptyDocument(input: FileItem): IllegalStateException =
        if (input.ext.equals("pdf", ignoreCase = true)) {
            IllegalStateException(
                "No text layer found in ${input.name}. This looks like a scanned PDF; OCR is available in the desktop app."
            )
        } else {
            IllegalStateException("No readable text was found in ${input.name}.")
        }

    // ---------- sources ----------

    private fun open(context: Context, input: FileItem): InputStream =
        context.contentResolver.openInputStream(input.uri) ?: throw IOException("Cannot open ${input.name}")

    private fun peek(context: Context, input: FileItem, count: Int): ByteArray =
        open(context, input).use { stream ->
            val buffer = ByteArray(count)
            var read = 0
            while (read < count) {
                val now = stream.read(buffer, read, count - read)
                if (now <= 0) break
                read += now
            }
            buffer.copyOf(read)
        }

    /**
     * UTF-8 unless a byte-order mark says otherwise: Windows still writes UTF-16 for "Unicode
     * text" exports, and reading those as UTF-8 produces a document of NUL characters.
     */
    private fun openText(context: Context, input: FileItem): String {
        val bytes = open(context, input).use { it.readBytes() }
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun isZip(head: ByteArray): Boolean =
        head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

    private fun isRtf(head: ByteArray): Boolean =
        head.size >= 5 && head[0] == '{'.code.toByte() && head[1] == '\\'.code.toByte() &&
            head[2] == 'r'.code.toByte() && head[3] == 't'.code.toByte() && head[4] == 'f'.code.toByte()

    /** OLE2 compound file: the container of every Office 97-2003 binary (.doc/.xls/.ppt). */
    private fun isLegacyOle(head: ByteArray): Boolean =
        head.size >= 8 &&
            (head[0].toInt() and 0xFF) == 0xD0 && (head[1].toInt() and 0xFF) == 0xCF &&
            (head[2].toInt() and 0xFF) == 0x11 && (head[3].toInt() and 0xFF) == 0xE0 &&
            (head[4].toInt() and 0xFF) == 0xA1 && (head[5].toInt() and 0xFF) == 0xB1 &&
            (head[6].toInt() and 0xFF) == 0x1A && (head[7].toInt() and 0xFF) == 0xE1

    private fun legacyMessage(ext: String): String =
        ".${ext.uppercase()} is the Office 97-2003 binary format, which cannot be read on-device. " +
            "Save it as .${if (ext.equals("xls", true)) "xlsx" else if (ext.equals("ppt", true)) "pptx" else "docx"} " +
            "in Office, or convert it on the desktop app."

    private fun unsupportedMessage(ext: String): String =
        "Reading .$ext is not supported on-device. PDF, Word (.docx), Excel (.xlsx/.xlsm/.ods/.csv), " +
            "PowerPoint (.pptx), OpenDocument, RTF, HTML, Markdown, TXT and EPUB are supported."

    // ---------- blocks ----------

    private fun trimRow(row: List<String>): List<String> {
        var end = row.size
        while (end > 0 && row[end - 1].isBlank()) end--
        return if (end == 0) emptyList() else row.subList(0, end)
    }

    private fun flatten(blocks: List<DocxWriter.Block>): String =
        blocks.joinToString("\n\n") { block ->
            when (block) {
                is DocxWriter.Block.Paragraph -> {
                    val prefix = if (block.heading > 0) "#".repeat(block.heading) + " " else ""
                    prefix + block.text
                }

                is DocxWriter.Block.Table -> block.rows.joinToString("\n") { row -> row.joinToString("\t") }
            }
        }.trim()

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

    private fun docxText(context: Context, input: FileItem): String = try {
        open(context, input).use { flatten(DocxReader.blocks(it)) }
    } catch (_: Exception) {
        open(context, input).use { legacyDocxText(it) }
    }

    /** Extracts visible text from `word/document.xml` without an XML parser dependency. */
    fun legacyDocxText(stream: InputStream): String {
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
            .replace(Regex("<w:p[ >][^>]*/>"), "\n")
            .replace(Regex("</w:p>"), "\n")
            .replace(Regex("</w:tc>"), "\t")
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
