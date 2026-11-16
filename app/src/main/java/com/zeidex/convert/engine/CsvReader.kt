package com.zeidex.convert.engine

/**
 * Delimited text (`.csv`, `.tsv`) as rows.
 *
 * The separator is detected rather than assumed: a file exported by Excel on an Arabic or
 * European locale separates with `;`, and splitting it on commas would put an entire row in one
 * cell. Quoted fields keep their embedded separators and line breaks.
 */
object CsvReader {

    const val MAX_ROWS = 20_000

    fun rows(text: String, preferredDelimiter: Char? = null): List<List<String>> {
        val body = text.removePrefix("\uFEFF")
        val delimiter = preferredDelimiter ?: detect(body)
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        while (index < body.length && rows.size < MAX_ROWS) {
            val ch = body[index]
            when {
                quoted -> when {
                    ch == '"' && index + 1 < body.length && body[index + 1] == '"' -> {
                        field.append('"')
                        index++
                    }

                    ch == '"' -> quoted = false
                    else -> field.append(ch)
                }

                ch == '"' && field.isEmpty() -> quoted = true
                ch == delimiter -> {
                    row.add(field.toString())
                    field.setLength(0)
                }

                ch == '\n' -> {
                    row.add(field.toString())
                    field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row.toList())
                    row.clear()
                }

                ch == '\r' -> {
                    // CRLF: the LF that follows would close an empty row.
                    if (index + 1 < body.length && body[index + 1] == '\n') index++
                    row.add(field.toString())
                    field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row.toList())
                    row.clear()
                }

                else -> field.append(ch)
            }
            index++
        }

        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotBlank() }) rows.add(row.toList())
        }
        return rows
    }

    fun toBlocks(text: String, preferredDelimiter: Char? = null): List<DocxWriter.Block> {
        val parsed = rows(text, preferredDelimiter)
        if (parsed.isEmpty()) return emptyList()
        return listOf(DocxWriter.Block.Table(parsed))
    }

    /**
     * The delimiter that appears most often in the first lines. Tab wins outright when every
     * sample line has one: a `.tsv` whose text contains commas is otherwise misread.
     */
    fun detect(text: String): Char {
        val sample = text.lineSequence()
            .filter { it.isNotBlank() }
            .take(5)
            .map { it.replace(Regex("\"[^\"]*\""), "") }
            .toList()
        if (sample.isEmpty()) return ','
        if (sample.all { it.contains('\t') }) return '\t'
        val candidates = charArrayOf(',', ';', '|')
        var best = ','
        var bestCount = 0
        for (candidate in candidates) {
            val count = sample.sumOf { line -> line.count { it == candidate } }
            if (count > bestCount) {
                best = candidate
                bestCount = count
            }
        }
        return best
    }
}
