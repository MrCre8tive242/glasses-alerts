package com.glassesalerts

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.KeyEvent
import com.glassesalerts.logic.ActionStateMachine
import com.glassesalerts.logic.AnnounceFilter
import com.glassesalerts.logic.ButtonMap
import com.glassesalerts.logic.Classifier
import com.glassesalerts.logic.DictationFailure
import com.glassesalerts.logic.Effect
import com.glassesalerts.logic.Kind
import com.glassesalerts.logic.Pending
import com.glassesalerts.logic.QuickReplies
import com.glassesalerts.logic.QuietHours
import com.glassesalerts.logic.ReplyMode
import com.glassesalerts.logic.Speech
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Reads WhatsApp Business notifications aloud and lets glasses taps (Bluetooth media buttons)
 * answer calls, send replies (spoken or quick) and play voice notes. All the "what does a tap
 * mean" logic is in the logic package; this class only talks to Android.
 */
class AlertListenerService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var sm: ActionStateMachine

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var session: MediaSession? = null
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null

    private val filter = AnnounceFilter()
    private var sessionWasActive = false
    private val messageSbns = LinkedHashMap<String, StatusBarNotification>()
    private var ringingKey: String? = null
    private var callActions: Pair<PendingIntent?, PendingIntent?>? = null
    private var buttonTestUntil = 0L

    // Voice notes are looked up the moment the notification arrives, so a tap can play at once.
    private var voiceToken = 0
    private var voiceFile: File? = null
    private var voiceSearching = false
    private var playWhenReady = false
    private var voiceSender: String? = null
    private var voiceKey: String? = null
    private var micOverlay: View? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        sm = ActionStateMachine(
            replies = { QuickReplies.parse(prefs.quickReplies) },
            clock = { System.currentTimeMillis() },
            replyMode = { if (prefs.replyDictate) ReplyMode.DICTATE else ReplyMode.QUICK },
        )
        tts = TextToSpeech(this, this)
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (e: Exception) {
            // no confirmation beep: fine
        }
        createSession()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            ttsReady = true
        }
    }

    // ------------------------------------------------------------------ notifications

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!prefs.enabled || !ttsReady) return

        val pkg = sbn.packageName
        val watched = pkg == BUSINESS_PKG || (prefs.includeRegularWhatsApp && pkg == REGULAR_PKG)
        if (!watched) return

        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        // Prefer the newest message in the chat bubble list; WhatsApp sometimes replaces the
        // plain text with "2 new messages", which would otherwise hide the second message.
        val text = (latestMessageText(ex)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString())?.trim().orEmpty()
        val template = ex.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val isCall = n.category == Notification.CATEGORY_CALL || template.endsWith("CallStyle")
        val (answer, decline) = if (isCall) callIntents(n) else Pair(null, null)
        val flags = n.flags

        val result = Classifier.classify(
            Classifier.Input(
                title = title,
                text = text,
                isCall = isCall,
                hasAnswerAction = answer != null,
                isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0,
                isOngoing = (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0,
                isGroupChat = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            ),
            prefs.includeGroups,
        )
        if (result.kind == Kind.IGNORE) return

        val now = System.currentTimeMillis()
        logd("posted ${result.kind}, notification is ${now - n.`when`} ms old")

        if (result.kind == Kind.INCOMING_CALL) {
            if (!prefs.enableCalls) return
            if (ringingKey == sbn.key) {
                // The ring notification was updated: pick up the Answer button if it arrived late.
                if (callActions?.first == null && answer != null) callActions = Pair(answer, decline)
                return
            }
        }
        if (inQuietHours()) return
        if (prefs.onlyWhenBluetooth && !bluetoothAudioConnected()) return

        if (result.kind != Kind.INCOMING_CALL) {
            // Skip WhatsApp re-posts, old notifications, and our own quick reply echoed back.
            if (!filter.shouldAnnounce(sbn.key, text, n.`when`, now)) return
        }

        when (result.kind) {
            Kind.INCOMING_CALL -> {
                ringingKey = sbn.key
                callActions = Pair(answer, decline)
                handle(sm.onIncomingCall(result.sender, result.detail))
                repeatRing(sbn.key, result.sender, result.detail, 0)
            }
            Kind.MESSAGE -> {
                val tapToPlay = prefs.tapToPlayMessages && prefs.readMessageBody
                speak(Speech.message(result.sender, result.detail, prefs.readMessageBody && !tapToPlay))
                messageSbns[sbn.key] = sbn
                while (messageSbns.size > 20) messageSbns.remove(messageSbns.keys.first())
                val canReply = prefs.enableReplies && findReplyActions(n).isNotEmpty()
                if (tapToPlay) handle(sm.onMessageToPlay(result.sender, sbn.key, result.detail, canReply))
                else handle(sm.onMessage(result.sender, sbn.key, canReply))
            }
            Kind.VOICE_NOTE -> {
                speak(Speech.voiceNote(result.sender))
                // Keep the notification so a reply can be offered after the voice note has played.
                messageSbns[sbn.key] = sbn
                while (messageSbns.size > 20) messageSbns.remove(messageSbns.keys.first())
                voiceSender = result.sender
                voiceKey = sbn.key
                if (prefs.enableVoiceNotes) {
                    prefetchVoiceNote(pkg, now)
                    handle(sm.onVoiceNote(result.sender))
                }
            }
            Kind.IGNORE -> Unit
        }
        refreshSession()
        main.postDelayed({ refreshSession() }, 46_000L)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        messageSbns.remove(sbn.key)
        filter.forget(sbn.key)
        if (sbn.key == ringingKey) {
            ringingKey = null
            callActions = null
            sm.onCallEnded()
            refreshSession()
        }
    }

    // ------------------------------------------------------------------ effects

    private fun handle(effects: List<Effect>) {
        for (e in effects) {
            when (e) {
                is Effect.Speak -> speak(e.text, e.flush)
                Effect.AnswerCall -> answerCall()
                Effect.DeclineCall -> fire(callActions?.second, "decline")
                is Effect.SendReply -> sendReply(e.key, e.text)
                is Effect.PlayVoiceNote -> playVoiceNote()
                Effect.StartDictation -> startDictation()
                Effect.CancelDictation -> cancelDictation()
            }
        }
    }

    /** Applies the voice, speed and pitch chosen in the app, so changes take effect at once. */
    private fun applyVoice() {
        val t = tts ?: return
        try {
            t.setSpeechRate(prefs.ttsRate)
            t.setPitch(prefs.ttsPitch)
            val name = prefs.ttsVoice
            if (name.isNotEmpty()) t.voices?.firstOrNull { it.name == name }?.let { t.voice = it }
        } catch (e: Exception) {
            logd("voice setup failed: ${e.message}")
        }
    }

    private fun speak(text: String, flush: Boolean = false) {
        applyVoice()
        tts?.speak(
            text,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            UUID.randomUUID().toString(),
        )
    }

    /** Short beep the instant a tap is accepted, before anything slower happens. */
    private fun ack() {
        try {
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (e: Exception) {
        }
    }

    private fun logd(msg: String) {
        Log.d("GlassesAlerts", msg)
    }

    private fun repeatRing(key: String, sender: String, detail: String, count: Int) {
        if (count >= 2) return
        main.postDelayed({
            if (ringingKey == key && sm.current is Pending.Call) {
                speak(Speech.call(sender, detail) + " " + Speech.CALL_HINT)
                repeatRing(key, sender, detail, count + 1)
            }
        }, 9_000L)
    }

    // ------------------------------------------------------------------ calls

    private fun callIntents(n: Notification): Pair<PendingIntent?, PendingIntent?> {
        val ex = n.extras
        // Android's CallStyle notifications carry these two extras (Android 12+).
        @Suppress("DEPRECATION")
        var answer: PendingIntent? = ex?.getParcelable<PendingIntent>("android.answerIntent")
        @Suppress("DEPRECATION")
        var decline: PendingIntent? = ex?.getParcelable<PendingIntent>("android.declineIntent")
        // Fallback: look for buttons titled Answer / Decline.
        n.actions?.forEach { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            if (answer == null && ANSWER_WORDS.any { t.contains(it) }) answer = a.actionIntent
            if (decline == null && DECLINE_WORDS.any { t.contains(it) }) decline = a.actionIntent
        }
        return Pair(answer, decline)
    }

    private fun answerCall() {
        val key = ringingKey
        val pi = callActions?.first
        if (pi == null) {
            speak("I cannot answer this one. Please answer on your phone.", true)
            return
        }
        fire(pi, "answer")
        // If Android blocked the answer, the ringing notification is still there.
        main.postDelayed({
            if (key != null && ringingKey == key) {
                speak("The call did not connect. Please answer on your phone.", true)
            }
        }, 5_000L)
    }

    private fun fire(pi: PendingIntent?, what: String) {
        if (pi == null) {
            speak("Could not $what the call.", true)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                pi.send(this, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
        } catch (e: PendingIntent.CanceledException) {
            speak("Could not $what the call.", true)
        }
    }

    // ------------------------------------------------------------------ replies

    private fun findReplyActions(n: Notification): List<Notification.Action> {
        val all = ArrayList<Notification.Action>()
        n.actions?.let { all.addAll(it) }
        try {
            all.addAll(Notification.WearableExtender(n).actions)
        } catch (e: Exception) {
            // no wearable extender: fine
        }
        return all.filter { a -> a.remoteInputs?.isNotEmpty() == true }
    }

    private fun sendReply(key: String, text: String) {
        val sbn = messageSbns[key]
        filter.noteSentReply(key, text, System.currentTimeMillis())
        val ok = sbn != null && replyViaRemoteInput(sbn.notification, text)
        speak(if (ok) "Reply sent." else "Could not send the reply.", true)
    }

    private fun replyViaRemoteInput(n: Notification, text: String): Boolean {
        val action = findReplyActions(n).firstOrNull() ?: return false
        val inputs = action.remoteInputs ?: return false
        val fill = Intent()
        val results = Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, fill, results)
        return try {
            action.actionIntent.send(this, 0, fill)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    // ------------------------------------------------------------------ spoken replies

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startDictation() {
        if (!hasMic()) {
            handle(sm.onDictationFailed(DictationFailure.UNAVAILABLE))
            return
        }
        launchDictation { text, unavailable -> onDictationOutcome(text, unavailable) }
    }

    /** Settings-screen test: listen once and say back what was heard. */
    fun startDictationTest() {
        if (!hasMic()) {
            speak("Please allow the microphone first. That is step 5.", true)
            return
        }
        tts?.stop()
        ack()
        launchDictation { text, unavailable ->
            speak(
                when {
                    !text.isNullOrBlank() -> "I heard: $text"
                    unavailable -> "Speaking is not available. Check the last spoken reply line in the app."
                    else -> "I did not hear anything."
                },
                true,
            )
        }
    }

    /**
     * Starts DictationService. Android decides whether a foreground service may use the
     * microphone at the moment it starts, and it only allows that for an app with a visible
     * window. So a 1-pixel invisible overlay ("Display over other apps") goes up first, and the
     * service is started once that window is showing.
     */
    private fun launchDictation(onDone: (String?, Boolean) -> Unit) {
        DictationService.listener = { text, unavailable ->
            // Short pause so the audio is back on the glasses speaker before anything is said.
            main.postDelayed({
                hideMicOverlay()
                onDone(text, unavailable)
            }, 300L)
        }
        showMicOverlay()
        main.postDelayed({
            if (DictationService.listener == null) {
                hideMicOverlay() // cancelled while waiting
            } else {
                try {
                    startForegroundService(Intent(this, DictationService::class.java))
                } catch (e: Exception) {
                    DictationLog.record("Could not start listening: ${e.javaClass.simpleName}")
                    val cb = DictationService.listener
                    DictationService.listener = null
                    cb?.invoke(null, true)
                }
            }
        }, if (micOverlay != null) 250L else 0L)
    }

    private fun cancelDictation() {
        DictationService.listener = null
        stopService(Intent(this, DictationService::class.java))
        hideMicOverlay()
    }

    private fun onDictationOutcome(text: String?, unavailable: Boolean) {
        val effects = when {
            unavailable -> sm.onDictationFailed(DictationFailure.UNAVAILABLE)
            text.isNullOrBlank() -> sm.onDictationFailed(DictationFailure.NOTHING_HEARD)
            else -> sm.onDictationResult(text)
        }
        handle(effects)
        refreshSession()
        // The microphone switched audio routes; make sure the next glasses tap still comes here.
        if (sm.hasPending()) claimMediaButtons()
    }

    private fun showMicOverlay() {
        if (micOverlay != null || !Settings.canDrawOverlays(this)) return
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
            micOverlay = v
        } catch (e: Exception) {
            logd("overlay failed: ${e.message}")
        }
    }

    private fun hideMicOverlay() {
        val v = micOverlay ?: return
        micOverlay = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ voice notes

    private fun prefetchVoiceNote(pkg: String, notifiedAt: Long) {
        val token = ++voiceToken
        voiceFile = null
        playWhenReady = false
        if (!Environment.isExternalStorageManager()) {
            voiceSearching = false
            return
        }
        voiceSearching = true
        Thread {
            val started = System.currentTimeMillis()
            val f = VoiceNoteFinder.find(pkg, notifiedAt)
            main.post {
                if (token != voiceToken) return@post
                voiceSearching = false
                voiceFile = f
                logd("voice note ${if (f != null) "found" else "NOT found"} after ${System.currentTimeMillis() - started} ms")
                if (playWhenReady) {
                    playWhenReady = false
                    if (f != null) play(f)
                    else speak("I could not find that voice message. Check that media auto download is on.", true)
                }
            }
        }.start()
    }

    private fun playVoiceNote() {
        if (!Environment.isExternalStorageManager()) {
            speak("Please allow all files access in the Glasses Alerts app first.", true)
            return
        }
        val f = voiceFile
        when {
            f != null && f.exists() -> play(f) // already found: starts right away
            voiceSearching -> {
                playWhenReady = true
                speak("One moment.", true)
            }
            else -> speak("I could not find that voice message. Check that media auto download is on.", true)
        }
    }

    private fun play(file: File) {
        player?.release()
        player = null
        tts?.stop()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                offerReplyAfterVoice()
            }
            mp.setOnErrorListener { m, _, _ ->
                m.release()
                if (player === m) player = null
                speak("Could not play that voice message.", true)
                true
            }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            speak("Could not play that voice message.", true)
        }
    }

    /** After a voice note has played, offer "Double tap to reply", like after a text message. */
    private fun offerReplyAfterVoice() {
        val key = voiceKey ?: return
        val sender = voiceSender ?: return
        voiceKey = null
        val sbn = messageSbns[key] ?: return
        val canReply = prefs.enableReplies && findReplyActions(sbn.notification).isNotEmpty()
        handle(sm.onMessage(sender, key, canReply))
        refreshSession()
        main.postDelayed({ refreshSession() }, 46_000L)
    }

    // ------------------------------------------------------------------ glasses buttons

    private fun createSession() {
        val s = MediaSession(this, "GlassesAlerts")
        s.setCallback(object : MediaSession.Callback() {
            @Suppress("DEPRECATION")
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (ev.action != KeyEvent.ACTION_DOWN) return true // swallow key-up while we are active
                if (ev.repeatCount > 0) return true
                ButtonLog.record(ev.keyCode)
                val testing = System.currentTimeMillis() < buttonTestUntil
                if (!sm.hasPending()) {
                    if (testing) speak("Button: " + ButtonLog.friendly(ev.keyCode), true)
                    refreshSession()
                    return testing
                }
                val button = ButtonMap.map(ev.keyCode) ?: return false
                logd("button ${ev.keyCode} -> $button")
                ack()          // instant feedback
                tts?.stop()    // stop whatever was being read so the answer to the tap is heard first
                handle(sm.onButton(button))
                refreshSession()
                return true
            }
        })
        session = s
    }

    /** Only claim the media buttons while an action is waiting (or during the button test). */
    private fun refreshSession() {
        val s = session ?: return
        val active = sm.hasPending() || System.currentTimeMillis() < buttonTestUntil
        if (active) {
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build()
            )
            s.isActive = true
            if (!sessionWasActive) claimMediaButtons()
        } else {
            s.isActive = false
        }
        sessionWasActive = active
    }

    /**
     * Android sends headset buttons to the app that most recently played audio itself.
     * Text-to-speech audio is played by the TTS engine's process, not ours, so we play a
     * fraction of a second of silence from this app to become the button target.
     */
    private fun claimMediaButtons() {
        try {
            val rate = 16_000
            val samples = ShortArray(rate / 4) // 250 ms of silence
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            main.postDelayed({ track.release() }, 1_000L)
        } catch (e: Exception) {
            // Not fatal: buttons may still reach us.
        }
    }

    /** Called from the settings screen: for 60 seconds, the glasses read out which button you press. */
    fun startButtonTest() {
        buttonTestUntil = System.currentTimeMillis() + 60_000L
        refreshSession()
        speak("Button test on. Tap or swipe your glasses now.", true)
        main.postDelayed({ refreshSession() }, 61_000L)
    }

    // ------------------------------------------------------------------ helpers

    /** Text of the last message in a MessagingStyle notification, or null if there is none. */
    private fun latestMessageText(ex: Bundle): String? {
        return try {
            @Suppress("DEPRECATION")
            val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
            val last = arr.lastOrNull() as? Bundle ?: return null
            last.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun inQuietHours(): Boolean {
        if (!prefs.quietHoursEnabled) return false
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return QuietHours.contains(hour, prefs.quietStartHour, prefs.quietEndHour)
    }

    private fun bluetoothAudioConnected(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    override fun onDestroy() {
        instance = null
        DictationService.listener = null
        hideMicOverlay()
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        try {
            tone?.release()
        } catch (e: Exception) {
        }
        tone = null
        session?.release()
        session = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        const val BUSINESS_PKG = "com.whatsapp.w4b"
        const val REGULAR_PKG = "com.whatsapp"

        @Volatile
        var instance: AlertListenerService? = null

        private val ANSWER_WORDS = listOf(
            "answer", "accept", "atender", "responder", "contestar", "aceptar",
            "répondre", "antworten", "annehmen",
        )
        private val DECLINE_WORDS = listOf(
            "decline", "reject", "rechazar", "recusar", "rejeitar", "refuser",
            "ablehnen", "declinar",
        )
    }
}
