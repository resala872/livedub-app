package com.livedub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Pipeline: captured app audio (YouTube / Morphe-patched YouTube)
 *   -> Vosk offline speech recognition (English)
 *   -> ML Kit on-device translation
 *   -> Android TextToSpeech (the "dub"), played on the accessibility stream
 *      while the original media volume is ducked.
 */
class DubService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var projection: MediaProjection? = null
    private var tts: TextToSpeech? = null
    private val pending = AtomicInteger(0)

    private lateinit var am: AudioManager
    private var originalMusicVolume = -1

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY

        startForegroundCompat()

        val code = intent.getIntExtra("code", 0)
        val data = intent.getParcelableExtra<Intent>("data")
        val lang = intent.getStringExtra("lang") ?: "ar"
        val duck = intent.getIntExtra("duck", 40)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        // Required on Android 14+: a callback must be registered before capturing.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = p

        am = getSystemService(AUDIO_SERVICE) as AudioManager
        Bus.running.value = true
        job = scope.launch {
            try {
                pipeline(p, lang, duck)
            } catch (e: Exception) {
                Bus.status.value = "Error: ${e.message}"
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
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private suspend fun pipeline(proj: MediaProjection, lang: String, duckPercent: Int) = coroutineScope {
        Bus.status.value = "Loading speech model…"
        val model = withContext(Dispatchers.IO) { Model(ModelManager.dir(this@DubService).absolutePath) }
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

        Bus.status.value = "Preparing translation model (first run downloads ~30 MB)…"
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(lang)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()

        Bus.status.value = "Starting voice…"
        if (!initTts(lang)) {
            Bus.status.value = "No text-to-speech voice for this language. Install it in Android settings."
            return@coroutineScope
        }

        duckOriginal(duckPercent)

        val queue = Channel<String>(Channel.UNLIMITED)
        val speaker = launch {
            for (text in queue) {
                val out = try { translator.translate(text).await() } catch (e: Exception) { continue }
                if (out.isBlank()) continue
                Bus.dubbed.value = out
                speak(out)
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
            Bus.status.value = "Dubbing… play a video"
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
            translator.close()
        }
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
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) // not captured by our own capture config
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

    private fun speak(text: String) {
        val t = tts ?: return
        // Catch up: if the dub is far behind the video, drop the backlog.
        if (pending.get() > 8) {
            t.stop()
            pending.set(0)
        }
        val n = pending.incrementAndGet()
        // Speed up gently as the queue grows so the dub stays close to the video.
        t.setSpeechRate((1.0f + 0.12f * (n - 1)).coerceAtMost(1.7f))
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
    }

    private fun duckOriginal(percent: Int) {
        runCatching {
            // STREAM_ACCESSIBILITY (10) carries the dub; keep it loud.
            am.setStreamVolume(10, am.getStreamMaxVolume(10), 0)
        }
        if (percent in 0..99) runCatching {
            originalMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val v = maxOf(1, originalMusicVolume * percent / 100)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        }
    }

    override fun onDestroy() {
        runCatching {
            if (originalMusicVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0)
            }
        }
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        projection?.stop()
        Bus.running.value = false
        Bus.status.value = "Stopped"
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.livedub.STOP"
        private const val SAMPLE_RATE = 16000
    }
}
