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
 * Attaches an Equalizer + Visualizer to the REAL audio session that a
 * playback app (Spotify, YouTube Music, etc.) reports via the system
 * broadcasts ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION / _CLOSE_ - this
 * is the actual supported mechanism third-party equalizer apps use.
 *
 * The earlier "attach to session 0" approach looked like it worked
 * (the Equalizer object constructed without throwing) but carried no
 * real audio on this device, confirmed by the Visualizer failing to
 * initialize with "error: -3" and zero frames ever being analyzed.
 * Attaching to the real session ID fixes that at the root.
 *
 * Still best-effort: it only works for apps that actually send these
 * broadcasts (most mainstream players do, but not universally), and a
 * session can close/reopen as tracks change, which is handled below.
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
        const val ACTION_SESSION_OPENED = "com.autoeq.app.SESSION_OPENED"
        const val ACTION_SESSION_CLOSED = "com.autoeq.app.SESSION_CLOSED"
        const val ACTION_RETRY_VISUALIZER = "com.autoeq.app.RETRY_VISUALIZER"
        const val EXTRA_BAND_INDEX = "band_index"
        const val EXTRA_BAND_MILLIBEL = "band_millibel"
        const val EXTRA_PRESET_LABEL = "preset_label"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION_PACKAGE = "session_package"

        const val ACTION_STATUS_UPDATE = "com.autoeq.app.STATUS_UPDATE"
        const val EXTRA_EQ_STATUS = "eq_status"
        const val EXTRA_VIZ_STATUS = "viz_status"
        const val EXTRA_CAPTURE_COUNT = "capture_count"

        private const val ATTACK_SECONDS = 0.12
        private const val RELEASE_SECONDS = 0.6
        private const val TARGET_MAGNITUDE = 42.0
        private const val MAX_GAIN_DB = 12.0
        private const val MIN_GAIN_DB = -12.0
    }

    private var equalizer: Equalizer? = null
    private var visualizer: Visualizer? = null
    private var attachedSessionId: Int? = null
    private var attachedPackageName: String = ""

    private var autoMode = true
    private var currentPreset: EqPreset = EqPreset.FLAT

    private var smoothedGainsDb = DoubleArray(BAND_FREQUENCIES.size)

    private var hwBandCount: Int = 0
    private var hwMinMb: Int = -1200
    private var hwMaxMb: Int = 1200

    private var lastEqStatus: String = "Waiting for a playback app to start (open Spotify and play something)"
    private var lastVizStatus: String = "Not yet attached"
    private var captureCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Waiting for audio session…"))
        broadcastStatus()
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
            ACTION_SESSION_OPENED -> {
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                val pkg = intent.getStringExtra(EXTRA_SESSION_PACKAGE) ?: "unknown"
                if (sessionId != -1) attachToSession(sessionId, pkg)
            }
            ACTION_SESSION_CLOSED -> {
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                if (sessionId != -1 && sessionId == attachedSessionId) {
                    detachCurrentSession("Session closed by $attachedPackageName - waiting for a new one")
                }
            }
            ACTION_RETRY_VISUALIZER -> {
                retryVisualizerOnCurrentSession()
            }
        }
        return START_STICKY
    }

    /**
     * Attaches fresh Equalizer + Visualizer instances to a real,
     * playback-reported audio session. Releases any previous session's
     * effects first.
     */
    private fun attachToSession(sessionId: Int, packageName: String) {
        if (attachedSessionId == sessionId) return // already on it
        releaseEffects()

        attachedSessionId = sessionId
        attachedPackageName = packageName

        try {
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
            val eq = equalizer
            if (eq != null) {
                hwBandCount = eq.numberOfBands.toInt().coerceAtLeast(1)
                val range = eq.bandLevelRange
                hwMinMb = range[0].toInt()
                hwMaxMb = range[1].toInt()
                lastEqStatus = "Attached to $packageName (session $sessionId), $hwBandCount hardware bands"
            } else {
                lastEqStatus = "Equalizer null after construction for session $sessionId"
            }
        } catch (e: Exception) {
            equalizer = null
            lastEqStatus = "FAILED for session $sessionId: ${e.javaClass.simpleName}: ${e.message}"
        }

        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        captureCount++
                        if (autoMode && fft != null && fft.size >= 4) {
                            analyzeAndAdjust(fft, samplingRate)
                        }
                        if (captureCount % 10 == 0) broadcastStatus()
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
            lastVizStatus = "Attached to session $sessionId"
        } catch (e: Exception) {
            visualizer = null
            lastVizStatus = "FAILED for session $sessionId: ${e.javaClass.simpleName}: ${e.message}"
        }

        updateNotification()
        broadcastStatus()
    }

    /**
     * Re-attempts creating the Visualizer on whatever session is currently
     * attached, without touching the Equalizer - used right after the user
     * grants RECORD_AUDIO so they don't have to replay the track again.
     */
    private fun retryVisualizerOnCurrentSession() {
        val sessionId = attachedSessionId ?: run {
            lastVizStatus = "No session attached yet to retry on"
            broadcastStatus()
            return
        }
        try { visualizer?.enabled = false } catch (_: Exception) {}
        try { visualizer?.release() } catch (_: Exception) {}
        visualizer = null

        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        captureCount++
                        if (autoMode && fft != null && fft.size >= 4) {
                            analyzeAndAdjust(fft, samplingRate)
                        }
                        if (captureCount % 10 == 0) broadcastStatus()
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
            lastVizStatus = "Attached to session $sessionId (retried)"
        } catch (e: Exception) {
            visualizer = null
            lastVizStatus = "Retry FAILED for session $sessionId: ${e.javaClass.simpleName}: ${e.message}"
        }
        broadcastStatus()
    }

    private fun detachCurrentSession(reason: String) {        releaseEffects()
        attachedSessionId = null
        attachedPackageName = ""
        lastEqStatus = reason
        lastVizStatus = "Not attached"
        captureCount = 0
        updateNotification()
        broadcastStatus()
    }

    private fun releaseEffects() {
        try { visualizer?.enabled = false } catch (_: Exception) {}
        try { visualizer?.release() } catch (_: Exception) {}
        try { equalizer?.enabled = false } catch (_: Exception) {}
        try { equalizer?.release() } catch (_: Exception) {}
        visualizer = null
        equalizer = null
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

    private fun analyzeAndAdjust(fft: ByteArray, sampleRate: Int) {
        val eq = equalizer ?: return
        if (hwBandCount <= 0) return

        val n = fft.size / 2
        if (n <= 0 || sampleRate <= 0) return

        val nyquist = sampleRate / 2.0

        for (b in BAND_FREQUENCIES.indices) {
            val centerHz = BAND_FREQUENCIES[b].toDouble()
            if (centerHz >= nyquist) continue

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
        val target = if (attachedSessionId != null) attachedPackageName else "no session"
        val status = "$mode - ${currentPreset.label} - $target"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseEffects()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
