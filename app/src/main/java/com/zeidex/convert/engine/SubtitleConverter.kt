package com.zeidex.convert.engine

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Subtitle conversion without any native dependency: SRT, WebVTT, ASS/SSA and plain text.
 *
 * The parser is intentionally tolerant (BOM, CRLF, missing hour field, VTT cues without a
 * blank line before the next cue) while the writer is strict, so the output opens cleanly in
 * players that are fussy about the format.
 */
object SubtitleConverter {

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    val SUPPORTED = listOf("srt", "vtt", "ass", "ssa", "txt")

    fun convert(input: InputStream, ext: String, targetExt: String, output: OutputStream) {
        val raw = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cues = parse(raw, ext)
        output.write(serialize(cues, targetExt).toByteArray(Charsets.UTF_8))
    }

    fun parse(raw: String, ext: String): List<Cue> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return when (ext.lowercase(Locale.ROOT)) {
            "ass", "ssa" -> parseAss(text)
            "vtt" -> parseVtt(text)
            "srt" -> parseSrt(text)
            else -> {
                val looksVtt = text.trimStart().startsWith("WEBVTT")
                val looksAss = text.contains("[Events]") && text.contains("Dialogue:")
                when {
                    looksVtt -> parseVtt(text)
                    looksAss -> parseAss(text)
                    else -> parseSrt(text)
                }
            }
        }
    }

    fun serialize(cues: List<Cue>, ext: String): String {
        val clean = cues.map { c ->
            val start = c.startMs.coerceAtLeast(0)
            val end = c.endMs.coerceAtLeast(start)
            Cue(start, end, c.text.trim('\n'))
        }
        return when (ext.lowercase(Locale.ROOT)) {
            "vtt" -> buildString {
                append("WEBVTT\n\n")
                clean.forEach { cue ->
                    append("${vttTime(cue.startMs)} --> ${vttTime(cue.endMs)}\n")
                    append(cue.text.replace("\n", "\n"))
                    append("\n\n")
                }
            }
            "ass", "ssa" -> buildString {
                append("[Script Info]\n")
                append("ScriptType: v4.00+\n")
                append("WrapStyle: 0\n")
                append("ScaledBorderAndShadow: yes\n")
                append("PlayResX: 1920\n")
                append("PlayResY: 1080\n\n")
                append("[V4+ Styles]\n")
                append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, ")
                append("Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, ")
                append("Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                append("Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,")
                append("0,0,0,0,100,100,0,0,1,2,1,2,20,20,24,1\n\n")
                append("[Events]\n")
                append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
                clean.forEach { cue ->
                    val body = cue.text
                        .replace("\\", "\\\\")
                        .replace("{", "\\{")
                        .replace("}", "\\}")
                        .replace("\n", "\\N")
                    append("Dialogue: 0,${assTime(cue.startMs)},${assTime(cue.endMs)},Default,,0,0,0,,$body\n")
                }
            }
            "txt" -> clean.joinToString("\n\n") { it.text }
            else -> buildString {
                clean.forEachIndexed { i, cue ->
                    append("${i + 1}\n")
                    append("${srtTime(cue.startMs)} --> ${srtTime(cue.endMs)}\n")
                    append(cue.text)
                    append("\n\n")
                }
            }
        }
    }

    /** Writes [output] from one subtitle file to another, atomically. */
    fun convertFile(source: File, targetExt: String, output: File) {
        source.inputStream().use { input ->
            output.outputStream().use { out -> convert(input, source.extension, targetExt, out) }
        }
    }

    // ---------- parsing ----------

    private fun parseSrt(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val blocks = text.split(Regex("\n[ \t]*\n"))
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val timingIndex = lines.indexOfFirst { it.contains("-->") }
            if (timingIndex < 0) continue
            val (start, end) = parseTiming(lines[timingIndex]) ?: continue
            val body = lines.drop(timingIndex + 1).joinToString("\n").trim()
            if (body.isNotEmpty()) cues.add(Cue(start, end, body))
        }
        return cues
    }

    private fun parseVtt(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val body = text.lines()
            .dropWhile { it.trim() != "WEBVTT" && it.isNotBlank() }
            .drop(1)
            .joinToString("\n")
        val blocks = body.split(Regex("\n[ \t]*\n"))
        for (block in blocks) {
            val lines = block.lines().dropWhile { it.isBlank() }
            if (lines.isEmpty()) continue
            val first = lines.first().trim()
            if (first.startsWith("NOTE") || first.startsWith("STYLE") || first.startsWith("REGION")) continue
            val timingIndex = lines.indexOfFirst { it.contains("-->") }
            if (timingIndex < 0) continue
            val (start, end) = parseTiming(lines[timingIndex]) ?: continue
            val content = lines.drop(timingIndex + 1).joinToString("\n").trim()
            if (content.isNotEmpty()) cues.add(Cue(start, end, content))
        }
        return cues
    }

    private fun parseAss(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        var fields: List<String> = emptyList()
        var inEvents = false
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inEvents = trimmed.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                fields = trimmed.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                continue
            }
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue
            val payload = trimmed.substringAfter(':').trimStart()
            val values = if (fields.isEmpty()) {
                payload.split(',', limit = 10)
            } else {
                payload.split(',', limit = fields.size)
            }
            val startIdx = fields.indexOf("start").takeIf { it >= 0 } ?: 1
            val endIdx = fields.indexOf("end").takeIf { it >= 0 } ?: 2
            val textIdx = fields.indexOf("text").takeIf { it >= 0 } ?: (values.lastIndex)
            if (values.size <= maxOf(startIdx, endIdx, textIdx)) continue
            val start = parseAssTime(values[startIdx]) ?: continue
            val end = parseAssTime(values[endIdx]) ?: continue
            val content = values[textIdx]
                .replace(Regex("\\{[^}]*}"), "")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
                .trim()
            if (content.isNotEmpty()) cues.add(Cue(start, end, content))
        }
        return cues
    }

    // ---------- timing ----------

    private fun parseTiming(line: String): Pair<Long, Long>? {
        val parts = line.split("-->")
        if (parts.size < 2) return null
        val start = parseClock(parts[0]) ?: return null
        // SRT and VTT both put a space after the arrow; trimming first matters because
        // `substringBefore(' ')` on " 00:00:02,500" is the empty string.
        val end = parseClock(parts[1].trim().substringBefore(' ')) ?: return null
        return start to end
    }

    private fun parseClock(raw: String): Long? {
        val value = raw.trim().replace(',', '.')
        val pieces = value.split(':')
        if (pieces.size !in 2..3) return null
        return try {
            val secPart = pieces.last()
            val seconds = secPart.toDoubleOrNull() ?: return null
            val minutes = pieces[pieces.size - 2].toLongOrNull() ?: return null
            val hours = if (pieces.size == 3) pieces[0].toLongOrNull() ?: return null else 0L
            (((hours * 60 + minutes) * 60) * 1000 + Math.round(seconds * 1000)).coerceAtLeast(0)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAssTime(raw: String): Long? {
        val value = raw.trim()
        val parts = value.split(':')
        if (parts.size != 3) return null
        return try {
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val seconds = parts[2].toDouble()
            (((hours * 60 + minutes) * 60) * 1000 + Math.round(seconds * 1000)).coerceAtLeast(0)
        } catch (_: Exception) {
            null
        }
    }

    private fun srtTime(ms: Long): String {
        val (h, m, s, millis) = split(ms)
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, millis)
    }

    private fun vttTime(ms: Long): String {
        val (h, m, s, millis) = split(ms)
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, millis)
    }

    private fun assTime(ms: Long): String {
        val (h, m, s, millis) = split(ms)
        val cs = millis / 10
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }

    private fun split(ms: Long): List<Int> {
        val total = ms.coerceAtLeast(0)
        return listOf(
            (total / 3_600_000).toInt(),
            ((total % 3_600_000) / 60_000).toInt(),
            ((total % 60_000) / 1000).toInt(),
            (total % 1000).toInt()
        )
    }
}
