package com.zeidex.convert.engine

/**
 * Turns HTML into readable text.
 *
 * Real-world HTML is rarely well-formed XML, so the converter works on tags rather than a parser:
 * block-level elements become paragraph breaks, table cells become tabs and headings keep their
 * level, then the markup is dropped and entities are decoded. Script and style bodies are removed
 * before the tags are stripped, otherwise a page arrives with its JavaScript in the middle.
 */
object HtmlText {

    fun toBlocks(html: String): List<DocxWriter.Block> = DocxWriter.blocksFromText(toText(html))

    fun toText(html: String): String {
        var text = html
        text = text.replace(Regex("(?is)<!--.*?-->"), " ")
        text = text.replace(Regex("(?is)<(script|style|noscript|head)[^>]*>.*?</\\1\\s*>"), " ")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?i)</?(p|div|section|article|aside|header|footer|main|nav|table|ul|ol|blockquote|pre|figure|figcaption)\\b[^>]*>"), "\n\n")
        text = text.replace(Regex("(?i)<tr\\b[^>]*>"), "\n")
        text = text.replace(Regex("(?i)<li\\b[^>]*>"), "\n- ")
        text = text.replace(Regex("(?i)</li\\s*>"), "\n")
        text = text.replace(Regex("(?i)<h[12]\\b[^>]*>"), "\n\n# ")
        text = text.replace(Regex("(?i)<h[3-6]\\b[^>]*>"), "\n\n## ")
        text = text.replace(Regex("(?i)</h[1-6]\\s*>"), "\n")
        text = text.replace(Regex("(?i)<(td|th)\\b[^>]*>"), "\t")
        text = text.replace(Regex("<[^>]*>"), "")
        return normalize(unescape(text))
    }

    /** Collapses runs of blank lines and horizontal whitespace without touching word order. */
    private fun normalize(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .map { it.replace(Regex("[\\t ]+"), " ").trim() }
        val out = StringBuilder()
        var blank = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blank++
                if (blank <= 1 && out.isNotEmpty()) out.append('\n')
            } else {
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                out.append(line).append('\n')
                blank = 0
            }
        }
        return out.toString().trim()
    }

    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bull" to "•",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "middot" to "·",
        "times" to "×", "divide" to "÷", "plusmn" to "±", "frac12" to "½", "euro" to "€",
        "pound" to "£", "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶",
        "dagger" to "†", "permil" to "‰", "prime" to "′", "Prime" to "″", "ensp" to " ",
        "emsp" to " ", "thinsp" to " ", "shy" to "", "zwnj" to "", "zwj" to ""
    )

    private fun unescape(text: String): String {
        if (!text.contains('&')) return text
        return Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z][a-zA-Z0-9]*);").replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value

                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value

                else -> NAMED[body] ?: match.value
            }
        }
    }
}
