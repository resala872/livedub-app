package com.livedub

/** Pure timing logic (no Android classes) so it can be unit-tested on a normal JVM. */
object Sync {
    const val LEAD_MS = 250L    // start speaking slightly early to hide TTS start-up delay
    const val LATE_MS = 1500L   // skip a sentence if we are this late
    private const val CHARS_PER_SEC = 13.0 // rough speaking speed of Arabic TTS at normal rate

    fun firstAfter(cues: List<Cue>, pos: Long): Int {
        val i = cues.indexOfFirst { it.end > pos }
        return if (i < 0) cues.size else i
    }

    /** The video position jumped (user seeked) between two scheduler ticks. */
    fun isSeek(lastPos: Long, pos: Long) = pos < lastPos - 700 || pos - lastPos > 2500

    fun rateFor(textLength: Int, slotMs: Long): Float =
        (textLength / CHARS_PER_SEC * 1000.0 / slotMs).toFloat().coerceIn(1.0f, 1.8f)

    /** Time available to speak cue [i]: until the next cue starts (at most 1.5 s past its own end). */
    fun slotMs(cues: List<Cue>, i: Int): Long {
        val cue = cues[i]
        val nextStart = cues.getOrNull(i + 1)?.start ?: (cue.end + 2000)
        return maxOf(1200L, minOf(nextStart, cue.end + 1500) - cue.start)
    }
}

/**
 * Decides, for every tick of the video position, which translated sentence must be spoken now.
 * Handles pause, resume, seek and sentences whose translation is not ready yet.
 */
class DubScheduler(
    private val cues: List<Cue>,
    private val text: (Int) -> String?,          // null = translation not ready yet, "" = skip
    private val speak: (Int, Long) -> Unit,      // cue index, time slot in ms
    private val stop: () -> Unit
) {
    var next = 0
        private set
    private var lastPos = -1L
    private var wasPlaying = false

    fun tick(pos: Long, playing: Boolean) {
        if (!playing) {
            if (wasPlaying) stop()
            wasPlaying = false
            next = Sync.firstAfter(cues, pos)
            return
        }
        if (!wasPlaying) {
            next = Sync.firstAfter(cues, pos)
            wasPlaying = true
        } else if (Sync.isSeek(lastPos, pos)) {
            stop()
            next = Sync.firstAfter(cues, pos)
        }
        lastPos = pos
        while (next < cues.size && cues[next].start <= pos + Sync.LEAD_MS) {
            val t = text(next) ?: break
            val i = next
            next++
            if (t.isEmpty() || pos - cues[i].start > Sync.LATE_MS) continue
            speak(i, Sync.slotMs(cues, i))
        }
    }
}
