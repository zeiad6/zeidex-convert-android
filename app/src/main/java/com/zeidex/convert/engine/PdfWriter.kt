package com.zeidex.convert.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import com.zeidex.convert.engine.DocxWriter.Block
import java.io.OutputStream

/**
 * Writes the block model to a real PDF (A4, selectable text) with no PDF library.
 *
 * `android.graphics.pdf.PdfDocument` draws through the same Skia text stack the UI uses, so
 * Arabic, Hebrew and CJK are shaped by the system font fallback instead of being handed to a
 * Type-1 font that only knows Latin. Paragraphs are laid out with `StaticLayout`, which brings
 * bidirectional text, justification and line breaking along for free.
 *
 * Two layout decisions matter for a converter:
 *  - text is paginated line by line, so a paragraph longer than a page continues on the next one
 *    instead of being clipped;
 *  - a table with many columns switches to a landscape page, because eight columns squeezed into
 *    a portrait width are unreadable at any font size.
 */
object PdfWriter {

    private const val PORTRAIT_WIDTH = 595
    private const val PORTRAIT_HEIGHT = 842
    private const val LANDSCAPE_WIDTH = 842
    private const val LANDSCAPE_HEIGHT = 595
    private const val MARGIN_SIDE = 46f
    private const val MARGIN_TOP = 52f
    private const val MARGIN_BOTTOM = 48f
    private const val BODY_SIZE = 10.5f
    private const val TABLE_SIZE = 8.5f
    private const val CELL_PAD = 3.5f
    private const val LINE_SPACING = 1.18f
    private const val MIN_ROW_HEIGHT = 14f
    private const val WIDE_TABLE_COLUMNS = 6
    private const val MAX_TABLE_COLUMNS = 24
    private const val PARAGRAPH_CHUNK = 6000

    /**
     * @param footer text in the page footer, normally the source file name.
     * @param checkActive called between blocks and rows so a cancelled task stops writing.
     */
    fun write(
        blocks: List<Block>,
        footer: String,
        output: OutputStream,
        onProgress: ((Float) -> Unit)? = null,
        checkActive: (() -> Unit)? = null
    ) {
        val renderer = Renderer(footer, onProgress, checkActive)
        try {
            val total = blocks.size.coerceAtLeast(1)
            blocks.forEachIndexed { index, block ->
                checkActive?.invoke()
                when (block) {
                    is Block.Paragraph -> renderer.drawParagraph(block.text, block.heading)
                    is Block.Table -> renderer.drawTable(block.rows)
                }
                onProgress?.invoke((index + 1).toFloat() / total * 0.95f)
            }
            renderer.finish(output)
        } finally {
            renderer.close()
        }
    }

