package com.livedub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationManagerCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * 1. Downloads the video's subtitle track (with timestamps).
 * 2. Translates every cue on-device (ML Kit) in the background.
 * 3. Reads the video app's playback position (MediaSession) and speaks each translated cue
 *    with text-to-speech exactly when it is due, following pause / seek / speed changes.
 */
class DubService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var tts: TextToSpeech? = null
    private val pending = AtomicInteger(0)
    private var failed = false
    private var lastStatus = ""

    private fun status(s: String) {
        if (s != lastStatus) { lastStatus = s; Log.i("LiveDub", s) }
        Bus.status.value = s
    }

    private lateinit var am: AudioManager
    private var originalMusicVolume = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY

        startForegroundCompat()
        val videoId = intent.getStringExtra("video") ?: run { stopSelf(); return START_NOT_STICKY }
        val lang = intent.getStringExtra("lang") ?: "ar"
        val duck = intent.getIntExtra("duck", 20)

        am = getSystemService(AUDIO_SERVICE) as AudioManager
        Bus.running.value = true
        job = scope.launch {
            try {
                run(videoId, lang, duck)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                status("Error: ${e.message}")
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val ch = NotificationChannel("dub", "Live dubbing", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, DubService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "dub")
            .setContentTitle("Live dubbing is running")
            .setContentText("Tap Stop to end")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private suspend fun run(videoId: String, lang: String, duckPercent: Int) = coroutineScope {
        status("Fetching subtitles…")
        val cap = withContext(Dispatchers.IO) { Captions.fetch(videoId) }
        val cues = cap.cues
        val src = TranslateLanguage.fromLanguageTag(cap.lang.substringBefore('-'))
            ?: throw Exception("Unsupported subtitle language: ${cap.lang}")

        status("Preparing translation (first run downloads ~30 MB)…")
        val translator = if (src == lang) null else Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(lang).build()
        )
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())?.await()

        if (!initTts(lang)) {
            throw Exception("No voice for this language. Install it in Android Settings > Text-to-speech.")
        }
        duckOriginal(duckPercent)

        // null = not translated yet, "" = failed (skipped)
        val out = AtomicReferenceArray<String>(cues.size)
        val translateJob = launch {
            for (i in cues.indices) {
                val t = try {
                    translator?.translate(cues[i].text)?.await() ?: cues[i].text
                } catch (e: Exception) { "" }
                out.set(i, t)
            }
        }

        status("Ready: ${cues.size} sentences, ${cap.lang} → $lang (via ${cap.via})")
        val sched = DubScheduler(
            cues,
            text = { out.get(it) },
            speak = { i, slot ->
                Bus.original.value = cues[i].text
                Bus.dubbed.value = out.get(i) ?: ""
                speak(out.get(i) ?: "", slot)
            },
            stop = { tts?.stop(); pending.set(0) }
        )
        var lastPos = 0L
        try {
            while (isActive) {
                val p = playback()
                if (p == null) {
                    sched.tick(lastPos, false)
                    status(
                        if (NotificationManagerCompat.getEnabledListenerPackages(this@DubService).contains(packageName))
                            "Waiting for the video to play…"
                        else "Enable Notification access for Live Dub"
                    )
                    delay(500)
                    continue
                }
                val pos = p.pos + Bus.offsetMs.value
                lastPos = pos
                val sameVideo = cap.durationMs <= 0 || p.durationMs <= 0 || abs(p.durationMs - cap.durationMs) < 4000
                if (!sameVideo) {
                    sched.tick(pos, false)
                    status("A different video (or an ad) is playing — dub paused")
                    delay(400)
                    continue
                }
                sched.tick(pos, p.playing)
                status(if (p.playing) "Dubbing ${cap.lang} → $lang" else "Paused")
                delay(120)
            }
        } finally {
            translateJob.cancel()
            translator?.close()
        }
    }

    private class Playback(val pos: Long, val playing: Boolean, val durationMs: Long)

    /** Position of the video app's media session, or null if nothing is playing. */
    private fun playback(): Playback? {
        val msm = getSystemService(MediaSessionManager::class.java)
        val list: List<MediaController> = try {
            msm.getActiveSessions(ComponentName(this, MediaWatcher::class.java))
        } catch (e: SecurityException) {
            return null
        }
        val c = list.firstOrNull { it.packageName.contains("youtube", ignoreCase = true) && it.playbackState != null }
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: return null
        val s = c.playbackState ?: return null
        if (s.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) return null
        val playing = s.state == PlaybackState.STATE_PLAYING
        var pos = s.position
        if (playing) pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return Playback(pos, playing, dur)
    }

    private suspend fun initTts(lang: String): Boolean = withContext(Dispatchers.Main) {
        val ok = suspendCancellableCoroutine<Boolean> { c ->
            tts = TextToSpeech(this@DubService) { status ->
                if (c.isActive) c.resume(status == TextToSpeech.SUCCESS)
            }
        }
        val t = tts
        if (!ok || t == null) return@withContext false
        val r = t.setLanguage(Locale.forLanguageTag(lang))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return@withContext false
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) // separate volume from the video
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { pending.updateAndGet { maxOf(0, it - 1) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { pending.updateAndGet { maxOf(0, it - 1) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                pending.updateAndGet { maxOf(0, it - 1) }
            }
        })
        true
    }

    private fun speak(text: String, slotMs: Long) {
        val t = tts ?: return
        if (pending.get() >= 3) { t.stop(); pending.set(0) } // way behind: drop the backlog
        pending.incrementAndGet()
        t.setSpeechRate(Sync.rateFor(text.length, slotMs))
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
    }

    private fun duckOriginal(percent: Int) {
        runCatching { am.setStreamVolume(10, am.getStreamMaxVolume(10), 0) } // accessibility stream = the dub
        if (percent in 0..99) runCatching {
            originalMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxOf(1, originalMusicVolume * percent / 100), 0)
        }
    }

    override fun onDestroy() {
        runCatching {
            if (originalMusicVolume >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0)
        }
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        Bus.running.value = false
        if (!failed) status("Stopped")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.livedub.STOP"
    }
}
