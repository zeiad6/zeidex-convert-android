package com.zeidex.convert.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SubtitleConverterTest {

    private fun convert(raw: String, from: String, to: String): String {
        val out = ByteArrayOutputStream()
        SubtitleConverter.convert(ByteArrayInputStream(raw.toByteArray()), from, to, out)
        return out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun srtBecomesVttWithDottedTimes() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\nHello\n\n2\n00:00:03,000 --> 00:00:04,000\nWorld\n"
        val vtt = convert(srt, "srt", "vtt")
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:01.000 --> 00:00:02.500"))
        assertTrue(vtt.contains("Hello"))
        assertTrue(vtt.contains("00:00:03.000 --> 00:00:04.000"))
    }

    @Test
    fun vttShortTimestampsAndCueSettingsAreUnderstood() {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000 align:start position:10%\nShort\n"
        val srt = convert(vtt, "vtt", "srt")
        assertTrue(srt.contains("00:00:01,000 --> 00:00:02,000"))
        assertTrue(srt.contains("Short"))
    }

    @Test
    fun assOutputEscapesDialogueSyntax() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n{a} \\ b\n"
        val ass = convert(srt, "srt", "ass")
        assertTrue(ass.contains("[Events]"))
        assertTrue(ass.contains("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,"))
        assertTrue("braces must not become override tags: $ass", ass.contains("\\{a\\}"))
        assertTrue("a literal backslash is doubled: $ass", ass.contains("\\\\ b"))
    }

    @Test
    fun assParsesBackToSrt() {
        val ass = """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.25,0:00:07.00,Default,,0,0,0,,Line one\NLine two
        """.trimIndent()
        val srt = convert(ass, "ass", "srt")
        assertTrue(srt.contains("00:00:05,250 --> 00:00:07,000"))
        assertTrue(srt.contains("Line one\nLine two"))
    }

    @Test
    fun plainTextKeepsOnlyTheDialogue() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nFirst\n\n2\n00:00:03,000 --> 00:00:04,000\nSecond\n"
        val txt = convert(srt, "srt", "txt")
        assertEquals("First\n\nSecond", txt.trim())
    }

    @Test
    fun endBeforeStartIsClampedToZeroLengthInsteadOfWrapping() {
        val cues = SubtitleConverter.parse("1\n00:00:05,000 --> 00:00:02,000\nOdd\n", "srt")
        assertEquals(1, cues.size)
        val out = SubtitleConverter.serialize(cues, "vtt")
        assertTrue(
            "the cue collapses to its start time instead of running backwards: $out",
            out.contains("00:00:05.000 --> 00:00:05.000")
        )
    }

    @Test
    fun crlfBomAndMissingHourFieldAreTolerated() {
        val vtt = "\uFEFFWEBVTT\r\n\r\n01:02.500 --> 01:04.000\r\nline\r\n"
        val cues = SubtitleConverter.parse(vtt, "vtt")
        assertEquals(1, cues.size)
        assertEquals(62_500L, cues[0].startMs)
        assertEquals(64_000L, cues[0].endMs)
    }

    @Test
    fun unknownExtensionSniffsTheFormatFromContent() {
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,sniffed\n"
        val cues = SubtitleConverter.parse(ass, "sub")
        assertEquals(1, cues.size)
        assertEquals("sniffed", cues[0].text)
    }
}
