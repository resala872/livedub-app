package com.livedub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {
    private val cues = listOf(
        Cue(1000, 3000, "A"),
        Cue(3200, 5000, "B"),
        Cue(9000, 10000, "C")
    )

    private class Rig(cues: List<Cue>, ready: (Int) -> String? = { "t$it" }) {
        val spoken = mutableListOf<Int>()
        var stops = 0
        val s = DubScheduler(cues, ready, { i, _ -> spoken.add(i) }, { stops++ })
    }

    @Test fun speaksCueWhenDueAndOnlyOnce() {
        val r = Rig(cues)
        r.s.tick(0, true); assertTrue(r.spoken.isEmpty())
        r.s.tick(800, true); assertEquals(listOf(0), r.spoken)
        r.s.tick(900, true); r.s.tick(1000, true); assertEquals(listOf(0), r.spoken)
        r.s.tick(3000, true); assertEquals(listOf(0, 1), r.spoken)
    }

    @Test fun pauseStopsSpeechOnceAndResumeRespeaksCurrentSentence() {
        val r = Rig(cues)
        r.s.tick(800, true)
        r.s.tick(2000, false); r.s.tick(2100, false)
        assertEquals(1, r.stops)
        r.s.tick(2000, true)
        assertEquals(listOf(0, 0), r.spoken)
    }

    @Test fun seekForwardSkipsToNewPosition() {
        val r = Rig(cues)
        r.s.tick(800, true)
        r.s.tick(9100, true)
        assertEquals(1, r.stops)
        assertEquals(listOf(0, 2), r.spoken)
    }

    @Test fun seekBackwardReplaysEarlierSentence() {
        val r = Rig(cues)
        r.s.tick(4000, true); r.s.tick(4100, true)
        r.s.tick(900, true)
        assertTrue(r.spoken.contains(0))
    }

    @Test fun startingLateSkipsSentencesAlreadyOver() {
        val r = Rig(cues)
        r.s.tick(5000, true)
        assertTrue(r.spoken.isEmpty())
        assertEquals(2, r.s.next)
    }

    @Test fun waitsForTranslationThenSpeaksIfNotTooLate() {
        var ready = false
        val r = Rig(cues) { if (it == 0 && !ready) null else "x" }
        r.s.tick(1000, true); assertTrue(r.spoken.isEmpty())
        ready = true
        r.s.tick(1800, true); assertEquals(listOf(0), r.spoken)   // 800 ms late: still spoken
    }

    @Test fun tooLateIsSkipped() {
        var ready = false
        val r = Rig(cues) { if (it == 0 && !ready) null else "x" }
        r.s.tick(1000, true)
        ready = true
        r.s.tick(2800, true)   // 1800 ms late
        assertTrue(!r.spoken.contains(0))
    }

    @Test fun failedTranslationIsSkipped() {
        val r = Rig(cues) { if (it == 0) "" else "x" }
        r.s.tick(1000, true); r.s.tick(3000, true)
        assertEquals(listOf(1), r.spoken)
    }

    @Test fun speechRateFitsSlot() {
        assertEquals(1.8f, Sync.rateFor(130, 5000), 0.001f)
        assertEquals(1.0f, Sync.rateFor(13, 5000), 0.001f)
        assertEquals(1.2f, Sync.rateFor(78, 5000), 0.01f)
    }

    @Test fun slotNeverShorterThanOneAndAHalfSecondsOrCutsIntoNextCue() {
        assertEquals(2200L, Sync.slotMs(cues, 0))  // until next start (3200) - 1000
        assertTrue(Sync.slotMs(cues, 2) >= 1200)
    }
}
