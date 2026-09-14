package com.autoeq.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Attaches an Equalizer + Visualizer to audio session 0, which on most
 * devices represents the general output mix rather than one app's stream.
 * This is the same approach used by third-party system-wide EQ apps
 * (e.g. Wavelet, Flat Equalizer). It is best-effort: some OEM audio
 * stacks, DRM-protected streams, or apps using audio offload will not
 * be affected. There is no public Android API that guarantees control
 * over literally every sound the device makes.
 */
class EqualizerService : Service() {

    companion object {
        const val CHANNEL_ID = "autoeq_channel"
        const val NOTIF_ID = 1
        const val EXTRA_AUTO_MODE = "auto_mode"
        const val ACTION_SET_BAND = "com.autoeq.app.SET_BAND"
        const val ACTION_SET_AUTO_MODE = "com.autoeq.app.SET_AUTO_MODE"
        const val EXTRA_BAND_INDEX = "band_index"
        const val EXTRA_BAND_MILLIBEL = "band_millibel"

        // Global audio session. 0 = output mix on most Android versions.
        private const val GLOBAL_SESSION = 0
    }

    private var equalizer: Equalizer? = null
    private var visualizer: Visualizer? = null
    private var autoMode = true
    private var analyzerThread: Thread? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        setupEqualizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_BAND -> {
                val index = intent.getIntExtra(EXTRA_BAND_INDEX, -1)
                val mb = intent.getIntExtra(EXTRA_BAND_MILLIBEL, 0)
                if (index >= 0) setBandManual(index, mb)
            }
            ACTION_SET_AUTO_MODE -> {
                autoMode = intent.getBooleanExtra(EXTRA_AUTO_MODE, true)
                updateNotification()
            }
        }
        return START_STICKY
    }

    private fun setupEqualizer() {
        try {
            equalizer = Equalizer(0, GLOBAL_SESSION).apply {
                enabled = true
            }
        } catch (e: Exception) {
            // Some devices refuse a global-session effect outright.
            equalizer = null
        }

        try {
            visualizer = Visualizer(GLOBAL_SESSION).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (autoMode && fft != null) analyzeAndAdjust(fft)
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (e: Exception) {
            visualizer = null
        }

        updateNotification()
    }

    /**
     * Very simple auto-EQ: looks at FFT magnitude in five rough bands and
     * nudges gain toward a flat target curve, the same idea as the web
     * prototype, applied here to the global equalizer bands.
     */
    private fun analyzeAndAdjust(fft: ByteArray) {
        val eq = equalizer ?: return
        val bandCount = eq.numberOfBands
        if (bandCount <= 0) return

        val n = fft.size / 2
        if (n <= 0) return

        val targetBands = minOf(bandCount.toInt(), 5)
        val chunk = n / targetBands
        for (b in 0 until targetBands) {
            var sum = 0.0
            var count = 0
            val start = b * chunk
            val end = if (b == targetBands - 1) n else start + chunk
            for (i in start until end) {
                val re = fft[2 * i].toInt()
                val im = if (2 * i + 1 < fft.size) fft[2 * i + 1].toInt() else 0
                sum += Math.sqrt((re * re + im * im).toDouble())
                count++
            }
            val avg = if (count > 0) sum / count else 0.0
            val targetAvg = 40.0
            val diff = (targetAvg - avg).coerceIn(-30.0, 30.0)
            val range = eq.bandLevelRange
            val minMb = range[0]
            val maxMb = range[1]
            val gainMb = (diff * 15).toInt().coerceIn(minMb.toInt(), maxMb.toInt())
            try {
                eq.setBandLevel(b.toShort(), gainMb.toShort())
            } catch (_: Exception) {
            }
        }
    }

    private fun setBandManual(index: Int, millibel: Int) {
        try {
            equalizer?.setBandLevel(index.toShort(), millibel.toShort())
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Auto Equalizer", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Equalizer")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val status = if (autoMode) "Auto-EQ running" else "Manual EQ running"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { visualizer?.enabled = false } catch (_: Exception) {}
        try { visualizer?.release() } catch (_: Exception) {}
        try { equalizer?.enabled = false } catch (_: Exception) {}
        try { equalizer?.release() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
