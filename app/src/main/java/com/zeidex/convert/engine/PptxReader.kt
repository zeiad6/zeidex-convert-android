package com.zeidex.convert.engine

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream

/**
 * Reads a PowerPoint deck (`ppt/slides/slideN.xml`) as an ordered outline: one heading per slide,
 * one paragraph per text frame paragraph. Shapes carry their text in `<a:t>` runs, so that is all
 * the reader needs to give a deck a readable PDF.
 */
object PptxReader {

    private val SLIDE_NAME = Regex("ppt/slides/slide(\\d+)\\.xml")

    fun blocks(stream: InputStream): List<DocxWriter.Block> {
        val slides = sortedMapOf<Int, List<String>>()
        ZipSupport.forEachEntry(stream) { name, entry ->
            val match = SLIDE_NAME.matchEntire(name) ?: return@forEachEntry
            val index = match.groupValues[1].toIntOrNull() ?: return@forEachEntry
            val handler = SlideHandler()
            try {
                XmlSupport.parse(entry, handler)
            } catch (_: org.xml.sax.SAXException) {
                // A damaged slide should not cost the reader the other 40.
                return@forEachEntry
            }
            slides[index] = handler.paragraphs
        }

        val result = mutableListOf<DocxWriter.Block>()
        for ((index, paragraphs) in slides) {
            if (paragraphs.none { it.isNotBlank() }) continue
            result.add(DocxWriter.Block.Paragraph("Slide $index", heading = 2))
            for (paragraph in paragraphs) {
                if (paragraph.isNotBlank()) result.add(DocxWriter.Block.Paragraph(paragraph))
            }
        }
        return result
    }

    private class SlideHandler : DefaultHandler() {
        val paragraphs = mutableListOf<String>()
        private val current = StringBuilder()
        private var inParagraph = false
        private var inText = false

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (localName) {
                "p" -> {
                    current.setLength(0)
                    inParagraph = true
                }

                "t" -> inText = true
                "br" -> if (inParagraph) current.append('\n')
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inParagraph && inText) current.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            when (localName) {
                "t" -> inText = false
                "p" -> {
                    inParagraph = false
                    val text = current.toString().trim()
                    if (text.isNotEmpty()) paragraphs.add(text)
                }
            }
        }
    }
}
