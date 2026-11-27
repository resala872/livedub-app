package com.livedub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
    private var duck = 40

    private lateinit var statusTv: TextView
    private lateinit var originalTv: TextView
    private lateinit var dubbedTv: TextView
    private lateinit var startBtn: Button
    private lateinit var modelBtn: Button

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, DubService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", data)
                    .putExtra("lang", languages[langIndex].second)
                    .putExtra("duck", duck)
                ContextCompat.startForegroundService(this, i)
            }
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { m ->
            if (m[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) launchProjection()
            else Toast.makeText(this, "Microphone permission is required to capture audio", Toast.LENGTH_LONG).show()
        }

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
            text = "Speaks a translated voice over any video playing on this phone (YouTube, Morphe, …). English audio in."
            textSize = 14f
        })

        modelBtn = Button(this).apply { setOnClickListener { downloadModel() } }
        root.addView(modelBtn)

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

        startBtn = Button(this).apply {
            text = "Start dubbing"
            setOnClickListener { onStartStop() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        statusTv = TextView(this).apply { textSize = 14f; gravity = Gravity.START; setPadding(0, dp(16), 0, 0) }
        originalTv = TextView(this).apply { textSize = 16f; setPadding(0, dp(12), 0, 0) }
        dubbedTv = TextView(this).apply { textSize = 18f; setPadding(0, dp(8), 0, 0) }
        root.addView(statusTv); root.addView(originalTv); root.addView(dubbedTv)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshModelButton()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Bus.status.collect { statusTv.text = it } }
                launch { Bus.original.collect { originalTv.text = if (it.isEmpty()) "" else "EN: $it" } }
                launch { Bus.dubbed.collect { dubbedTv.text = it } }
                launch { Bus.running.collect { startBtn.text = if (it) "Stop dubbing" else "Start dubbing" } }
            }
        }
    }

    private fun refreshModelButton() {
        val ready = ModelManager.isReady(this)
        modelBtn.isEnabled = !ready
        modelBtn.text = if (ready) "Speech model ready ✓" else "Download speech model (~40 MB)"
    }

    private fun downloadModel() {
        modelBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                ModelManager.download(this@MainActivity) { p ->
                    runOnUiThread { modelBtn.text = "Downloading… $p%" }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            refreshModelButton()
        }
    }

    private fun onStartStop() {
        if (Bus.running.value) {
            startService(Intent(this, DubService::class.java).setAction(DubService.ACTION_STOP))
            return
        }
        if (!ModelManager.isReady(this)) {
            Toast.makeText(this, "Download the speech model first", Toast.LENGTH_SHORT).show()
            return
        }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    private fun launchProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
