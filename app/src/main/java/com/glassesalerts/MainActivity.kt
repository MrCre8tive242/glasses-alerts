package com.glassesalerts

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.glassesalerts.logic.QuickReplies
import java.util.Locale

/** One simple settings screen. Everything is built in code, so there are no layout files. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var filesText: TextView
    private lateinit var overlayText: TextView
    private lateinit var micText: TextView
    private lateinit var dictLogText: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            ui.postDelayed(this, 1000)
        }
    }
    private lateinit var lastButtonText: TextView
    private var speaker: Speaker? = null
    private lateinit var elevenText: TextView
    private lateinit var elevenSwitch: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        speaker = Speaker(this, prefs)
        setContentView(buildUi())
    }

    private fun sampleVoice() {
        if (speaker?.ready != true) return
        speaker?.speak("Message from Test Customer. Is my order ready?", true)
        // If ElevenLabs was used and failed, the phone voice spoke instead: say why on screen.
        if (prefs.elevenEnabled) ui.postDelayed({
            Speaker.lastError?.let { Toast.makeText(this, "$it. The phone voice was used.", Toast.LENGTH_LONG).show() }
        }, 6_000L)
    }

    private fun elevenStatus(): String = when {
        prefs.elevenApiKey.isBlank() -> "ElevenLabs: no API key saved yet."
        prefs.elevenVoiceId.isBlank() -> "ElevenLabs: key saved. Now choose a voice."
        else -> "ElevenLabs voice: ${prefs.elevenVoiceName}" + if (prefs.elevenEnabled) "" else " (switched off)"
    }

    private fun chooseElevenVoice() {
        val key = prefs.elevenApiKey
        if (key.isBlank()) {
            Toast.makeText(this, "Paste your ElevenLabs API key and tap Save key first.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Loading your ElevenLabs voices...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                Result.success(Speaker.elevenVoices(key))
            } catch (e: Exception) {
                Result.failure(e)
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                val voices = result.getOrNull()
                if (voices.isNullOrEmpty()) {
                    val why = (result.exceptionOrNull() as? IllegalStateException)?.message
                        ?: if (result.isSuccess) "No voices in this ElevenLabs account" else "No connection to ElevenLabs"
                    Toast.makeText(this, "$why.", Toast.LENGTH_LONG).show()
                    return@post
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("Choose an ElevenLabs voice (it plays a sample)")
                    .setItems(voices.map { it.second }.toTypedArray()) { _, which ->
                        prefs.elevenVoiceId = voices[which].first
                        prefs.elevenVoiceName = voices[which].second
                        prefs.elevenEnabled = true
                        elevenSwitch.isChecked = true
                        elevenText.text = elevenStatus()
                        sampleVoice()
                    }
                    .show()
            }
        }.start()
    }

    private fun chooseVoice() {
        val all = speaker?.phoneVoices().orEmpty()
        if (all.isEmpty()) {
            Toast.makeText(this, "Voices are not ready yet. Try again in a moment.", Toast.LENGTH_LONG).show()
            return
        }
        val lang = Locale.getDefault().language
        val voices = all.filter { it.locale.language == lang && !it.features.contains("notInstalled") &&
            !it.isNetworkConnectionRequired } // online-only voices go silent without signal
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name })
            .ifEmpty { all.sortedBy { it.name } }
        val names = arrayOf("Phone default") + voices.mapIndexed { i, v ->
            "Voice ${i + 1}: ${v.locale.displayName}" + (if (v.quality >= android.speech.tts.Voice.QUALITY_HIGH) " (higher quality)" else "")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Choose a voice (it plays a sample)")
            .setItems(names) { _, which ->
                prefs.ttsVoice = if (which == 0) "" else voices[which - 1].name
                sampleVoice()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick) // keeps the status lines and "last glasses button" up to date
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
    }

    override fun onDestroy() {
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }

    private fun updateStatus() {
        statusText.text = if (isListenerEnabled()) {
            "Notification access is ON."
        } else {
            "Notification access is OFF. Tap step 1 and allow \"Glasses Alerts\"."
        }
        filesText.text = if (Environment.isExternalStorageManager()) {
            "All files access is ON (needed for voice notes)."
        } else {
            "All files access is OFF. Voice notes will not play until you allow it (step 3)."
        }
        overlayText.text = if (Settings.canDrawOverlays(this)) {
            "Display over other apps is ON (needed to answer calls and for spoken replies)."
        } else {
            "Display over other apps is OFF. Answering calls and spoken replies may not work (step 4)."
        }
        micText.text = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone access is ON (needed for spoken replies)."
        } else {
            "Microphone access is OFF. Spoken replies will not work until you allow it (step 5)."
        }
        dictLogText.text = "Last spoken reply: " + DictationLog.last
        lastButtonText.text = "Last glasses button: " + ButtonLog.last
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, AlertListenerService::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(text: String, bold: Boolean = false, size: Float = 16f) = TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 2)
        }

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

        fun check(text: String, get: () -> Boolean, set: (Boolean) -> Unit) =
            CheckBox(this).apply {
                this.text = text
                isChecked = get()
                setOnCheckedChangeListener { _, v -> set(v) }
            }

        col.addView(label("Glasses Alerts", bold = true, size = 24f))
        col.addView(label("WhatsApp Business alerts through your Meta Ray-Ban glasses: hear messages, answer calls, reply by voice and play voice notes."))

        statusText = label("")
        filesText = label("")
        overlayText = label("")
        micText = label("")
        col.addView(statusText)
        col.addView(filesText)
        col.addView(overlayText)
        col.addView(micText)

        col.addView(button("1. Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(button("2. Battery: set Glasses Alerts to Unrestricted") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        col.addView(button("3. Allow all files access (for voice notes)") {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        })
        col.addView(button("4. Allow display over other apps (calls and spoken replies)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("5. Allow microphone (for spoken replies)") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })
        col.addView(button("6. Test the voice (connect your glasses first)") {
            sampleVoice()
        })

        col.addView(button("7. Test spoken reply (speak after the second beep)") {
            val svc = AlertListenerService.instance
            if (svc == null) {
                Toast.makeText(this, "Turn on notification access first (step 1).", Toast.LENGTH_LONG).show()
            } else {
                svc.startDictationTest()
            }
        })

        col.addView(label("Voice", bold = true, size = 20f))
        col.addView(label("If the voice sounds robotic, pick a higher quality one, or install better voices in Android's text-to-speech settings (Google or Samsung engines have natural voices)."))
        col.addView(button("Choose voice") { chooseVoice() })
        fun slider(title: String, min: Float, max: Float, get: () -> Float, set: (Float) -> Unit): View {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(label(title))
            box.addView(android.widget.SeekBar(this).apply {
                this.max = 100
                progress = (((get() - min) / (max - min)) * 100).toInt()
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) set(min + (max - min) * p / 100f)
                    }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar?) { sampleVoice() }
                })
            })
            return box
        }
        col.addView(slider("Speed (left slower, right faster)", 0.5f, 2.0f, { prefs.ttsRate }, { prefs.ttsRate = it }))
        col.addView(slider("Pitch (left deeper, right higher)", 0.5f, 1.5f, { prefs.ttsPitch }, { prefs.ttsPitch = it }))
        col.addView(button("Open Android voice settings (install better voices)") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        })

        col.addView(label("ElevenLabs voice (optional)", bold = true, size = 20f))
        col.addView(label("A natural, human-sounding voice from your ElevenLabs account. It needs internet, uses your ElevenLabs credits, and sends the text of what is spoken to ElevenLabs. If it cannot be reached, the phone voice is used instead."))
        val keyBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Paste your ElevenLabs API key"
            setText(prefs.elevenApiKey)
        }
        col.addView(keyBox)
        elevenText = label(elevenStatus())
        elevenSwitch = check("Use the ElevenLabs voice", { prefs.elevenEnabled }, {
            prefs.elevenEnabled = it
            elevenText.text = elevenStatus()
        })
        col.addView(button("Save key") {
            prefs.elevenApiKey = keyBox.text.toString()
            elevenText.text = elevenStatus()
            Toast.makeText(this, if (prefs.elevenApiKey.isBlank()) "Key removed" else "Key saved", Toast.LENGTH_SHORT).show()
        })
        col.addView(button("Choose ElevenLabs voice") { chooseElevenVoice() })
        col.addView(elevenSwitch)
        col.addView(elevenText)

        col.addView(label("Glasses buttons", bold = true, size = 20f))
        col.addView(label("Press the button below, then tap your glasses once, twice and three times. They will say which button each one sent."))
        col.addView(button("8. Test glasses buttons (60 seconds)") {
            val svc = AlertListenerService.instance
            if (svc == null) {
                Toast.makeText(this, "Turn on notification access first (step 1).", Toast.LENGTH_LONG).show()
            } else {
                svc.startButtonTest()
            }
        })
        lastButtonText = label("")
        col.addView(lastButtonText)
        dictLogText = label("")
        col.addView(dictLogText)
        col.addView(label("How taps work on Ray-Ban Meta: double tap = answer / reply / send / play. Triple tap = decline a call, cancel, or hear the next quick reply. A single tap is kept by the glasses and never reaches this app."))

        col.addView(label("Features", bold = true, size = 20f))
        col.addView(Switch(this).apply {
            text = "Announce messages"
            isChecked = prefs.enabled
            setPadding(0, pad / 2, 0, pad / 2)
            setOnCheckedChangeListener { _, v -> prefs.enabled = v }
        })
        col.addView(check("Announce calls (double tap to answer, triple tap to decline)", { prefs.enableCalls }, { prefs.enableCalls = it }))
        col.addView(check("Reply by speaking (off = pick a saved quick reply)", { prefs.replyDictate }, { prefs.replyDictate = it }))
        col.addView(check("Offer replies after a message", { prefs.enableReplies }, { prefs.enableReplies = it }))
        col.addView(check("Voice notes: offer to play them", { prefs.enableVoiceNotes }, { prefs.enableVoiceNotes = it }))

        col.addView(label("Quick replies (one per line, up to ${QuickReplies.MAX_REPLIES})", bold = true))
        val repliesBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP
            setText(prefs.quickReplies)
        }
        col.addView(repliesBox)
        col.addView(button("Save quick replies") {
            prefs.quickReplies = repliesBox.text.toString()
            val n = QuickReplies.parse(prefs.quickReplies).size
            Toast.makeText(this, "Saved $n quick replies", Toast.LENGTH_SHORT).show()
        })

        col.addView(label("Other settings", bold = true, size = 20f))
        col.addView(check("Messages: say who it is from, double tap to hear the text (off = read it straight away)", { prefs.tapToPlayMessages }, { prefs.tapToPlayMessages = it }))
        col.addView(check("Read the message text (off = only say who it is from)", { prefs.readMessageBody }, { prefs.readMessageBody = it }))
        col.addView(check("Only speak when Bluetooth audio (glasses) is connected", { prefs.onlyWhenBluetooth }, { prefs.onlyWhenBluetooth = it }))
        col.addView(check("Include group chats", { prefs.includeGroups }, { prefs.includeGroups = it }))
        col.addView(check("Also read regular WhatsApp", { prefs.includeRegularWhatsApp }, { prefs.includeRegularWhatsApp = it }))
        col.addView(check("Quiet hours (stay silent)", { prefs.quietHoursEnabled }, { prefs.quietHoursEnabled = it }))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun hourField(initial: Int, onChange: (Int) -> Unit) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial.toString())
            layoutParams = LinearLayout.LayoutParams(pad * 4, ViewGroup.LayoutParams.WRAP_CONTENT)
            // Save as you type, so the value sticks even if you leave the screen straight away.
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    s?.toString()?.toIntOrNull()?.let { if (it in 0..23) onChange(it) }
                }
            })
        }
        row.addView(label("From hour (0-23):  "))
        row.addView(hourField(prefs.quietStartHour) { prefs.quietStartHour = it })
        row.addView(label("   to  "))
        row.addView(hourField(prefs.quietEndHour) { prefs.quietEndHour = it })
        col.addView(row)

        // Android 15 draws apps edge to edge; keep content clear of the status and navigation bars.
        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(col)
        }
    }
}
