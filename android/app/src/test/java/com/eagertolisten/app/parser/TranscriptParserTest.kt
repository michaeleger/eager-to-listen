package com.eagertolisten.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptParserTest {

    private val parser = TranscriptParser()

    @Test
    fun testParseTimestamp_secondsOnlyWithDecimals() {
        // Test single number format in SRT/VTT (e.g., "12.5")
        val vtt = """WEBVTT

00:00:12.500 --> 00:00:15.000
This is a test subtitle.
"""
        val cues = parser.parse(vtt, "text/vtt")
        assertEquals(1, cues.size)
        assertEquals(12500L, cues[0].startMs)
        assertEquals(15000L, cues[0].endMs)
        assertEquals("This is a test subtitle.", cues[0].text)
    }

    @Test
    fun testParseWebVtt_hhMmSs() {
        val vtt = """WEBVTT

01:02:03.456 --> 01:02:07.890
Hello from WebVTT!
"""
        val cues = parser.parse(vtt, "text/vtt")
        assertEquals(1, cues.size)
        val expectedStart = (1 * 3600 + 2 * 60 + 3) * 1000L + 456L
        val expectedEnd = (1 * 3600 + 2 * 60 + 7) * 1000L + 890L
        assertEquals(expectedStart, cues[0].startMs)
        assertEquals(expectedEnd, cues[0].endMs)
    }

    @Test
    fun testParseJson_podcasting20() {
        val json = """{
            "version": "1.0.0",
            "segments": [
                {
                    "startTime": 5.5,
                    "endTime": 9.25,
                    "body": "First segment"
                }
            ]
        }"""
        val cues = parser.parse(json, "application/json")
        assertEquals(1, cues.size)
        assertEquals(5500L, cues[0].startMs)
        assertEquals(9250L, cues[0].endMs)
        assertEquals("First segment", cues[0].text)
    }
}
