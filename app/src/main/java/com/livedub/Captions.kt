package com.livedub

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Fetches the subtitle track of a YouTube video (unofficial InnerTube endpoint; may change). */
object Captions {
    data class Cue(val start: Long, val end: Long, val text: String)
    data class Result(val lang: String, val cues: List<Cue>, val durationMs: Long, val via: String)

    private class Client(val name: String, val version: String, val ua: String, val extra: String)

    private val CLIENTS = listOf(
        Client("ANDROID", "19.09.37", "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip",
            ""","androidSdkVersion":30"""),
        Client("WEB", "2.20240304.00.00",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36", ""),
        Client("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0",
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1", "")
    )

    fun parseId(s: String): String? {
        val t = s.trim()
        Regex("""(?:v=|youtu\.be/|shorts/|embed/|live/)([\w-]{11})""").find(t)?.let { return it.groupValues[1] }
        return if (Regex("""[\w-]{11}""").matches(t)) t else null
    }

    fun fetch(videoId: String): Result {
        var err = "No captions found for this video"
        for (c in CLIENTS) {
            try {
                tryClient(videoId, c)?.let { return it }
            } catch (e: Exception) {
                err = e.message ?: err
            }
        }
        throw Exception(err)
    }

    private fun tryClient(id: String, c: Client): Result? {
        val body = """{"context":{"client":{"clientName":"${c.name}","clientVersion":"${c.version}","hl":"en"${c.extra}}},"videoId":"$id"}"""
        val resp = http("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", c.ua, body)
        val tracks = JSONObject(resp).optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return null
        if (tracks.length() == 0) return null

        // Prefer a human English track, then auto-generated English, then anything.
        var best = 0
        var bestScore = Double.MAX_VALUE
        for (i in 0 until tracks.length()) {
            val t = tracks.getJSONObject(i)
            var score = if (t.optString("languageCode").startsWith("en")) 0.0 else 1.0
            if (t.optString("kind") == "asr") score += 0.5
            if (score < bestScore) { bestScore = score; best = i }
        }
        val track = tracks.getJSONObject(best)
        val url = track.getString("baseUrl").replace(Regex("[&?]fmt=[^&]*"), "") + "&fmt=json3"
        val json = http(url, c.ua, null)
        if (json.isBlank()) return null
        val cues = parse(json)
        if (cues.isEmpty()) return null
        val dur = JSONObject(resp).optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull()?.times(1000) ?: 0L
        return Result(track.optString("languageCode", "en"), cues, dur, c.name)
    }

    fun parse(json: String): List<Cue> {
        val events = JSONObject(json).optJSONArray("events") ?: return emptyList()
        val raw = mutableListOf<Cue>()
        for (k in 0 until events.length()) {
            val e = events.getJSONObject(k)
            val segs = e.optJSONArray("segs") ?: continue
            val text = buildString {
                for (j in 0 until segs.length()) append(segs.getJSONObject(j).optString("utf8"))
            }.replace("\n", " ").replace(">>", " ").replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty() || Regex("""\[[^\]]*]""").matches(text)) continue
            val start = e.optLong("tStartMs")
            raw.add(Cue(start, start + e.optLong("dDurationMs", 2000), text))
        }
        // Merge fragments into sentences so the dub sounds natural and translates better.
        val merged = mutableListOf<Cue>()
        val sentenceEnd = Regex(""".*[.?!…]$""")
        for (c in raw) {
            val last = merged.lastOrNull()
            if (last != null && !sentenceEnd.matches(last.text) &&
                c.start - last.end < 600 && c.end - last.start < 8000
            ) {
                merged[merged.lastIndex] = Cue(last.start, maxOf(last.end, c.end), last.text + " " + c.text)
            } else merged.add(c)
        }
        return merged
    }

    private fun http(url: String, ua: String, postBody: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        if (postBody != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(postBody.toByteArray()) }
        }
        if (c.responseCode !in 200..299) throw Exception("YouTube answered HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
