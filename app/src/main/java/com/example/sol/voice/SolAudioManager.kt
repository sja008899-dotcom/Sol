package com.example.sol.voice

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

data class AudioVolumeStatus(
    val mediaCurrent: Int,
    val mediaMax: Int,
    val ringCurrent: Int,
    val ringMax: Int,
    val ringerModeLabel: String,
)

class SolAudioManager(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun getVolumeStatus(): AudioVolumeStatus {
        val mediaCur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mediaMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val ringCur = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val ringMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
        val ringerLabel = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "بی‌صدا"
            AudioManager.RINGER_MODE_VIBRATE -> "لرزش"
            else -> "عادی"
        }
        return AudioVolumeStatus(mediaCur, mediaMax, ringCur, ringMax, ringerLabel)
    }

    fun adjustMediaVolume(deltaSteps: Int): AudioVolumeStatus {
        val direction = when {
            deltaSteps > 0 -> AudioManager.ADJUST_RAISE
            deltaSteps < 0 -> AudioManager.ADJUST_LOWER
            else -> AudioManager.ADJUST_SAME
        }
        repeat(kotlin.math.abs(deltaSteps).coerceIn(1, 5)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }
        return getVolumeStatus()
    }

    fun setMediaVolumePercent(percent: Int): AudioVolumeStatus {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = ((percent.coerceIn(0, 100) / 100f) * max).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return getVolumeStatus()
    }

    fun dispatchMediaKey(keyCode: Int): Boolean {
        return runCatching {
            val eventTime = SystemClock.uptimeMillis()
            val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
            true
        }.getOrDefault(false)
    }
}