    private class Renderer(
        private val footer: String,
        private val onProgress: ((Float) -> Unit)?,
        private val checkActive: (() -> Unit)?
    ) {
        private val document = PdfDocument()
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var pageNumber = 0
        private var width = PORTRAIT_WIDTH.toFloat()
        private var height = PORTRAIT_HEIGHT.toFloat()
        private var landscape = false
        private var cursorY = MARGIN_TOP

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            color = Color.rgb(150, 158, 166)
        }
        private val shadePaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.rgb(238, 241, 245)
        }
        private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 7.5f
            color = Color.rgb(110, 118, 126)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

        fun finish(output: OutputStream) {
            if (page == null) startPage(false)
            endPage()
            document.writeTo(output)
        }

        fun close() {
            try {
                document.close()
            } catch (_: Exception) {
            }
        }

        // ---------- pages ----------

        private fun startPage(wide: Boolean) {
            endPage()
            landscape = wide
            width = if (wide) LANDSCAPE_WIDTH.toFloat() else PORTRAIT_WIDTH.toFloat()
            height = if (wide) LANDSCAPE_HEIGHT.toFloat() else PORTRAIT_HEIGHT.toFloat()
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(width.toInt(), height.toInt(), pageNumber).create()
            page = document.startPage(info)
            canvas = page?.canvas
            cursorY = MARGIN_TOP
        }

        private fun endPage() {
            val current = page ?: return
            val target = canvas
            if (target != null) {
                val baseline = height - 24f
                target.drawText(footerLabel(), MARGIN_SIDE, baseline, footerPaint)
                val number = pageNumber.toString()
                target.drawText(number, width - MARGIN_SIDE - footerPaint.measureText(number), baseline, footerPaint)
            }
            document.finishPage(current)
            page = null
            canvas = null
        }

        private fun ensurePage(wide: Boolean) {
            if (page == null || wide != landscape) startPage(wide)
        }

        private fun contentWidth(): Float = width - 2 * MARGIN_SIDE
        private fun contentRight(): Float = width - MARGIN_SIDE
        private fun bottom(): Float = height - MARGIN_BOTTOM

        private fun footerLabel(): String {
            val name = footer.ifBlank { "Zeidex Convert" }
            return if (name.length > 78) name.take(75) + "..." else name
        }

        // ---------- paragraphs ----------

        fun drawParagraph(text: String, heading: Int) {
            if (text.isBlank()) {
                cursorY += BODY_SIZE * 0.6f
                return
            }
            val size = when (heading) {
                1 -> 17f
                2 -> 13.5f
                else -> BODY_SIZE
            }
            val paint = textPaint(size, bold = heading > 0)
            val before = when (heading) {
                1 -> 16f
                2 -> 11f
                else -> 2f
            }
            val after = when (heading) {
                1 -> 9f
                2 -> 7f
                else -> 6f
            }
            ensurePage(false)
            cursorY += before
            for (chunk in chunks(text)) {
                checkActive?.invoke()
                val layout = layoutOf(chunk, paint, contentWidth().toInt())
                var line = 0
                while (line < layout.lineCount) {
                    ensurePage(false)
                    val available = bottom() - cursorY
                    var end = line
                    while (end < layout.lineCount &&
                        layout.getLineBottom(end) - layout.getLineTop(line) <= available
                    ) {
                        end++
                    }
                    if (end == line) {
                        // A single line taller than the page is impossible at these sizes; the
                        // guard only prevents an endless loop on a degenerate page.
                        if (cursorY > MARGIN_TOP + 0.5f) {
                            startPage(false)
                            continue
                        }
                        end = line + 1
                    }
                    val target = canvas
                    if (target != null) {
                        target.save()
                        target.clipRect(
                            MARGIN_SIDE,
                            cursorY,
                            contentRight(),
                            cursorY + available + 1f
                        )
                        target.translate(MARGIN_SIDE, cursorY - layout.getLineTop(line))
                        layout.draw(target)
                        target.restore()
                    }
                    cursorY += layout.getLineBottom(end - 1) - layout.getLineTop(line)
                    line = end
                }
            }
            cursorY += after
        }

        // ---------- tables ----------

        fun drawTable(rows: List<List<String>>) {
            val meaningful = rows.filter { row -> row.any { it.isNotBlank() } }
            if (meaningful.isEmpty()) return
            val columns = meaningful.maxOf { it.size }.coerceAtMost(MAX_TABLE_COLUMNS)
            if (columns == 0) return
            val wide = columns > WIDE_TABLE_COLUMNS
            ensurePage(wide)
            cursorY += 8f
            val widths = columnWidths(meaningful, columns, contentWidth())
            val body = textPaint(TABLE_SIZE, bold = false)
            val head = textPaint(TABLE_SIZE, bold = true)
            val hasHeader = meaningful.size >= 3

            fun drawHeaderRow() {
                cursorY += drawRow(meaningful[0], widths, head, shade = hasHeader, available = bottom() - cursorY)
            }

            drawHeaderRow()
            var index = 1
            var rowsOnPage = 0
            while (index < meaningful.size) {
                checkActive?.invoke()
                val needed = measureRow(meaningful[index], widths, body)
                if (cursorY + needed > bottom() && rowsOnPage > 0) {
                    startPage(wide)
                    drawHeaderRow()
                    rowsOnPage = 0
                }
                cursorY += drawRow(
                    meaningful[index],
                    widths,
                    body,
                    shade = false,
                    available = bottom() - cursorY
                )
                rowsOnPage++
                index++
                onProgress?.invoke(index.toFloat() / meaningful.size)
            }
            if (meaningful.size > 1 && meaningful.maxOf { it.size } > MAX_TABLE_COLUMNS) {
                drawParagraph(
                    "(Only the first $MAX_TABLE_COLUMNS columns are shown.)",
                    heading = 0
                )
            }
            cursorY += 10f
        }

        private fun columnWidths(rows: List<List<String>>, columns: Int, available: Float): FloatArray {
            val sample = rows.take(120)
            val widths = FloatArray(columns) { column ->
                var longest = 4
                for (row in sample) {
                    val length = row.getOrElse(column) { "" }.length
                    if (length > longest) longest = length
                }
                longest.coerceAtMost(34).toFloat()
            }
            val total = widths.sum().coerceAtLeast(1f)
            for (column in 0 until columns) {
                widths[column] = (available * widths[column] / total).coerceAtLeast(24f)
            }
            val sum = widths.sum()
            if (sum > available) {
                val factor = available / sum
                for (column in 0 until columns) widths[column] *= factor
            }
            return widths
        }

        private fun cellLayouts(
            row: List<String>,
            widths: FloatArray,
            paint: TextPaint,
            maxLines: Int
        ): List<StaticLayout> = widths.indices.map { column ->
            val cellWidth = (widths[column] - 2 * CELL_PAD).toInt().coerceAtLeast(8)
            layoutOf(row.getOrElse(column) { "" }, paint, cellWidth, maxLines)
        }

        private fun measureRow(row: List<String>, widths: FloatArray, paint: TextPaint): Float {
            val layouts = cellLayouts(row, widths, paint, maxLines = 0)
            val tallest = layouts.maxOfOrNull { it.height } ?: 0
            return (tallest + 2 * CELL_PAD).coerceAtLeast(MIN_ROW_HEIGHT)
        }

        private fun drawRow(
            row: List<String>,
            widths: FloatArray,
            paint: TextPaint,
            shade: Boolean,
            available: Float
        ): Float {
            val target = canvas ?: return 0f
            val room = available.coerceAtLeast(MIN_ROW_HEIGHT)
            val lineHeight = paint.textSize * LINE_SPACING
            val maxLines = ((room - 2 * CELL_PAD) / lineHeight).toInt().coerceIn(1, 80)
            val layouts = cellLayouts(row, widths, paint, maxLines)
            val rowHeight = ((layouts.maxOfOrNull { it.height } ?: 0) + 2 * CELL_PAD)
                .coerceAtLeast(MIN_ROW_HEIGHT)
                .coerceAtMost(room)
            var x = MARGIN_SIDE
            if (shade) target.drawRect(x, cursorY, x + widths.sum(), cursorY + rowHeight, shadePaint)
            for (column in widths.indices) {
                val layout = layouts[column]
                target.save()
                target.clipRect(
                    x + CELL_PAD,
                    cursorY + CELL_PAD,
                    x + widths[column] - CELL_PAD,
                    cursorY + rowHeight
                )
                target.translate(x + CELL_PAD, cursorY + CELL_PAD)
                layout.draw(target)
                target.restore()
                target.drawRect(x, cursorY, x + widths[column], cursorY + rowHeight, borderPaint)
                x += widths[column]
            }
            return rowHeight
        }
    }

    // ---------- text ----------

    private fun textPaint(size: Float, bold: Boolean): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.BLACK
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layoutOf(
        text: String,
        prototype: TextPaint,
        width: Int,
        maxLines: Int = 0
    ): StaticLayout {
        // StaticLayout keeps a reference to the paint, so every layout owns a copy: mutating a
        // shared paint would resize layouts that are already built.
        val paint = TextPaint(prototype)
        val builder = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(8))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        if (maxLines > 0) {
            builder.setMaxLines(maxLines)
            builder.setEllipsize(TextUtils.TruncateAt.END)
        }
        return builder.build()
    }

    /** Splits an oversized paragraph at a word boundary so one layout never holds a whole file. */
    private fun chunks(text: String): List<String> {
        if (text.length <= PARAGRAPH_CHUNK) return listOf(text)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + PARAGRAPH_CHUNK).coerceAtMost(text.length)
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end)
                if (space > start + PARAGRAPH_CHUNK / 2) end = space
            }
            val part = text.substring(start, end).trim()
            if (part.isNotEmpty()) parts.add(part)
            start = end
        }
        return parts
    }
}
