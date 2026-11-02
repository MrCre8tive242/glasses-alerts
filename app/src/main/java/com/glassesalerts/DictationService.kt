package com.glassesalerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Listens for one spoken reply using Android's speech recognition, then reports the text back to
 * AlertListenerService. It runs as a short-lived foreground service (microphone type) because
 * Android only lets an app record audio while it is visible or running as one.
 *
 * AlertListenerService shows a 1-pixel invisible overlay before starting this service, because
 * Android only grants the microphone to a foreground service whose app has a visible window.
 *
 * Try 1 uses the glasses microphone (if connected). If the recognizer fails right away (which
 * means a technical problem, not silence), try 2 uses the phone microphone. If that fails right
 * away too, the service reports "unavailable" so the app can fall back to quick replies.
 */
class DictationService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tone: ToneGenerator? = null
    private var started = false
    private var finished = false
    private var routed = false
    private var oldMode = AudioManager.MODE_NORMAL
    private var attempt = 0
    private var listenStartedAt = 0L
    private var heardSpeech = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = startAsForeground()
        if (!started) {
            started = true
            if (ok) begin() else finish(null, true)
        }
        return START_NOT_STICKY
    }

    /** False if Android refused (it throws instead of returning, which would crash the whole app). */
    private fun startAsForeground(): Boolean {
        return try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Spoken replies", NotificationManager.IMPORTANCE_LOW)
            )
            val n = Notification.Builder(this, CHANNEL)
                .setContentTitle("Listening for your reply")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            true
        } catch (e: Exception) {
            DictationLog.record("Android refused the microphone service: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            DictationLog.record("Microphone permission is not granted")
            finish(null, true)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            DictationLog.record("No speech recognizer installed on this phone")
            finish(null, true)
            return
        }
        DictationLog.record("starting...")
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (e: Exception) {
            // no beep: fine
        }
        startAttempt(useGlasses = true)
        main.postDelayed({ finish(null, false) }, 30_000L)
    }

    private fun startAttempt(useGlasses: Boolean) {
        if (finished) return
        attempt++
        heardSpeech = false
        unroute()
        if (useGlasses) routeToGlassesMic()
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                try {
                    tone?.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
                } catch (e: Exception) {
                }
            }

            override fun onBeginningOfSpeech() {
                heardSpeech = true
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                val elapsed = SystemClock.elapsedRealtime() - listenStartedAt
                val mic = if (routed) "glasses" else "phone"
                DictationLog.record("Try $attempt ($mic mic): error $error ${errorName(error)} after $elapsed ms")
                if (error == 9) { // no microphone permission
                    finish(null, true)
                    return
                }
                // An error within 2.5 seconds, before any speech, is a technical failure, not silence.
                val technical = elapsed < 2_500L && !heardSpeech
                if (technical && attempt == 1) {
                    main.postDelayed({ startAttempt(useGlasses = false) }, 300L)
                    return
                }
                finish(null, technical)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                val mic = if (routed) "glasses" else "phone"
                DictationLog.record("Try $attempt ($mic mic): heard ${text?.length ?: 0} characters")
                finish(text, false)
            }
        })

        // Give the Bluetooth microphone a moment to connect before listening.
        main.postDelayed({ startListening() }, if (routed) 900L else 150L)
    }

    private fun startListening() {
        if (finished) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        listenStartedAt = SystemClock.elapsedRealtime()
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            DictationLog.record("startListening failed: ${e.message}")
            finish(null, true)
        }
    }

    private fun finish(text: String?, unavailable: Boolean) {
        if (finished) return
        finished = true
        main.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
        unroute()
        try {
            tone?.release()
        } catch (e: Exception) {
        }
        tone = null
        val cb = listener
        listener = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        stopSelf()
        cb?.invoke(text, unavailable)
    }

    override fun onDestroy() {
        finish(null, false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ audio routing

    @Suppress("DEPRECATION")
    private fun routeToGlassesMic() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (dev != null) {
                    oldMode = am.mode
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    routed = am.setCommunicationDevice(dev)
                }
            } else if (am.isBluetoothScoAvailableOffCall) {
                oldMode = am.mode
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                routed = true
            }
            Log.d(TAG, "glasses microphone routed: $routed")
        } catch (e: Exception) {
            Log.d(TAG, "routing failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun unroute() {
        if (!routed) return
        routed = false
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
            am.mode = oldMode
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val TAG = "GlassesAlerts"
        private const val CHANNEL = "dictation"
        private const val NOTIFICATION_ID = 4242

        /** Called once when dictation ends: (recognized text or null, true if speaking is unavailable). */
        @Volatile
        var listener: ((String?, Boolean) -> Unit)? = null

        fun errorName(code: Int): String = when (code) {
            1 -> "network timeout"
            2 -> "network"
            3 -> "audio recording"
            4 -> "server"
            5 -> "client"
            6 -> "no speech heard"
            7 -> "no match"
            8 -> "recognizer busy"
            9 -> "no microphone permission"
            10 -> "too many requests"
            11 -> "server disconnected"
            12 -> "language not supported"
            13 -> "language unavailable"
            else -> "other"
        }
    }
}
