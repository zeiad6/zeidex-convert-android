package com.zeidex.convert.engine

import com.zeidex.convert.engine.XmlSupport.attr
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Reads `.xlsx`/`.xlsm` with no spreadsheet library.
 *
 * The format is a ZIP holding `xl/workbook.xml` (sheet names and their part names),
 * `xl/sharedStrings.xml` (the string table the cells point at by index), `xl/styles.xml`
 * (number formats) and one `xl/worksheets/sheetN.xml` per sheet. Values are resolved to the
 * text a user would see in Excel — including serial dates, so a date column reads `2024-01-31`
 * instead of `45322`.
 *
 * The archive is streamed twice: the small parts first (strings and styles must be known before
 * any cell is decoded), then the worksheets. Both passes are bounded so a pathological file
 * cannot exhaust the heap on a phone.
 */
object XlsxReader {

    data class Sheet(
        val name: String,
        val rows: List<List<String>>,
        /** The sheet had more rows or cells than the reader keeps. */
        val truncated: Boolean = false
    )

    /** Enough for a readable export; a 100k-row sheet becomes a 2000-page PDF nobody wants. */
    private const val MAX_ROWS = 20_000
    private const val MAX_CELLS = 120_000
    private const val MAX_COLUMNS = 128

    /** A shared-string table larger than this belongs in a database, not in a phone's memory. */
    private const val MAX_SHARED_STRINGS = 400_000

    private val BUILTIN_DATE_FORMATS = setOf(
        14, 15, 16, 17, 18, 19, 20, 21, 22,
        27, 28, 29, 30, 31, 32, 33, 34, 35, 36,
        45, 46, 47,
        50, 51, 52, 53, 54, 55, 56, 57, 58
    )

    /**
     * @param open a factory that returns a fresh stream on every call — the archive is read twice.
     */
    fun read(open: () -> InputStream): List<Sheet> {
        val refs = mutableListOf<SheetRef>()
        val relationships = mutableMapOf<String, String>()
        val shared = mutableListOf<String>()
        var dateStyles: List<Boolean> = emptyList()
        var date1904 = false

        streamParts(open) { name, entry ->
            when (name) {
                "xl/workbook.xml" -> {
                    val handler = WorkbookHandler()
                    parse(entry, handler)
                    date1904 = handler.date1904
                    refs.addAll(handler.sheets)
                }

                "xl/_rels/workbook.xml.rels" -> relationships.putAll(parseRelationships(entry))

                "xl/sharedStrings.xml" -> {
                    val handler = StringsHandler()
                    parse(entry, handler)
                    shared.addAll(handler.values)
                }

                "xl/styles.xml" -> {
                    val handler = StylesHandler()
                    parse(entry, handler)
                    dateStyles = handler.dateStyles()
                }
            }
        }

        val nameByPath = LinkedHashMap<String, String>()
        for (ref in refs) {
            val target = relationships[ref.relId] ?: "worksheets/sheet${ref.sheetId}.xml"
            nameByPath[resolvePath(target)] = ref.name
        }

        val found = LinkedHashMap<String, Sheet>()
        var unnamed = 0
        streamParts(open) { name, entry ->
            val path = name.removePrefix("/")
            if (path.startsWith("xl/worksheets/") && path.endsWith(".xml") && !path.contains("/_rels/")) {
                val sheetName = nameByPath[path] ?: "Sheet ${++unnamed}"
                val handler = SheetHandler(shared, dateStyles, date1904)
                parse(entry, handler)
                found[path] = Sheet(sheetName, handler.rows(), handler.truncated)
            }
        }

        val ordered = nameByPath.keys.mapNotNull { found[it] }
        val leftovers = found.filterKeys { it !in nameByPath }.values
        return ordered + leftovers
    }

    // ---------- archive ----------

    private fun streamParts(open: () -> InputStream, action: (String, InputStream) -> Unit) {
        open().use { raw ->
            try {
                ZipSupport.forEachEntry(raw, action)
            } catch (e: java.util.zip.ZipException) {
                throw IllegalStateException("This file is not a valid Excel workbook (${e.message})")
            }
        }
    }

    private fun parse(stream: InputStream, handler: DefaultHandler) {
        try {
            XmlSupport.parse(stream, handler)
        } catch (e: org.xml.sax.SAXException) {
            throw XmlSupport.wrap(e, "the workbook XML")
        }
    }

