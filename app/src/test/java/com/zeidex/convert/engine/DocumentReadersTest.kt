package com.zeidex.convert.engine

import com.zeidex.convert.model.Category
import com.zeidex.convert.model.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The readers run on the JVM: they deliberately touch neither Android nor the filesystem, so the
 * formats that used to fail on-device (a spreadsheet, a deck, an OpenDocument file) are covered
 * by tests instead of by hope.
 */
class DocumentReadersTest {

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun source(bytes: ByteArray): () -> java.io.InputStream = { ByteArrayInputStream(bytes) }

    // ---------- xlsx ----------

    private fun workbookZip(): ByteArray = archive(
        "xl/workbook.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <workbookPr/>
              <sheets>
                <sheet name="Budget" sheetId="1" r:id="rId1"/>
                <sheet name="Notes" sheetId="2" r:id="rId2"/>
              </sheets>
            </workbook>
        """.trimIndent(),
        "xl/_rels/workbook.xml.rels" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/>
              <Relationship Id="rId2" Type="worksheet" Target="worksheets/sheet2.xml"/>
            </Relationships>
        """.trimIndent(),
        "xl/sharedStrings.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><t>Item</t></si>
              <si><r><t>Tota</t></r><r><t>l</t></r></si>
              <si><t>مرحبا</t></si>
            </sst>
        """.trimIndent(),
        "xl/styles.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <numFmts><numFmt numFmtId="164" formatCode="#,##0.00 &quot;SAR&quot;"/></numFmts>
              <cellXfs count="3">
                <xf numFmtId="0"/>
                <xf numFmtId="14"/>
                <xf numFmtId="164"/>
              </cellXfs>
            </styleSheet>
        """.trimIndent(),
        "xl/worksheets/sheet1.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2"><v>42</v></c><c r="B2" s="1"><v>45292</v></c></row>
                <row r="3"><c r="A3" s="2"><v>1234.5</v></c><c r="C3" t="s"><v>2</v></c></row>
                <row r="4"><c r="A4" t="inlineStr"><is><t>inline</t></is></c><c r="B4" t="b"><v>1</v></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent(),
        "xl/worksheets/sheet2.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData><row r="1"><c r="A1"><v>7</v></c></row></sheetData>
            </worksheet>
        """.trimIndent()
    )

    @Test
    fun spreadsheetIsReadWithSheetNamesNumbersDatesAndStrings() {
        val sheets = XlsxReader.read(source(workbookZip()))

        assertEquals(listOf("Budget", "Notes"), sheets.map { it.name })
        val budget = sheets.first().rows
        assertEquals(listOf("Item", "Total"), budget[0])
        assertEquals(listOf("42", "2024-01-01"), budget[1])
        // A date is a date because of the style, the custom currency format is not.
        assertEquals(listOf("1234.5", "", "مرحبا"), budget[2])
        assertEquals(listOf("inline", "TRUE"), budget[3])
        assertEquals(listOf("7"), sheets[1].rows.single())
    }

    @Test
    fun spreadsheetKeepsRaggedRowsAligned() {
        val rows = XlsxReader.read(source(workbookZip())).first().rows
        assertEquals(3, rows[2].size)
        assertEquals("", rows[2][1])
    }

    // ---------- docx ----------

    @Test
    fun wordDocumentKeepsHeadingsAndTables() {
        val zip = archive("word/document.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Report</w:t></w:r></w:p>
                <w:p><w:r><w:t>Body </w:t></w:r><w:r><w:t>text</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr>
                  <w:tr><w:tc><w:p><w:r><w:t>1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>2</w:t></w:r></w:p></w:tc></w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent())

        val blocks = DocxReader.blocks(ByteArrayInputStream(zip))

        assertEquals(3, blocks.size)
        assertEquals(DocxWriter.Block.Paragraph("Report", 1), blocks[0])
        assertEquals(DocxWriter.Block.Paragraph("Body text"), blocks[1])
        assertEquals(DocxWriter.Block.Table(listOf(listOf("A", "B"), listOf("1", "2"))), blocks[2])
    }

    // ---------- pptx ----------

    @Test
    fun deckIsReadSlideBySlideInOrder() {
        fun slide(vararg lines: String) = """
            <?xml version="1.0" encoding="UTF-8"?>
            <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                   xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
              <p:cSld><p:spTree><p:sp><p:txBody>
                ${lines.joinToString("") { "<a:p><a:r><a:t>$it</a:t></a:r></a:p>" }}
              </p:txBody></p:sp></p:spTree></p:cSld>
            </p:sld>
        """.trimIndent()

        val zip = archive(
            "ppt/slides/slide10.xml" to slide("Later"),
            "ppt/slides/slide2.xml" to slide("First", "Second")
        )

        val blocks = PptxReader.blocks(ByteArrayInputStream(zip))
        assertEquals(
            listOf(
                DocxWriter.Block.Paragraph("Slide 2", 2),
                DocxWriter.Block.Paragraph("First"),
                DocxWriter.Block.Paragraph("Second"),
                DocxWriter.Block.Paragraph("Slide 10", 2),
                DocxWriter.Block.Paragraph("Later")
            ),
            blocks
        )
    }

    // ---------- odt ----------

    @Test
    fun openDocumentKeepsHeadingsSpacesAndTables() {
        val zip = archive("content.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                     xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                                     xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0">
              <office:body><office:text>
                <text:h text:outline-level="1">Heading</text:h>
                <text:p>Hello<text:s text:c="2"/>world</text:p>
                <table:table>
                  <table:table-row>
                    <table:table-cell><text:p>A</text:p></table:table-cell>
                    <table:table-cell><text:p>B</text:p></table:table-cell>
                  </table:table-row>
                  <table:table-row>
                    <table:table-cell><text:p>1</text:p></table:table-cell>
                    <table:table-cell><text:p>2</text:p></table:table-cell>
                  </table:table-row>
                </table:table>
              </office:text></office:body>
            </office:document-content>
        """.trimIndent())

