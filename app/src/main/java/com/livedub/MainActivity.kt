package com.livedub

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val languages = listOf(
        "Arabic (العربية)" to "ar", "French" to "fr", "Spanish" to "es", "German" to "de",
        "Turkish" to "tr", "Russian" to "ru", "Hindi" to "hi", "Urdu" to "ur",
        "Indonesian" to "id", "Portuguese" to "pt"
    )
    private val prefs by lazy { getSharedPreferences("live_dub", MODE_PRIVATE) }
    private var langIndex = 0
    private var duck = 20

    private lateinit var linkEt: EditText
    private lateinit var statusTv: TextView
    private lateinit var progress: ProgressBar
    private lateinit var originalTv: TextView
    private lateinit var dubbedTv: TextView
    private lateinit var startBtn: Button
    private lateinit var accessBtn: Button
    private lateinit var offsetTv: TextView

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startDubbing() }

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
        background = if (primary) rounded(0xFF1A73E8.toInt(), 12)
        else GradientDrawable().apply {
            setColor(0xFFE8F0FE.toInt()); cornerRadius = dp(12).toFloat()
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

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
            text = "Speaks the video's subtitles in your language, in sync with playback."
            textSize = 14f; setTextColor(0xFF5F6368.toInt())
        })

        // 1. Setup
        val setup = card(root, "1 · SET UP")
        accessBtn = button("", false) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        setup.addView(accessBtn)
        setup.addView(button("Check / install the voice for this language", false) { openVoiceSettings() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // 2. Video + language
        val video = card(root, "2 · VIDEO AND LANGUAGE")
        linkEt = EditText(this).apply {
            hint = "Paste the YouTube link (or Share > Live Dub)"; setSingleLine(); textSize = 15f
        }
        video.addView(linkEt)
        video.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first })
            setSelection(langIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    langIndex = pos; prefs.edit().putInt("lang", pos).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

        // 3. Mix + sync
        val mix = card(root, "3 · SOUND AND SYNC")
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
        handleShare(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Bus.status.collect {
                        statusTv.text = it
                        progress.visibility =
                            if (it.startsWith("Fetching") || it.startsWith("Preparing")) View.VISIBLE else View.GONE
                    }
                }
                launch { Bus.original.collect { originalTv.text = it } }
                launch { Bus.dubbed.collect { dubbedTv.text = it } }
                launch {
                    Bus.running.collect {
                        startBtn.text = if (it) "Stop dubbing" else "Start dubbing"
                    }
                }
                launch { Bus.offsetMs.collect { updateOffsetLabel(it) } }
            }
        }
    }

    private fun changeOffset(delta: Long) {
        Bus.offsetMs.value += delta
        prefs.edit().putLong("offset", Bus.offsetMs.value).apply()
    }

    private fun updateOffsetLabel(ms: Long) {
        offsetTv.text = "Sync fine-tune: %+.1f s  (+ = dub speaks earlier)".format(ms / 1000.0)
    }

    private fun openVoiceSettings() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (e: Exception) {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (e2: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        accessBtn.text =
            if (listenerEnabled()) "Notification access is ON ✓"
            else "Turn on Notification access for Live Dub"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && linkEt.text.isNullOrBlank()) {
            // Convenience: if a YouTube link was just copied, fill it in.
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (t != null && t.contains("youtu") && Captions.parseId(t) != null) linkEt.setText(t.trim())
        }
    }

    private fun listenerEnabled() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { linkEt.setText(it.trim()) }
        }
    }

    private fun onStartStop() {
        if (Bus.running.value) {
            startService(Intent(this, DubService::class.java).setAction(DubService.ACTION_STOP))
            return
        }
        if (Captions.parseId(linkEt.text.toString()) == null) {
            Toast.makeText(this, "Paste a YouTube link first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!listenerEnabled()) {
            Toast.makeText(this, "Turn on Notification access for Live Dub, then come back", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= 33) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startDubbing()
    }

    private fun startDubbing() {
        val id = Captions.parseId(linkEt.text.toString()) ?: return
        Bus.original.value = ""
        Bus.dubbed.value = ""
        val i = Intent(this, DubService::class.java)
            .putExtra("video", id)
            .putExtra("lang", languages[langIndex].second)
            .putExtra("duck", duck)
        ContextCompat.startForegroundService(this, i)
    }
}
