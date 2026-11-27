package com.livedub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionsTest {
    @Test fun parsesVideoIds() {
        assertEquals("UF8uR6Z6KLc", Captions.parseId("https://www.youtube.com/watch?v=UF8uR6Z6KLc&t=10s"))
        assertEquals("UF8uR6Z6KLc", Captions.parseId("Watch this https://youtu.be/UF8uR6Z6KLc?si=abc"))
        assertEquals("UF8uR6Z6KLc", Captions.parseId("https://www.youtube.com/shorts/UF8uR6Z6KLc"))
        assertEquals("UF8uR6Z6KLc", Captions.parseId("  UF8uR6Z6KLc "))
        assertNull(Captions.parseId("hello"))
        assertNull(Captions.parseId(""))
    }

    @Test fun parsesAndMergesJson3() {
        val json = """{"events":[
            {"tStartMs":0,"dDurationMs":1500,"segs":[{"utf8":"Hello"},{"utf8":" there"}]},
            {"tStartMs":1500,"dDurationMs":1500,"segs":[{"utf8":"how are you today."}]},
            {"tStartMs":3100,"dDurationMs":500,"segs":[{"utf8":"\n"}]},
            {"tStartMs":4000,"dDurationMs":1000,"segs":[{"utf8":"[Music]"}]},
            {"tStartMs":5000,"dDurationMs":2000,"segs":[{"utf8":">> Second sentence."}]},
            {"tStartMs":9000,"dDurationMs":1000}
        ]}"""
        val cues = Captions.parse(json)
        assertEquals(2, cues.size)
        assertEquals("Hello there how are you today.", cues[0].text)
        assertEquals(0L, cues[0].start); assertEquals(3000L, cues[0].end)
        assertEquals("Second sentence.", cues[1].text)
    }

    @Test fun emptyOrBrokenInputGivesNoCues() {
        assertEquals(0, Captions.parse("""{"events":[]}""").size)
        assertEquals(0, Captions.parse("""{}""").size)
    }
}
