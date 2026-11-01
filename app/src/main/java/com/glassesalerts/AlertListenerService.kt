package com.glassesalerts

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import com.glassesalerts.logic.ActionStateMachine
import com.glassesalerts.logic.AnnounceFilter
import com.glassesalerts.logic.ButtonMap
import com.glassesalerts.logic.Classifier
import com.glassesalerts.logic.Effect
import com.glassesalerts.logic.Kind
import com.glassesalerts.logic.Pending
import com.glassesalerts.logic.QuickReplies
import com.glassesalerts.logic.QuietHours
import com.glassesalerts.logic.Speech
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Reads WhatsApp Business notifications aloud and lets glasses taps (Bluetooth media buttons)
 * answer calls, send quick replies and play voice notes. All the "what does a tap mean" logic is
 * in the logic package; this class only talks to Android.
 */
class AlertListenerService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var sm: ActionStateMachine

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var session: MediaSession? = null
    private var player: MediaPlayer? = null

    private val filter = AnnounceFilter()
    private var sessionWasActive = false
    private val messageSbns = LinkedHashMap<String, StatusBarNotification>()
    private var ringingKey: String? = null
    private var callActions: Pair<PendingIntent?, PendingIntent?>? = null
    private var voiceInfo: Pair<String, Long>? = null // package name, time the notification arrived
    private var buttonTestUntil = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        sm = ActionStateMachine(
            replies = { QuickReplies.parse(prefs.quickReplies) },
            clock = { System.currentTimeMillis() },
        )
        tts = TextToSpeech(this, this)
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
        if (result.kind == Kind.INCOMING_CALL && !prefs.enableCalls) return
        if (inQuietHours()) return
        if (prefs.onlyWhenBluetooth && !bluetoothAudioConnected()) return

        val now = System.currentTimeMillis()
        if (result.kind == Kind.INCOMING_CALL) {
            if (ringingKey == sbn.key) return // ring notification was just updated; already announced
        } else {
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
                speak(Speech.message(result.sender, result.detail, prefs.readMessageBody))
                messageSbns[sbn.key] = sbn
                while (messageSbns.size > 20) messageSbns.remove(messageSbns.keys.first())
                val canReply = prefs.enableReplies && findReplyActions(n).isNotEmpty()
                handle(sm.onMessage(result.sender, sbn.key, canReply))
            }
            Kind.VOICE_NOTE -> {
                speak(Speech.voiceNote(result.sender))
                if (prefs.enableVoiceNotes) {
                    voiceInfo = Pair(pkg, now)
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
                Effect.AnswerCall -> {
                    val key = ringingKey
                    fire(callActions?.first, "answer")
                    // If Android blocked the answer, the ringing notification is still there.
                    main.postDelayed({
                        if (key != null && ringingKey == key) {
                            speak("The call did not connect. Please answer on your phone.", true)
                        }
                    }, 5_000L)
                }
                Effect.DeclineCall -> fire(callActions?.second, "decline")
                is Effect.SendReply -> sendReply(e.key, e.text)
                is Effect.PlayVoiceNote -> playVoiceNote()
            }
        }
    }

    private fun speak(text: String, flush: Boolean = false) {
        tts?.speak(
            text,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            UUID.randomUUID().toString(),
        )
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

    private fun fire(pi: PendingIntent?, what: String) {
        if (pi == null) {
            speak("Could not $what the call.")
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
            speak("Could not $what the call.")
        }
    }

    // ------------------------------------------------------------------ quick replies

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
        speak(if (ok) "Reply sent." else "Could not send the reply.")
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

    // ------------------------------------------------------------------ voice notes

    private fun playVoiceNote() {
        val info = voiceInfo
        if (info == null) {
            speak("No voice message found.")
            return
        }
        if (!Environment.isExternalStorageManager()) {
            speak("Please allow all files access in the Glasses Alerts app first.")
            return
        }
        Thread {
            val file = VoiceNoteFinder.find(info.first, info.second)
            main.post {
                if (file == null) {
                    speak("I could not find that voice message. Check that media auto download is on.")
                } else {
                    play(file)
                }
            }
        }.start()
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
            }
            mp.setOnErrorListener { m, _, _ ->
                m.release()
                if (player === m) player = null
                speak("Could not play that voice message.")
                true
            }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            speak("Could not play that voice message.")
        }
    }

    // ------------------------------------------------------------------ glasses buttons

    private fun createSession() {
        val s = MediaSession(this, "GlassesAlerts")
        s.setCallback(object : MediaSession.Callback() {
            @Suppress("DEPRECATION")
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (ev.action != KeyEvent.ACTION_DOWN) return true // swallow key-up while we are active
                ButtonLog.record(ev.keyCode)
                val testing = System.currentTimeMillis() < buttonTestUntil
                if (!sm.hasPending()) {
                    if (testing) speak("Button: " + ButtonLog.friendly(ev.keyCode), true)
                    refreshSession()
                    return testing
                }
                val button = ButtonMap.map(ev.keyCode) ?: return false
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
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
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
