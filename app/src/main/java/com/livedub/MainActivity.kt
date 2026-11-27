package com.livedub

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val languages = listOf(
        "Arabic (العربية)" to "ar", "French" to "fr", "Spanish" to "es", "German" to "de",
        "Turkish" to "tr", "Russian" to "ru", "Hindi" to "hi", "Urdu" to "ur",
        "Indonesian" to "id", "Portuguese" to "pt"
    )
    private val prefs by lazy { getSharedPreferences("live_dub", MODE_PRIVATE) }
    private var langIndex = 0
    private var duck = 20

    private lateinit var statusTv: TextView
    private lateinit var progress: ProgressBar
    private lateinit var originalTv: TextView
    private lateinit var dubbedTv: TextView
    private lateinit var startBtn: Button
    private lateinit var accessBtn: Button
    private lateinit var modelBtn: Button
    private lateinit var subsBtn: Button
    private lateinit var subsClearBtn: Button
    private lateinit var subsInfo: TextView
    private lateinit var offsetTv: TextView

    private fun subsFile() = File(filesDir, DubService.SUBS_FILE)
    private fun hasSubs() = subsFile().exists()

    private val pickSubs =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                } ?: "subtitles"
                val text = withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                }
                val cues = text?.let { SubtitleParser.parse(it) } ?: emptyList()
                if (cues.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Could not read subtitles from this file (use .srt or .vtt)", Toast.LENGTH_LONG).show()
                } else {
                    withContext(Dispatchers.IO) { subsFile().writeText(text!!) }
                    prefs.edit().putString("subs_name", "$name · ${cues.size} sentences").apply()
                    refreshState()
                }
            }
        }

    private val audioPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            } else Toast.makeText(this, "Microphone permission is needed to hear the video's audio", Toast.LENGTH_LONG).show()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                startDub(Intent(this, DubService::class.java)
                    .putExtra("mode", "audio").putExtra("code", r.resultCode).putExtra("data", data))
            }
        }

    private val subsPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startDub(Intent(this, DubService::class.java).putExtra("mode", "subs"))
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun card(parent: LinearLayout, title: String? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 16)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        if (title != null) c.addView(TextView(this).apply {
            text = title; textSize = 13f; setTextColor(0xFF5F6368.toInt()); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return c
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        minHeight = dp(48)
        setTextColor(if (primary) Color.WHITE else 0xFF1A73E8.toInt())
        background = rounded(if (primary) 0xFF1A73E8.toInt() else 0xFFE8F0FE.toInt(), 12)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun gap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        langIndex = prefs.getInt("lang", 0).coerceIn(languages.indices)
        duck = prefs.getInt("duck", 20)
        Bus.offsetMs.value = prefs.getLong("offset", 0L)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Live Dub"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF202124.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Speaks the video in your language while it plays (English speech in)."
            textSize = 14f; setTextColor(0xFF5F6368.toInt())
        })

        // 1. Language and voice
        val lang = card(root, "1 · LANGUAGE")
        lang.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first })
            setSelection(langIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    langIndex = pos; prefs.edit().putInt("lang", pos).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        lang.addView(button("Check / install the voice for this language", false) { openVoiceSettings() }, gap())

        // 2. Audio mode
        val audio = card(root, "2 · LISTEN TO THE VIDEO'S AUDIO (default)")
        audio.addView(TextView(this).apply {
            text = "Recognises the English speech from the video's sound. Works with any video, a few seconds delay."
            textSize = 13f; setTextColor(0xFF5F6368.toInt())
        })
        modelBtn = button("", false) { downloadModel() }
        audio.addView(modelBtn, gap())

        // 3. Subtitle file mode
        val subs = card(root, "3 · OR USE A SUBTITLE FILE (more accurate)")
        subsInfo = TextView(this).apply {
            textSize = 13f; setTextColor(0xFF5F6368.toInt())
        }
        subs.addView(subsInfo)
        subsBtn = button("Choose .srt / .vtt file", false) { pickSubs.launch(arrayOf("*/*")) }
        subs.addView(subsBtn, gap())
        subsClearBtn = button("Remove the subtitle file", false) {
            subsFile().delete(); prefs.edit().remove("subs_name").apply(); refreshState()
        }
        subs.addView(subsClearBtn, gap())
        accessBtn = button("", false) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        subs.addView(accessBtn, gap())

        // Mix + sync
        val mix = card(root, "SOUND AND SYNC")
        val duckLabel = TextView(this).apply { text = "Original video volume while dubbing: $duck%"; textSize = 14f }
        mix.addView(duckLabel)
        mix.addView(SeekBar(this).apply {
            max = 100; progress = duck
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    duck = p; duckLabel.text = "Original video volume while dubbing: $p%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { prefs.edit().putInt("duck", duck).apply() }
            })
        })
        offsetTv = TextView(this).apply { textSize = 14f; setPadding(0, dp(12), 0, dp(4)) }
        mix.addView(offsetTv)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Dub later  −0.5 s", false) { changeOffset(-500) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(button("Dub earlier  +0.5 s", false) { changeOffset(500) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        mix.addView(row)

        startBtn = button("Start dubbing", true) { onStartStop() }
        root.addView(startBtn, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16) })

        // Live panel
        val live = card(root)
        statusTv = TextView(this).apply { textSize = 14f; setTextColor(0xFF1A73E8.toInt()) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE
        }
        originalTv = TextView(this).apply { textSize = 14f; setTextColor(0xFF5F6368.toInt()); setPadding(0, dp(10), 0, 0) }
        dubbedTv = TextView(this).apply { textSize = 20f; setTextColor(0xFF202124.toInt()); setPadding(0, dp(6), 0, 0) }
        live.addView(statusTv); live.addView(progress, ViewGroup.LayoutParams(-1, -2)); live.addView(originalTv); live.addView(dubbedTv)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFFF4F5F7.toInt())
            isFillViewport = true
            addView(root)
        })
        updateOffsetLabel(Bus.offsetMs.value)
        refreshState()
        handleCiStart(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Bus.status.collect {
                        statusTv.text = it
                        progress.visibility =
                            if (it.startsWith("Preparing") || it.startsWith("Loading")) View.VISIBLE else View.GONE
                    }
                }
                launch { Bus.original.collect { originalTv.text = it } }
                launch { Bus.dubbed.collect { dubbedTv.text = it } }
                launch { Bus.running.collect { startBtn.text = if (it) "Stop dubbing" else "Start dubbing" } }
                launch { Bus.offsetMs.collect { updateOffsetLabel(it) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCiStart(intent)
    }

    /** Test hook used by the automated emulator test: `--es ci_start subs --es ci_lang fr`. */
    private fun handleCiStart(i: Intent?) {
        if (i?.getStringExtra("ci_start") != "subs") return
        i.getStringExtra("ci_lang")?.let { code ->
            val idx = languages.indexOfFirst { it.second == code }
            if (idx >= 0) langIndex = idx
        }
        startDub(Intent(this, DubService::class.java).putExtra("mode", "subs"))
    }

    private fun refreshState() {
        val ready = ModelManager.isReady(this)
        modelBtn.isEnabled = !ready
        modelBtn.text = if (ready) "Speech model ready ✓" else "Download the speech model once (~40 MB)"
        val has = hasSubs()
        subsInfo.text = if (has) "Using: ${prefs.getString("subs_name", "subtitle file")}  — dubbing will follow the video position."
        else "No file chosen. Get a .srt/.vtt for the video, then choose it here."
        subsClearBtn.visibility = if (has) View.VISIBLE else View.GONE
        accessBtn.visibility = if (has) View.VISIBLE else View.GONE
        accessBtn.text = if (listenerEnabled()) "Notification access is ON ✓" else "Turn on Notification access (needed to follow the video)"
    }

    private fun changeOffset(delta: Long) {
        Bus.offsetMs.value += delta
        prefs.edit().putLong("offset", Bus.offsetMs.value).apply()
    }

    private fun updateOffsetLabel(ms: Long) {
        offsetTv.text = "Sync fine-tune (subtitle file mode): %+.1f s".format(ms / 1000.0)
    }

    private fun openVoiceSettings() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (e: Exception) {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (e2: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun downloadModel() {
        modelBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                ModelManager.download(this@MainActivity) { p -> runOnUiThread { modelBtn.text = "Downloading… $p%" } }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            refreshState()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun listenerEnabled() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun onStartStop() {
        if (Bus.running.value) {
            startService(Intent(this, DubService::class.java).setAction(DubService.ACTION_STOP))
            return
        }
        if (hasSubs()) {
            if (!listenerEnabled()) {
                Toast.makeText(this, "Turn on Notification access for Live Dub, then come back", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return
            }
            if (Build.VERSION.SDK_INT >= 33) subsPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startDub(Intent(this, DubService::class.java).putExtra("mode", "subs"))
        } else {
            if (!ModelManager.isReady(this)) {
                Toast.makeText(this, "Download the speech model first", Toast.LENGTH_SHORT).show()
                return
            }
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            audioPermLauncher.launch(perms.toTypedArray())
        }
    }

    private fun startDub(i: Intent) {
        Bus.original.value = ""
        Bus.dubbed.value = ""
        ContextCompat.startForegroundService(
            this, i.putExtra("lang", languages[langIndex].second).putExtra("duck", duck)
        )
    }
}