    private fun parseRelationships(stream: InputStream): Map<String, String> {
        val map = mutableMapOf<String, String>()
        XmlSupport.parse(stream, object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                if (localName != "Relationship") return
                val id = attributes.attr("Id") ?: return
                val target = attributes.attr("Target") ?: return
                map[id] = target
            }
        })
        return map
    }

    private fun resolvePath(target: String): String {
        val clean = target.removePrefix("/")
        return when {
            clean.startsWith("xl/") -> clean
            clean.startsWith("../") -> clean.removePrefix("../").let { "xl/$it" }
            else -> "xl/$clean"
        }
    }

    // ---------- workbook ----------

    private data class SheetRef(val name: String, val relId: String, val sheetId: String)

    private class WorkbookHandler : DefaultHandler() {
        val sheets = mutableListOf<SheetRef>()
        var date1904 = false
            private set

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (localName) {
                "workbookPr" -> {
                    val value = attributes.attr("date1904") ?: return
                    date1904 = value == "1" || value.equals("true", ignoreCase = true)
                }

                "sheet" -> {
                    val name = attributes.attr("name") ?: return
                    sheets.add(
                        SheetRef(
                            name = name,
                            relId = attributes.attr("id") ?: "",
                            sheetId = attributes.attr("sheetId") ?: "${sheets.size + 1}"
                        )
                    )
                }
            }
        }
    }

    private class StringsHandler : DefaultHandler() {
        val values = mutableListOf<String>()
        private val current = StringBuilder()
        private var inItem = false
        private var inText = false
        private var phoneticDepth = 0

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (localName) {
                "si" -> {
                    current.setLength(0)
                    inItem = true
                }

                "rPh" -> phoneticDepth++
                "t" -> if (inItem && phoneticDepth == 0) inText = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inItem && inText && phoneticDepth == 0) current.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            when (localName) {
                "t" -> inText = false
                "rPh" -> if (phoneticDepth > 0) phoneticDepth--
                "si" -> if (inItem) {
                    if (values.size < MAX_SHARED_STRINGS) values.add(current.toString())
                    inItem = false
                }
            }
        }
    }

    private class StylesHandler : DefaultHandler() {
        private val custom = mutableMapOf<Int, String>()
        private val xfFormats = mutableListOf<Int>()
        private var inCellFormats = false

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (localName) {
                "numFmt" -> {
                    val id = attributes.attr("numFmtId")?.toIntOrNull() ?: return
                    custom[id] = attributes.attr("formatCode") ?: ""
                }

                "cellXfs" -> inCellFormats = true
                "xf" -> if (inCellFormats) xfFormats.add(attributes.attr("numFmtId")?.toIntOrNull() ?: 0)
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (localName == "cellXfs") inCellFormats = false
        }

        fun dateStyles(): List<Boolean> = xfFormats.map { isDate(it) }

        private fun isDate(formatId: Int): Boolean {
            if (formatId in BUILTIN_DATE_FORMATS) return true
            val code = custom[formatId] ?: return false
            return looksLikeDateFormat(code)
        }
    }

    /**
     * `yyyy-mm-dd`, `d/m/yy h:mm` and `[$-409]mmm d` are dates; `#,##0.00 " kg"` is not.
     * Literal sections (`"..."`) and locale prefixes (`[...]`) are removed before looking for
     * date letters, otherwise a currency format with a `d` in its label would be a date.
     */
    private fun looksLikeDateFormat(code: String): Boolean {
        val stripped = StringBuilder()
        var inQuotes = false
        var inBracket = false
        var index = 0
        while (index < code.length) {
            val ch = code[index]
            when {
                inQuotes -> if (ch == '"') inQuotes = false
                inBracket -> if (ch == ']') inBracket = false
                ch == '"' -> inQuotes = true
                ch == '[' -> inBracket = true
                ch == '\\' -> index++
                else -> stripped.append(ch.lowercaseChar())
            }
            index++
        }
        val cleaned = stripped.toString()
        return cleaned.any { it == 'y' || it == 'd' } || (cleaned.contains('m') && cleaned.contains(':'))
    }

    // ---------- cells ----------

    private class SheetHandler(
        private val shared: List<String>,
        private val dateStyles: List<Boolean>,
        private val date1904: Boolean
    ) : DefaultHandler() {

        private val rows = mutableListOf<List<String>>()
        private var row = mutableListOf<String>()
        private var rowIndex = 0
        private var column = -1
        private var type = ""
        private var style = -1
        private val value = StringBuilder()
        private val inline = StringBuilder()
        private var inValue = false
        private var inInlineText = false
        private var cells = 0

        var truncated = false
            private set

        fun rows(): List<List<String>> = rows

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (localName) {
                "row" -> {
                    row = mutableListOf()
                    rowIndex = attributes.attr("r")?.toIntOrNull() ?: (rows.size + 1)
                }

                "c" -> {
                    column = attributes.attr("r")?.let { columnIndex(it) } ?: row.size
                    type = attributes.attr("t") ?: ""
                    style = attributes.attr("s")?.toIntOrNull() ?: -1
                }

                "v" -> inValue = true
                "t" -> if (column >= 0 && type == "inlineStr") inInlineText = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            when {
                inValue -> value.append(ch, start, length)
                inInlineText -> inline.append(ch, start, length)
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            when (localName) {
                "v" -> inValue = false
                "t" -> inInlineText = false
                "c" -> finishCell()
                "row" -> finishRow()
            }
        }

        private fun finishCell() {
            if (column >= 0) {
                val raw = if (type == "inlineStr") inline.toString() else value.toString()
                val text = cellText(raw, type, style)
                if (text.isNotEmpty()) {
                    if (column < MAX_COLUMNS && cells < MAX_CELLS) {
                        while (row.size <= column) row.add("")
                        row[column] = text
                        cells++
                    } else {
                        truncated = true
                    }
                }
            }
            column = -1
            type = ""
            style = -1
            value.setLength(0)
            inline.setLength(0)
            inValue = false
            inInlineText = false
        }

        private fun finishRow() {
            if (row.isEmpty()) return
            val target = rowIndex - 1
            if (target >= MAX_ROWS) {
                truncated = true
                return
            }
            if (target > rows.size) {
                while (rows.size < target && rows.size < MAX_ROWS) rows.add(emptyList())
            }
            if (target == rows.size) {
                rows.add(row.toList())
            } else if (target in rows.indices) {
                rows[target] = row.toList()
            }
            row = mutableListOf()
        }

        private fun cellText(raw: String, type: String, style: Int): String = when (type) {
            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrElse(it) { "" } } ?: ""
            "inlineStr", "str" -> raw.trim()
            // OOXML also allows a cell that already holds an ISO date string.
            "d" -> raw.trim().replace('T', ' ').substringBefore('.').removeSuffix(" 00:00:00")
            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
            "e" -> raw.trim()
            else -> numberText(raw, style)
        }

        private fun numberText(raw: String, style: Int): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            val number = trimmed.toDoubleOrNull() ?: return trimmed
            if (style >= 0 && dateStyles.getOrElse(style) { false }) return excelDate(number)
            return try {
                if (number == number.toLong().toDouble() && kotlin.math.abs(number) < 1e15) {
                    number.toLong().toString()
                } else {
                    BigDecimal(trimmed).stripTrailingZeros().toPlainString()
                }
            } catch (_: NumberFormatException) {
                trimmed
            }
        }

        /**
         * Excel counts days from 1899-12-30 (the 1900 leap-year bug is baked into that base);
         * the 1904 variant is used by files written on old Macs. The fractional part is the time.
         */
        private fun excelDate(serial: Double): String {
            if (!serial.isFinite() || serial < 0) return serial.toString()
            val days = floor(serial).toLong()
            val base = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30)
            val date = base.plusDays(days)
            val fraction = serial - days
            if (fraction < 1.0 / 86400.0) return date.toString()
            // A serial below 1 carries a time of day and no date at all.
            if (days == 0L) {
                return LocalTime.of(
                    ((serial * 24).toInt()) % 24,
                    ((serial * 1440).toInt()) % 60,
                    ((serial * 86400).roundToLong() % 60).toInt()
                ).toString()
            }
            val seconds = (fraction * 86400.0).roundToLong()
            val time = LocalTime.of(
                ((seconds / 3600) % 24).toInt(),
                ((seconds / 60) % 60).toInt(),
                (seconds % 60).toInt()
            )
            return "$date $time"
        }
    }

    /** `B12` → 1. Only the leading letters count; the row part is ignored. */
    private fun columnIndex(reference: String): Int {
        var column = 0
        for (ch in reference) {
            if (ch.isLetter()) {
                column = column * 26 + (ch.uppercaseChar() - 'A' + 1)
            } else if (column > 0) {
                break
            }
        }
        return column - 1
    }
}
