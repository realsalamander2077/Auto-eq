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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Attaches an Equalizer + Visualizer to audio session 0 (the general
 * output mix on most devices) and continuously adjusts gain toward a
 * target curve built from three layers:
 *
 *   1. Live spectrum analysis (what's actually playing right now)
 *   2. A fixed equal-loudness compensation curve (how human hearing
 *      perceives different frequencies at the same physical level)
 *   3. An optional named preset offset (Bass Boost, Vocal Clarity, etc)
 *
 * The result is smoothed with separate attack/release time constants
 * so it doesn't audibly jitter on every transient, only reacts to the
 * genuine tonal balance of a track.
 *
 * Same caveats as before: best-effort on session 0, not guaranteed on
 * every OEM/app combination (DRM/offloaded audio can bypass it).
 */
class EqualizerService : Service() {

    companion object {
        const val CHANNEL_ID = "autoeq_channel"
        const val NOTIF_ID = 1
        const val EXTRA_AUTO_MODE = "auto_mode"
        const val ACTION_SET_BAND = "com.autoeq.app.SET_BAND"
        const val ACTION_SET_AUTO_MODE = "com.autoeq.app.SET_AUTO_MODE"
        const val ACTION_SET_PRESET = "com.autoeq.app.SET_PRESET"
        const val ACTION_REQUEST_STATUS = "com.autoeq.app.REQUEST_STATUS"
        const val EXTRA_BAND_INDEX = "band_index"
        const val EXTRA_BAND_MILLIBEL = "band_millibel"
        const val EXTRA_PRESET_LABEL = "preset_label"

        const val ACTION_STATUS_UPDATE = "com.autoeq.app.STATUS_UPDATE"
        const val EXTRA_EQ_STATUS = "eq_status"
        const val EXTRA_VIZ_STATUS = "viz_status"
        const val EXTRA_CAPTURE_COUNT = "capture_count"

        private const val GLOBAL_SESSION = 0

        // Smoothing time constants (seconds). Release is slower than
        // attack so the curve settles gently instead of pumping.
        private const val ATTACK_SECONDS = 0.12
        private const val RELEASE_SECONDS = 0.6

        // Practical middle point for the 0-255 FFT magnitude range
        // Android's Visualizer returns - not a calibrated SPL value.
        private const val TARGET_MAGNITUDE = 42.0
        private const val MAX_GAIN_DB = 12.0
        private const val MIN_GAIN_DB = -12.0
    }

    private var equalizer: Equalizer? = null
    private var visualizer: Visualizer? = null
    private var autoMode = true
    private var currentPreset: EqPreset = EqPreset.FLAT

    // One smoothed gain value (dB) per logical band (BAND_FREQUENCIES.size)
    private var smoothedGainsDb = DoubleArray(BAND_FREQUENCIES.size)

    // Hardware band count/range, resolved once the Equalizer is created
    private var hwBandCount: Int = 0
    private var hwMinMb: Int = -1200
    private var hwMaxMb: Int = 1200

    private var lastEqStatus: String = "Not yet attempted"
    private var lastVizStatus: String = "Not yet attempted"
    private var captureCount: Int = 0

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
                if (index in BAND_FREQUENCIES.indices) setBandManual(index, mb)
            }
            ACTION_SET_AUTO_MODE -> {
                autoMode = intent.getBooleanExtra(EXTRA_AUTO_MODE, true)
                updateNotification()
            }
            ACTION_SET_PRESET -> {
                val label = intent.getStringExtra(EXTRA_PRESET_LABEL)
                if (label != null) {
                    currentPreset = EqPreset.fromLabel(label)
                    updateNotification()
                }
            }
            ACTION_REQUEST_STATUS -> {
                broadcastStatus()
            }
        }
        return START_STICKY
    }

    private fun setupEqualizer() {
        var eqStatus: String
        try {
            equalizer = Equalizer(0, GLOBAL_SESSION).apply {
                enabled = true
            }
            val eq = equalizer
            if (eq != null) {
                hwBandCount = eq.numberOfBands.toInt().coerceAtLeast(1)
                val range = eq.bandLevelRange
                hwMinMb = range[0].toInt()
                hwMaxMb = range[1].toInt()
                eqStatus = "Equalizer attached OK ($hwBandCount hardware bands, range ${hwMinMb / 100}..${hwMaxMb / 100} dB)"
            } else {
                eqStatus = "Equalizer object is null after construction (unknown failure)"
            }
        } catch (e: Exception) {
            equalizer = null
            eqStatus = "Equalizer FAILED to attach: ${e.javaClass.simpleName}: ${e.message}"
        }

        var vizStatus: String
        try {
            visualizer = Visualizer(GLOBAL_SESSION).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        captureCount++
                        if (autoMode && fft != null && fft.size >= 4) {
                            analyzeAndAdjust(fft, samplingRate)
                        }
                        broadcastStatus()
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
            vizStatus = "Visualizer attached OK"
        } catch (e: Exception) {
            visualizer = null
            vizStatus = "Visualizer FAILED to attach: ${e.javaClass.simpleName}: ${e.message}"
        }

        lastEqStatus = eqStatus
        lastVizStatus = vizStatus
        updateNotification()
        broadcastStatus()
    }

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_EQ_STATUS, lastEqStatus)
            putExtra(EXTRA_VIZ_STATUS, lastVizStatus)
            putExtra(EXTRA_CAPTURE_COUNT, captureCount)
        }
        sendBroadcast(intent)
    }

    /**
     * Core analysis pass, called a few times per second:
     *   1. Buckets FFT bins into our 10 logical bands by actual
     *      frequency (log-spaced, not equal bin counts).
     *   2. Computes a raw target gain per band from how loud that band
     *      is relative to TARGET_MAGNITUDE (real 20*log10 dB math).
     *   3. Adds the equal-loudness compensation and any active preset
     *      offset.
     *   4. Clamps, then smooths toward it with attack/release.
     *   5. Maps the 10 logical bands onto whatever band count the
     *      device's actual hardware Equalizer supports.
     */
    private fun analyzeAndAdjust(fft: ByteArray, sampleRate: Int) {
        val eq = equalizer ?: return
        if (hwBandCount <= 0) return

        val n = fft.size / 2
        if (n <= 0 || sampleRate <= 0) return

        val nyquist = sampleRate / 2.0

        for (b in BAND_FREQUENCIES.indices) {
            val centerHz = BAND_FREQUENCIES[b].toDouble()
            if (centerHz >= nyquist) {
                continue
            }

            val lowHz = centerHz / 1.5
            val highHz = min(centerHz * 1.5, nyquist - 1.0)

            val startBin = max(0, ((lowHz / nyquist) * n).toInt())
            val endBin = min(n - 1, ((highHz / nyquist) * n).toInt())
            if (endBin <= startBin) continue

            var sumSq = 0.0
            var count = 0
            for (j in startBin..endBin) {
                val idx = 2 * j
                if (idx + 1 >= fft.size) break
                val re = fft[idx].toInt()
                val im = fft[idx + 1].toInt()
                sumSq += (re * re + im * im).toDouble()
                count++
            }
            if (count == 0) continue

            val rms = Math.sqrt(sumSq / count)
            if (rms <= 0.0) continue

            val diffDb = 20.0 * ln(TARGET_MAGNITUDE / max(rms, 1.0)) / ln(10.0)

            val loudnessOffset = EQUAL_LOUDNESS_OFFSET_DB.getOrElse(b) { 0.0 }
            val presetOffset = currentPreset.offsetsDb.getOrElse(b) { 0.0 }

            val targetDb = (diffDb + loudnessOffset + presetOffset)
                .coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

            val current = smoothedGainsDb[b]
            val rising = targetDb > current
            val tauSeconds = if (rising) ATTACK_SECONDS else RELEASE_SECONDS

            val intervalSeconds = 2.0 / max(1, Visualizer.getMaxCaptureRate())
            val alpha = 1.0 - Math.exp(-intervalSeconds / tauSeconds)
            smoothedGainsDb[b] = current + alpha * (targetDb - current)
        }

        applySmoothedGainsToHardware(eq)
    }

    /**
     * Maps our fixed 10 logical bands onto however many bands the real
     * device Equalizer exposes (commonly 5-6 on many phones). Bands are
     * averaged together when the hardware has fewer bands than we do.
     */
    private fun applySmoothedGainsToHardware(eq: Equalizer) {
        for (hwIndex in 0 until hwBandCount) {
            val loLogical = (hwIndex * BAND_FREQUENCIES.size) / hwBandCount
            val hiLogical = max(loLogical + 1, ((hwIndex + 1) * BAND_FREQUENCIES.size) / hwBandCount)

            var sum = 0.0
            var count = 0
            for (li in loLogical until hiLogical.coerceAtMost(BAND_FREQUENCIES.size)) {
                sum += smoothedGainsDb[li]
                count++
            }
            if (count == 0) continue

            val avgDb = sum / count
            val millibel = (avgDb * 100).toInt().coerceIn(hwMinMb, hwMaxMb)
            try {
                eq.setBandLevel(hwIndex.toShort(), millibel.toShort())
            } catch (_: Exception) {
            }
        }
    }

    private fun setBandManual(logicalIndex: Int, millibel: Int) {
        val eq = equalizer ?: return
        if (hwBandCount <= 0) return
        val hwIndex = (logicalIndex * hwBandCount) / BAND_FREQUENCIES.size
        val clamped = millibel.coerceIn(hwMinMb, hwMaxMb)
        try {
            eq.setBandLevel(hwIndex.coerceIn(0, hwBandCount - 1).toShort(), clamped.toShort())
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
        val mode = if (autoMode) "Auto" else "Manual"
        val status = "$mode - ${currentPreset.label}"
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