        val blocks = OdtReader.blocks(ByteArrayInputStream(zip))
        assertEquals(DocxWriter.Block.Paragraph("Heading", 1), blocks[0])
        assertEquals(DocxWriter.Block.Paragraph("Hello  world"), blocks[1])
        assertEquals(DocxWriter.Block.Table(listOf(listOf("A", "B"), listOf("1", "2"))), blocks[2])
    }

    @Test
    fun openDocumentKeepsColumnsAlignedAcrossRepeatedEmptyCells() {
        // LibreOffice writes empty spans as counts (a trailing 1024 is normal); expanding them
        // would shift every later column and inflate the row.
        val zip = archive("content.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                     xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                                     xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0">
              <office:body><office:text><table:table>
                <table:table-row>
                  <table:table-cell table:number-columns-repeated="4"/>
                  <table:table-cell><text:p>12.5</text:p></table:table-cell>
                  <table:table-cell table:number-columns-repeated="1024"/>
                </table:table-row>
              </table:table></office:text></office:body>
            </office:document-content>
        """.trimIndent())

        val table = OdtReader.blocks(ByteArrayInputStream(zip)).filterIsInstance<DocxWriter.Block.Table>().single()

        assertEquals(listOf(listOf("", "", "", "", "12.5")), table.rows)
    }

    // ---------- rtf ----------

    @Test
    fun rtfDecodesUnicodeEscapesAndCodePageBytes() {
        val rtf = """
            {\rtf1\ansi\ansicpg1256{\fonttbl{\f0 Arial;}}{\colortbl;\red0\green0\blue0;}
            \f0\fs24 Hello \u1575?\u1604?\u1593?\u1585?\u1576?\u1610?\u1577? caf\'e9\par
            Second line}
        """.trimIndent()

        val text = RtfReader.toText(rtf.toByteArray(Charsets.UTF_8))

        assertTrue(text.contains("Hello العربية"))
        assertTrue(text.contains("café"))
        assertTrue(text.contains("\nSecond line"))
        assertFalse(text.contains("Arial"))
    }

    // ---------- html / csv ----------

    @Test
    fun htmlDropsMarkupAndScriptsButKeepsStructure() {
        val html = """
            <html><head><title>Hidden</title><style>p{color:red}</style></head>
            <body>
              <h1>Title &amp; more</h1>
              <p>First&nbsp;paragraph with &lt;b&gt;markup&lt;/b&gt;.</p>
              <ul><li>One</li><li>Two</li></ul>
              <script>console.log("nope")</script>
            </body></html>
        """.trimIndent()

        val text = HtmlText.toText(html)

        assertTrue(text.startsWith("# Title & more"))
        assertTrue(text.contains("First paragraph with <b>markup</b>."))
        assertTrue(text.contains("- One"))
        assertTrue(text.contains("- Two"))
        assertFalse(text.contains("nope"))
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("<p>"))
    }

    @Test
    fun csvDetectsSemicolonsAndHonoursQuotes() {
        val csv = "name;value\n\"Smith; John\";42\n\"multi\nline\";\"a \"\"quoted\"\" word\"\n"
        val rows = CsvReader.rows(csv)

        assertEquals(listOf("name", "value"), rows[0])
        assertEquals(listOf("Smith; John", "42"), rows[1])
        assertEquals(listOf("multi\nline", "a \"quoted\" word"), rows[2])
    }

    @Test
    fun csvPrefersTabsWhenEveryLineHasThem() {
        assertEquals('\t', CsvReader.detect("a\tb\tc\n1\t2,5\t3"))
        assertEquals(';', CsvReader.detect("a;b;c\n1;2;3"))
    }

    // ---------- formats ----------

    @Test
    fun documentsCanBeConvertedToPdf() {
        val targets = Format.targetsFor(Category.DOCUMENT, "xlsx")
        assertTrue(targets.any { it.ext == "pdf" })
        assertTrue(targets.any { it.ext == "docx" })
        // The current extension is never offered as a target.
        assertFalse(targets.any { it.ext == "xlsx" })
    }
}
