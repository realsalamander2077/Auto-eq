package com.autoeq.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/**
 * Listens for the system broadcasts that playback apps (Spotify, YouTube
 * Music, etc.) send when they open or close an audio session, specifically
 * so external equalizer/effect apps can attach to the REAL session ID -
 * this is the actual supported mechanism for third-party system EQs, not
 * the "attach to session 0" trick, which doesn't carry real audio on many
 * devices (as our diagnostics just confirmed).
 *
 * Declared in the manifest (not registered only at runtime) so the
 * broadcast reaches us even if MainActivity isn't currently open.
 */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (sessionId == -1) return

        val serviceIntent = Intent(context, EqualizerService::class.java)
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                serviceIntent.action = EqualizerService.ACTION_SESSION_OPENED
                serviceIntent.putExtra(EqualizerService.EXTRA_SESSION_ID, sessionId)
                serviceIntent.putExtra(
                    EqualizerService.EXTRA_SESSION_PACKAGE,
                    intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "unknown"
                )
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                serviceIntent.action = EqualizerService.ACTION_SESSION_CLOSED
                serviceIntent.putExtra(EqualizerService.EXTRA_SESSION_ID, sessionId)
            }
            else -> return
        }

        try {
            context.startService(serviceIntent)
        } catch (_: Exception) {
            // Service may not be able to start from background on some
            // Android versions if it isn't already running - nothing more
            // we can do from a BroadcastReceiver's limited execution window
        }
    }
}
