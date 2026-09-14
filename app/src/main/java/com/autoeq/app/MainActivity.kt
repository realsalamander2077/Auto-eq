package com.autoeq.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
            if (isChecked) {
                val intent = Intent(this, EqualizerService::class.java)
                intent.putExtra(EqualizerService.EXTRA_AUTO_MODE, autoModeToggle.isChecked)
                ContextCompat.startForegroundService(this, intent)
                statusText.text = "Service running"
            } else {
                stopService(Intent(this, EqualizerService::class.java))
                statusText.text = "Service stopped"
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
     * live value labels) so each column lines up like a hardware-style
     * graphic EQ panel.
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

            // Vertical slider via rotation trick: a SeekBar rotated -90
            // degrees inside a square-ish container reads top-to-bottom.
            val sliderContainer = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                layoutParams = colParamsMatch
            }
            val seekBar = SeekBar(this).apply {
                max = 240 // -12.0dB..+12.0dB in 0.1dB steps
                progress = 120 // 0 dB
                rotation = 270f
                progressDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.vertical_slider_track)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val db = (progress - 120) / 10.0
                        valueLabels[index].text = String.format("%.1f", db)
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
            }
            // Swap width/height so the rotated bar fills the column vertically
            sliderContainer.post {
                val h = sliderContainer.height
                seekBar.layoutParams = ViewGroup.LayoutParams(h, 60)
            }
            sliderContainer.addView(seekBar)
            slidersRow.addView(sliderContainer)
            bandSeekBars.add(seekBar)

            val valueLabel = TextView(this).apply {
                text = "0.0"
                setTextColor(0xFF34D399.toInt())
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = colParams
            }
            valueLabelsRow.addView(valueLabel)
            valueLabels.add(valueLabel)
        }
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
