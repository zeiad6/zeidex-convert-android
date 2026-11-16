package com.zeidex.convert.engine

import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Iterates a ZIP archive once, handing each entry to [action].
 *
 * The entry stream is wrapped so a caller (the XML parser, typically) cannot close the archive
 * by closing the entry — `ZipInputStream.nextEntry` would fail afterwards, and the readers that
 * take several parts out of one archive (a whole `.pptx` deck, an `.epub`) need it alive.
 */
internal object ZipSupport {

    fun forEachEntry(stream: InputStream, action: (String, InputStream) -> Unit) {
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    try {
                        action(entry.name, EntryStream(zip))
                    } finally {
                        zip.closeEntry()
                    }
                } else {
                    zip.closeEntry()
                }
            }
        }
    }

    private class EntryStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun close() {
            // The archive owns the entry; closing it here would end the iteration.
        }
    }
}
