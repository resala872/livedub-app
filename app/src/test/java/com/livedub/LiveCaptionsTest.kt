package com.livedub

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Hits the real YouTube endpoint. Only runs in CI when LIVE_TEST=1; writes a short report. */
class LiveCaptionsTest {
    @Test fun fetchesRealCaptions() {
        assumeTrue(System.getenv("LIVE_TEST") == "1")
        val ids = listOf("UF8uR6Z6KLc", "arj7oStGLkU", "iCvmsMzlF7o", "jNQXAC9IVRw")
        val sb = StringBuilder()
        var ok = 0
        for (id in ids) {
            try {
                val r = Captions.fetch(id)
                ok++
                val c = r.cues
                val avgDur = c.map { it.end - it.start }.average().toLong()
                val avgLen = c.map { it.text.length }.average().toInt()
                val overlaps = c.zipWithNext().count { (a, b) -> b.start < a.start }
                sb.appendLine("OK $id via=${r.via} lang=${r.lang} cues=${c.size} videoMs=${r.durationMs} lastEnd=${c.last().end} avgDurMs=$avgDur avgChars=$avgLen outOfOrder=$overlaps")
                c.take(6).forEach { sb.appendLine("   [${it.start}-${it.end}] len=${it.text.length} '${it.text.take(22)}'") }
            } catch (e: Exception) {
                sb.appendLine("FAIL $id ${e.message}")
            }
        }
        File(System.getenv("REPORT_FILE") ?: "/tmp/live_report.txt").writeText(sb.toString())
        println(sb)
        assert(ok > 0) { "no video returned captions" }
    }
}
