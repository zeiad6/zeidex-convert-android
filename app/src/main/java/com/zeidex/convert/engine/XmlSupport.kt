package com.zeidex.convert.engine

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * SAX plumbing shared by the OOXML and OpenDocument readers.
 *
 * Office files are XML inside a ZIP, and a single worksheet can be tens of megabytes, so the
 * readers parse a stream instead of building a DOM. External entities are resolved to nothing:
 * a file the user picked must never be able to read another file on the device.
 */
internal object XmlSupport {

    /**
     * An element's attribute by local name, falling back to the qualified name for parsers
     * that report no namespaces. Office XML mixes prefixed (`r:id`) and plain attributes.
     */
    fun Attributes.attr(name: String): String? {
        for (index in 0 until length) {
            val local = getLocalName(index)
            if (!local.isNullOrEmpty()) {
                if (local == name) return getValue(index)
            } else {
                val qualified = getQName(index) ?: continue
                if (qualified == name || qualified.substringAfter(':') == name) return getValue(index)
            }
        }
        return null
    }

    fun parse(stream: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.errorHandler = StrictErrors(handler)
        // No DOCTYPE, no external entity: the document is data, not a program.
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        try {
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (_: Exception) {
            // Older Android parsers expose a subset of the features; the resolver above still
            // blocks the network and the filesystem.
        }
        reader.parse(InputSource(stream))
    }

    /** Reports fatal errors instead of silently returning half a document. */
    private class StrictErrors(private val delegate: DefaultHandler) :
        org.xml.sax.ErrorHandler {
        override fun warning(exception: SAXParseException) {
            delegate.warning(exception)
        }

        override fun error(exception: SAXParseException) {
            delegate.error(exception)
        }

        override fun fatalError(exception: SAXParseException): Unit = throw exception
    }

    fun wrap(error: SAXException, what: String): IllegalStateException =
        IllegalStateException("Cannot read $what: ${error.message ?: "malformed XML"}")
}
