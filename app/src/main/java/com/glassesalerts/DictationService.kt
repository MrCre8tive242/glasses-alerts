package com.glassesalerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Listens for one spoken reply using Android's speech recognition, then reports the text back to
 * AlertListenerService. It runs as a short-lived foreground service (microphone type) because
 * Android only lets an app record audio while it is visible or running as one.
 *
 * To work from the background it also shows a 1-pixel invisible overlay (uses the
 * "Display over other apps" permission) and routes audio to the glasses microphone if connected.
 */
class DictationService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var overlay: View? = null
    private var tone: ToneGenerator? = null
    private var started = false
    private var finished = false
    private var routed = false
    private var oldMode = AudioManager.MODE_NORMAL

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (!started) {
            started = true
            begin()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Spoken replies", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Listening for your reply")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(this)
        ) {
            Log.d(TAG, "dictation unavailable: permission or recognizer missing")
            finish(null, true)
            return
        }
        addOverlay()
        routeToGlassesMic()
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (e: Exception) {
            // no beep: fine
        }

        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "ready for speech")
                try {
                    tone?.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
                } catch (e: Exception) {
                }
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                Log.d(TAG, "recognizer error $error")
                val unavailable = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                    error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_SERVER
                finish(null, unavailable)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                Log.d(TAG, "recognized: ${text?.length ?: 0} characters")
                finish(text, false)
            }
        })

        // Give the Bluetooth microphone a moment to connect before listening.
        main.postDelayed({ startListening() }, if (routed) 600L else 50L)
        main.postDelayed({ finish(null, false) }, 25_000L)
    }

    private fun startListening() {
        if (finished) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            Log.d(TAG, "startListening failed: ${e.message}")
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
        removeOverlay()
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

    // ------------------------------------------------------------------ overlay

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            wm.addView(v, lp)
            overlay = v
        } catch (e: Exception) {
            Log.d(TAG, "overlay failed: ${e.message}")
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
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
    }
}
