package com.autoeq.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private val bandSeekBars = mutableListOf<SeekBar>()
    private val valueLabels = mutableListOf<TextView>()
    private lateinit var diagnosticsText: TextView

    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val eqStatus = intent?.getStringExtra(EqualizerService.EXTRA_EQ_STATUS) ?: return
            val vizStatus = intent.getStringExtra(EqualizerService.EXTRA_VIZ_STATUS) ?: ""
            val captureCount = intent.getIntExtra(EqualizerService.EXTRA_CAPTURE_COUNT, 0)
            diagnosticsText.text = "EQ: $eqStatus\nAnalyzer: $vizStatus\nAudio frames analyzed so far: $captureCount" +
                if (captureCount == 0) "\n\n⚠️ 0 frames analyzed means the analyzer isn't receiving any audio from what's playing - the EQ has nothing to react to, even if it attached successfully." else ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext, defaultHandler))

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        showLastCrashIfAny()

        val statusText = findViewById<TextView>(R.id.statusText)
        val permissionButton = findViewById<MaterialButton>(R.id.permissionButton)
        val serviceToggle = findViewById<SwitchMaterial>(R.id.serviceToggle)
        val autoModeToggle = findViewById<SwitchMaterial>(R.id.autoModeToggle)
        val presetSpinner = findViewById<Spinner>(R.id.presetSpinner)

        val freqLabelsRow = findViewById<LinearLayout>(R.id.freqLabelsRow)
        val slidersRow = findViewById<LinearLayout>(R.id.slidersRow)
        val valueLabelsRow = findViewById<LinearLayout>(R.id.valueLabelsRow)
        diagnosticsText = findViewById(R.id.diagnosticsText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                statusReceiver,
                android.content.IntentFilter(EqualizerService.ACTION_STATUS_UPDATE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, android.content.IntentFilter(EqualizerService.ACTION_STATUS_UPDATE))
        }

        buildBandColumns(freqLabelsRow, slidersRow, valueLabelsRow)

        val presetLabels = EqPreset.values().map { it.label }
        presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, presetLabels
        )
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position !in presetLabels.indices) return
                val label = presetLabels[position]
                val preset = EqPreset.fromLabel(label)

                // Tell the service (used inside Auto mode's calculation)
                val presetIntent = Intent(this@MainActivity, EqualizerService::class.java)
                presetIntent.action = EqualizerService.ACTION_SET_PRESET
                presetIntent.putExtra(EqualizerService.EXTRA_PRESET_LABEL, label)
                startService(presetIntent)

                // Also snap the manual sliders to this preset's curve so
                // choosing a preset has an immediate, audible effect even
                // with Auto mode off - previously presets only fed into
                // Auto mode's math and did nothing visible in Manual mode.
                preset.offsetsDb.forEachIndexed { i, db ->
                    if (i < bandSeekBars.size && i < valueLabels.size) {
                        val sliderProgress = ((db * 10).toInt() + 120).coerceIn(0, 240)
                        bandSeekBars[i].progress = sliderProgress
                        valueLabels[i].text = String.format("%.1f", db)

                        val bandIntent = Intent(this@MainActivity, EqualizerService::class.java)
                        bandIntent.action = EqualizerService.ACTION_SET_BAND
                        bandIntent.putExtra(EqualizerService.EXTRA_BAND_INDEX, i)
                        bandIntent.putExtra(EqualizerService.EXTRA_BAND_MILLIBEL, (db * 100).toInt())
                        startService(bandIntent)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        permissionButton.setOnClickListener {
            requestNotifPermissionIfNeeded()
        }

        serviceToggle.setOnCheckedChangeListener { _, isChecked ->
            try {
                if (isChecked) {
                    val intent = Intent(this, EqualizerService::class.java)
                    intent.putExtra(EqualizerService.EXTRA_AUTO_MODE, autoModeToggle.isChecked)
                    ContextCompat.startForegroundService(this, intent)
                    statusText.text = "Service running"
                } else {
                    stopService(Intent(this, EqualizerService::class.java))
                    statusText.text = "Service stopped"
                }
            } catch (e: Exception) {
                statusText.text = "Service failed to start: ${e.message}"
            }
        }

        autoModeToggle.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, EqualizerService::class.java)
            intent.action = EqualizerService.ACTION_SET_AUTO_MODE
            intent.putExtra(EqualizerService.EXTRA_AUTO_MODE, isChecked)
            startService(intent)
            setBandsEnabled(!isChecked)
        }

        setBandsEnabled(false)
    }

    /**
     * Builds three aligned rows (frequency labels / vertical sliders /
     * live value labels). Sliders use a fixed pre-computed pixel size
     * (converted from dp up front) instead of measuring the parent at
     * runtime, which avoids relying on a post-layout callback.
     */
    private fun buildBandColumns(
        freqLabelsRow: LinearLayout,
        slidersRow: LinearLayout,
        valueLabelsRow: LinearLayout
    ) {
        bandSeekBars.clear()
        valueLabels.clear()
        freqLabelsRow.removeAllViews()
        slidersRow.removeAllViews()
        valueLabelsRow.removeAllViews()

        val density = resources.displayMetrics.density
        val sliderLengthPx = (220 * density).toInt()
        val sliderThicknessPx = (28 * density).toInt()

        BAND_FREQUENCIES.forEachIndexed { index, freqHz ->
            val colParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val colParamsMatch = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)

            val freqLabel = TextView(this).apply {
                text = formatFrequencyLabel(freqHz)
                setTextColor(0xFF9CA3AF.toInt())
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = colParams
            }
            freqLabelsRow.addView(freqLabel)

            val valueLabel = TextView(this).apply {
                text = "0.0"
                setTextColor(0xFF34D399.toInt())
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = colParams
            }

            // Real vertical slider: final on-screen box is thin (width)
            // and tall (height) - VerticalSeekBar handles the internal
            // rotation and touch remapping itself, no rotation attribute
            // or post-layout resize hack needed here.
            val seekBar = VerticalSeekBar(this).apply {
                max = 240 // -12.0dB..+12.0dB in 0.1dB steps
                progress = 120 // 0 dB
                layoutParams = LinearLayout.LayoutParams(sliderThicknessPx, sliderLengthPx)
                try {
                    progressDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.vertical_slider_track)
                } catch (_: Exception) {
                    // Fall back to the system default drawable if the custom one fails to load
                }
            }

            val sliderContainer = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                layoutParams = colParamsMatch
                addView(seekBar)
            }
            slidersRow.addView(sliderContainer)
            bandSeekBars.add(seekBar)

            // Listener attached after the SeekBar is fully configured and
            // added, so an index lookup here always has a matching label.
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val db = (progress - 120) / 10.0
                    valueLabel.text = String.format("%.1f", db)
                    if (!fromUser) return
                    val millibel = (db * 100).toInt()
                    val intent = Intent(this@MainActivity, EqualizerService::class.java)
                    intent.action = EqualizerService.ACTION_SET_BAND
                    intent.putExtra(EqualizerService.EXTRA_BAND_INDEX, index)
                    intent.putExtra(EqualizerService.EXTRA_BAND_MILLIBEL, millibel)
                    startService(intent)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            valueLabelsRow.addView(valueLabel)
            valueLabels.add(valueLabel)
        }
    }

    /**
     * If the app crashed last time it ran, show the saved stack trace
     * in a dialog on this launch so it can be read/screenshotted, then
     * clear it so it doesn't reappear on every future launch.
     */
    private fun showLastCrashIfAny() {
        val crashText = CrashHandler.readLastCrash(this) ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Last crash")
            .setMessage(crashText)
            .setPositiveButton("OK") { _, _ -> CrashHandler.clearLastCrash(this) }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val recordAudioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (recordAudioIndex != -1 && grantResults.getOrNull(recordAudioIndex) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(this, EqualizerService::class.java)
            intent.action = EqualizerService.ACTION_RETRY_VISUALIZER
            try { startService(intent) } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, EqualizerService::class.java)
        intent.action = EqualizerService.ACTION_REQUEST_STATUS
        try {
            startService(intent)
        } catch (_: Exception) {
            // Service isn't running yet (toggle is off) - nothing to request status from
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
    }

    private fun formatFrequencyLabel(hz: Int): String =
        if (hz >= 1000) "${hz / 1000}K" else "$hz"

    private fun setBandsEnabled(enabled: Boolean) {
        bandSeekBars.forEach { it.isEnabled = enabled }
    }

    private fun requestNotifPermissionIfNeeded() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Required for the Visualizer (spectrum analysis behind Auto mode) -
        // reading audio content, even just to analyze it and not save/send
        // it anywhere, is treated like recording by modern Android.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }
}
