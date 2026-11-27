package com.livedub

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
    private var langIndex = 0
    private var duck = 20

    private lateinit var linkEt: EditText
    private lateinit var statusTv: TextView
    private lateinit var originalTv: TextView
    private lateinit var dubbedTv: TextView
    private lateinit var startBtn: Button
    private lateinit var accessBtn: Button
    private lateinit var offsetTv: TextView

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startDubbing() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(20))
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, dp(16), 0, dp(4)) }

        root.addView(TextView(this).apply { text = "Live Dub"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "Takes the video's subtitles, translates them and speaks them in sync with playback. " +
                "In YouTube: Share > Live Dub, or paste the link below."
            textSize = 14f
        })

        root.addView(label("Video link"))
        linkEt = EditText(this).apply { hint = "https://youtu.be/…"; setSingleLine() }
        root.addView(linkEt)

        accessBtn = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        root.addView(accessBtn)

        root.addView(label("Dub language"))
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { langIndex = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

        val duckLabel = label("Original video volume while dubbing: $duck%")
        root.addView(duckLabel)
        root.addView(SeekBar(this).apply {
            max = 100; progress = duck
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    duck = p; duckLabel.text = "Original video volume while dubbing: $p%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        offsetTv = label("Sync fine-tune: 0.0 s")
        root.addView(offsetTv)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Dub later"; setOnClickListener { Bus.offsetMs.value -= 500 }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Dub earlier"; setOnClickListener { Bus.offsetMs.value += 500 }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        startBtn = Button(this).apply { text = "Start dubbing"; setOnClickListener { onStartStop() } }
        root.addView(startBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        statusTv = TextView(this).apply { textSize = 14f; setPadding(0, dp(16), 0, 0) }
        originalTv = TextView(this).apply { textSize = 16f; setPadding(0, dp(12), 0, 0) }
        dubbedTv = TextView(this).apply { textSize = 18f; setPadding(0, dp(8), 0, 0) }
        root.addView(statusTv); root.addView(originalTv); root.addView(dubbedTv)

        setContentView(ScrollView(this).apply { addView(root) })
        handleShare(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Bus.status.collect { statusTv.text = it } }
                launch { Bus.original.collect { originalTv.text = it } }
                launch { Bus.dubbed.collect { dubbedTv.text = it } }
                launch { Bus.running.collect { startBtn.text = if (it) "Stop dubbing" else "Start dubbing" } }
                launch {
                    Bus.offsetMs.collect { offsetTv.text = "Sync fine-tune: %.1f s".format(it / 1000.0) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        val on = listenerEnabled()
        accessBtn.text = if (on) "Notification access: ON ✓" else "Step 1: enable Notification access for Live Dub"
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
            Toast.makeText(this, "Enable Notification access for Live Dub, then come back", Toast.LENGTH_LONG).show()
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
