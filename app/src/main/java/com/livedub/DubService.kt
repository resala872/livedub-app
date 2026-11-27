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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.coroutines.resume

/**
 * Two ways to dub, both ending in Android text-to-speech on a separate volume stream:
 *  - AUDIO: captures the video app's audio (Android 10+), recognises English speech offline (Vosk),
 *           translates each sentence on-device (ML Kit) and speaks it.
 *  - SUBS:  uses a subtitle file the user imported, translated ahead of time and scheduled
 *           against the video's playback position (MediaSession), following pause / seek.
 */
class DubService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var projection: MediaProjection? = null
    private var tts: TextToSpeech? = null
    private val pending = AtomicInteger(0)
    private var failed = false
    private var lastStatus = ""

    private lateinit var am: AudioManager
    private var originalMusicVolume = -1

    private fun status(s: String) {
        if (s != lastStatus) { lastStatus = s; Log.i("LiveDub", s) }
        Bus.status.value = s
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY

        val audioMode = intent.getStringExtra("mode") == "audio"
        startForegroundCompat(audioMode)
        val lang = intent.getStringExtra("lang") ?: "ar"
        val duck = intent.getIntExtra("duck", 20)
        am = getSystemService(AUDIO_SERVICE) as AudioManager

        if (audioMode) {
            val data = intent.getParcelableExtra<Intent>("data")
            if (data == null) { stopSelf(); return START_NOT_STICKY }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(intent.getIntExtra("code", 0), data)
            // Required on Android 14+: register a callback before capturing.
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
            projection = p
        }

        Bus.running.value = true
        job = scope.launch {
            try {
                if (audioMode) runAudio(projection!!, lang, duck) else runSubs(lang, duck)
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

    private fun startForegroundCompat(audioMode: Boolean) {
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
        startForeground(
            1, n,
            if (audioMode) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    // ---------------------------------------------------------------- shared helpers

    private suspend fun makeTranslator(target: String): Translator? {
        status("Preparing translation (first run downloads ~30 MB)…")
        if (target == TranslateLanguage.ENGLISH) return null
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(target)
                .build()
        )
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return t
    }

    private suspend fun initTts(lang: String): Boolean = withContext(Dispatchers.Main) {
        val ok = suspendCancellableCoroutine<Boolean> { c ->
            tts = TextToSpeech(this@DubService) { st ->
                if (c.isActive) c.resume(st == TextToSpeech.SUCCESS)
            }
        }
        val t = tts
        if (!ok || t == null) return@withContext false
        val r = t.setLanguage(Locale.forLanguageTag(lang))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return@withContext false
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) // own volume; not captured by us
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

    private fun speak(text: String, rate: Float) {
        val t = tts ?: return
        if (pending.get() >= 3) { t.stop(); pending.set(0) } // far behind: drop the backlog
        pending.incrementAndGet()
        t.setSpeechRate(rate)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
    }

    private fun duckOriginal(percent: Int) {
        runCatching { am.setStreamVolume(10, am.getStreamMaxVolume(10), 0) } // accessibility stream = the dub
        if (percent in 0..99) runCatching {
            originalMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxOf(1, originalMusicVolume * percent / 100), 0)
        }
    }

    private suspend fun translate(tr: Translator?, text: String): String =
        try { tr?.translate(text)?.await() ?: text } catch (e: Exception) { "" }

    private val noVoice = "No voice for this language. Install it in Android Settings > Text-to-speech."

    // ---------------------------------------------------------------- AUDIO mode

    private suspend fun runAudio(proj: MediaProjection, lang: String, duckPercent: Int) = coroutineScope {
        status("Loading speech model…")
        val model = withContext(Dispatchers.IO) { Model(ModelManager.dir(this@DubService).absolutePath) }
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        val translator = makeTranslator(lang)
        status("Starting voice…")
        if (!initTts(lang)) throw Exception(noVoice)
        duckOriginal(duckPercent)

        val queue = Channel<String>(Channel.UNLIMITED)
        val speaker = launch {
            for (text in queue) {
                val out = translate(translator, text)
                if (out.isBlank()) continue
                Bus.dubbed.value = out
                // Speed up gently as the queue grows so the dub stays close to the video.
                speak(out, (1.0f + 0.12f * pending.get()).coerceAtMost(1.7f))
            }
        }

        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(cfg)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(minBuf * 4)
            .build()

        try {
            rec.startRecording()
            status("Listening… play a video")
            withContext(Dispatchers.IO) {
                val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms
                var lastFinal = System.currentTimeMillis()
                fun emit(json: String) {
                    val t = JSONObject(json).optString("text", "").trim()
                    lastFinal = System.currentTimeMillis()
                    if (t.isNotEmpty()) {
                        Bus.original.value = t
                        queue.trySend(t)
                    }
                }
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (recognizer.acceptWaveForm(buf, n)) {
                        emit(recognizer.result)
                    } else {
                        // Long unbroken speech: force a cut so the dub does not fall far behind.
                        val partial = JSONObject(recognizer.partialResult).optString("partial", "")
                        val words = if (partial.isBlank()) 0 else partial.trim().split(" ").size
                        val waited = System.currentTimeMillis() - lastFinal
                        if (words >= 14 || (words >= 4 && waited > 6000)) emit(recognizer.finalResult)
                    }
                }
            }
        } finally {
            queue.close()
            speaker.cancel()
            runCatching { rec.stop() }
            rec.release()
            recognizer.close()
            model.close()
            translator?.close()
        }
    }

    // ---------------------------------------------------------------- SUBS mode

    private suspend fun runSubs(lang: String, duckPercent: Int) = coroutineScope {
        val f = File(filesDir, SUBS_FILE)
        if (!f.exists()) throw Exception("No subtitle file imported")
        val cues = SubtitleParser.parse(f.readText())
        if (cues.isEmpty()) throw Exception("The subtitle file has no readable lines")

        val translator = makeTranslator(lang)
        status("Starting voice…")
        if (!initTts(lang)) throw Exception(noVoice)
        duckOriginal(duckPercent)

        val out = AtomicReferenceArray<String>(cues.size) // null = not ready, "" = failed
        val translateJob = launch {
            for (i in cues.indices) out.set(i, translate(translator, cues[i].text))
        }

        val sched = DubScheduler(
            cues,
            text = { out.get(it) },
            speak = { i, slot ->
                val t = out.get(i) ?: ""
                Bus.original.value = cues[i].text
                Bus.dubbed.value = t
                speak(t, Sync.rateFor(t.length, slot))
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
                        else "Turn on Notification access for Live Dub"
                    )
                    delay(500)
                    continue
                }
                val pos = p.first + Bus.offsetMs.value
                lastPos = pos
                sched.tick(pos, p.second)
                status(if (p.second) "Dubbing from the subtitle file" else "Paused")
                delay(120)
            }
        } finally {
            translateJob.cancel()
            translator?.close()
        }
    }

    /** (positionMs, isPlaying) of the video app's media session, or null if nothing is playing. */
    private fun playback(): Pair<Long, Boolean>? {
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
        return Pair(pos, playing)
    }

    override fun onDestroy() {
        runCatching {
            if (originalMusicVolume >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0)
        }
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        projection?.stop()
        Bus.running.value = false
        if (!failed) status("Stopped")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.livedub.STOP"
        const val SUBS_FILE = "subs.txt"
        private const val SAMPLE_RATE = 16000
    }
}
