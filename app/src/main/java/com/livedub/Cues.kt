package com.livedub

data class Cue(val start: Long, val end: Long, val text: String)

/** Parses .srt / .vtt subtitle files (pure Kotlin so it can be unit-tested on the JVM). */
object SubtitleParser {
    private val TIME = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
    private val SENTENCE_END = Regex(""".*[.?!…]$""")

    private fun ms(m: MatchResult): Long {
        val h = m.groupValues[1].ifEmpty { "0" }.toLong()
        val min = m.groupValues[2].toLong()
        val s = m.groupValues[3].toLong()
        val frac = m.groupValues[4].padEnd(3, '0').toLong()
        return ((h * 60 + min) * 60 + s) * 1000 + frac
    }

    fun parse(raw: String): List<Cue> {
        val blocks = raw.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        val cues = mutableListOf<Cue>()
        for (b in blocks) {
            val lines = b.trim().split("\n")
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val times = TIME.findAll(lines[ti]).toList()
            if (times.size < 2) continue
            val text = lines.drop(ti + 1).joinToString(" ")
                .replace(Regex("<[^>]+>"), "").replace(Regex("\\{\\\\[^}]*\\}"), "")
                .replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            cues.add(Cue(ms(times[0]), ms(times[1]), text))
        }
        return mergeSentences(cues.sortedBy { it.start })
    }

    /** Joins fragments into whole sentences so the dub sounds natural and translates better. */
    fun mergeSentences(raw: List<Cue>): List<Cue> {
        val merged = mutableListOf<Cue>()
        for (c in raw) {
            val last = merged.lastOrNull()
            if (last != null && !SENTENCE_END.matches(last.text) &&
                c.start - last.end < 600 && c.end - last.start < 8000
            ) merged[merged.lastIndex] = Cue(last.start, maxOf(last.end, c.end), last.text + " " + c.text)
            else merged.add(c)
        }
        return merged
    }
}
