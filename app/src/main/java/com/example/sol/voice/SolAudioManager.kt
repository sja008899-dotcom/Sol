package com.example.sol.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var listeningFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var speakingFocusRequest: AudioFocusRequest? = null

    @Volatile
    var hasListeningFocus: Boolean = false
        private set

    @Volatile
    var hasSpeakingFocus: Boolean = false
        private set

    /**
     * Requests transient exclusive audio focus while listening via Vosk or Android SpeechRecognizer
     * so background music/media pauses cleanly and avoids acoustic bleed into the mic.
     */
    fun requestListeningFocus(onFocusLost: () -> Unit): Boolean {
        abandonListeningFocus()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                hasListeningFocus = false
                mainHandler.post { onFocusLost() }
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(listener, mainHandler)
            .build()
        val granted = runCatching {
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(true)
        if (granted) {
            listeningFocusRequest = request
            hasListeningFocus = true
        }
        return granted
    }

    fun abandonListeningFocus() {
        val req = listeningFocusRequest
        listeningFocusRequest = null
        hasListeningFocus = false
        if (req != null) {
            runCatching { audioManager.abandonAudioFocusRequest(req) }
        }
    }

    /**
     * Requests transient ducking focus while Sol speaks so media volume lowers cleanly.
     */
    fun requestSpeakingFocus(onFocusLost: () -> Unit): Boolean {
        abandonSpeakingFocus()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                hasSpeakingFocus = false
                mainHandler.post { onFocusLost() }
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(listener, mainHandler)
            .build()
        val granted = runCatching {
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(true)
        if (granted) {
            speakingFocusRequest = request
            hasSpeakingFocus = true
        }
        return granted
    }

    fun abandonSpeakingFocus() {
        val req = speakingFocusRequest
        speakingFocusRequest = null
        hasSpeakingFocus = false
        if (req != null) {
            runCatching { audioManager.abandonAudioFocusRequest(req) }
        }
    }

    /**
     * Short auditory cue on start/stop listening using ToneGenerator (no extra runtime permissions).
     */
    fun playVoiceCue(isStart: Boolean) {
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val toneType = if (isStart) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 40)
            tg.startTone(toneType, 90)
            mainHandler.postDelayed({ runCatching { tg.release() } }, 160L)
        }
    }

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
