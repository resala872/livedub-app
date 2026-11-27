package com.livedub

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleParserTest {
    @Test fun parsesSrtAndMergesFragments() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello <i>there</i>\r\n\r\n" +
            "2\r\n00:00:02,600 --> 00:00:04,000\r\nhow are you today.\r\n\r\n" +
            "3\r\n00:01:05,250 --> 00:01:07,000\r\nSecond line\r\nsecond row.\r\n"
        val c = SubtitleParser.parse(srt)
        assertEquals(2, c.size)
        assertEquals("Hello there how are you today.", c[0].text)
        assertEquals(1000L, c[0].start); assertEquals(4000L, c[0].end)
        assertEquals("Second line second row.", c[1].text)
        assertEquals(65250L, c[1].start)
    }

    @Test fun parsesVtt() {
        val vtt = "WEBVTT\n\n00:01.500 --> 00:03.000\nShort one.\n\n01:00:00.000 --> 01:00:02.000 align:start\nAn hour in.\n"
        val c = SubtitleParser.parse(vtt)
        assertEquals(2, c.size)
        assertEquals(1500L, c[0].start)
        assertEquals(3600000L, c[1].start)
    }

    @Test fun garbageGivesNoCues() {
        assertEquals(0, SubtitleParser.parse("hello world").size)
        assertEquals(0, SubtitleParser.parse("").size)
    }
}
