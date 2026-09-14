package com.autoeq.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private val bandIds = intArrayOf(
        R.id.band0, R.id.band1, R.id.band2, R.id.band3, R.id.band4
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val permissionButton = findViewById<MaterialButton>(R.id.permissionButton)
        val serviceToggle = findViewById<SwitchMaterial>(R.id.serviceToggle)
        val autoModeToggle = findViewById<SwitchMaterial>(R.id.autoModeToggle)

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

        bandIds.forEachIndexed { index, id ->
            val seekBar = findViewById<SeekBar>(id)
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    // Map 0..30 slider to -1500..+1500 millibel (-15dB..+15dB)
                    val millibel = (progress - 15) * 100
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

        setBandsEnabled(false)
    }

    private fun setBandsEnabled(enabled: Boolean) {
        bandIds.forEach { id -> findViewById<SeekBar>(id).isEnabled = enabled }
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
