package com.autoeq.app

import android.Manifest
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

        buildBandColumns(freqLabelsRow, slidersRow, valueLabelsRow)

        val presetLabels = EqPreset.values().map { it.label }
        presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, presetLabels
        )
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position !in presetLabels.indices) return
                val label = presetLabels[position]
                val intent = Intent(this@MainActivity, EqualizerService::class.java)
                intent.action = EqualizerService.ACTION_SET_PRESET
                intent.putExtra(EqualizerService.EXTRA_PRESET_LABEL, label)
                startService(intent)
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

            // Vertical slider via rotation trick, sized up front so no
            // runtime remeasure/resize is needed.
            val seekBar = SeekBar(this).apply {
                max = 240 // -12.0dB..+12.0dB in 0.1dB steps
                progress = 120 // 0 dB
                rotation = 270f
                layoutParams = LinearLayout.LayoutParams(sliderLengthPx, sliderThicknessPx)
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

    private fun formatFrequencyLabel(hz: Int): String =
        if (hz >= 1000) "${hz / 1000}K" else "$hz"

    private fun setBandsEnabled(enabled: Boolean) {
        bandSeekBars.forEach { it.isEnabled = enabled }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }
}
