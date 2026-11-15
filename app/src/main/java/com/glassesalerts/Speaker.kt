package com.glassesalerts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Everything the app says goes through here.
 *
 * Normally it uses the phone's own text-to-speech voice. When ElevenLabs is switched on (and an
 * API key and voice are saved), the text is sent to ElevenLabs and the returned audio is played
 * instead. If that fails for any reason (no signal, bad key, out of credits), the phone voice
 * says it, so an alert is never lost.
 *
 * Short phrases ("Double tap to play.") are kept on the phone after the first time, so they
 * play instantly and are not billed again.
 */
class Speaker(context: Context, private val prefs: Prefs) {

    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private var tts: TextToSpeech? = null
    @Volatile var ready = false
        private set

    // Bumped by every flush/stop; queued work from an older generation is dropped.
    @Volatile private var generation = 0
    private var player: MediaPlayer? = null
    private var waiting: CountDownLatch? = null
    private var waitingId: String? = null

    private var appliedVoice: String? = null
    private var appliedRate = -1f
    private var appliedPitch = -1f

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setAudioAttributes(attrs)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = release(id)
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = release(id)
                    override fun onError(id: String?, code: Int) = release(id)
                    override fun onStop(id: String?, interrupted: Boolean) = release(id)
                })
                ready = true
            }
        }
        worker.execute { trimCache() }
    }

    /** Voices installed on the phone (for the picker). */
    fun phoneVoices(): List<Voice> = try {
        tts?.voices?.toList().orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private fun useEleven(): Boolean =
        prefs.elevenEnabled && prefs.elevenApiKey.isNotBlank() && prefs.elevenVoiceId.isNotBlank()

    fun speak(text: String, flush: Boolean = false) {
        if (text.isBlank()) return
        if (flush) stop()
        if (!useEleven()) {
            speakOnPhone(text, UUID.randomUUID().toString())
            return
        }
        val gen = generation
        worker.execute {
            if (gen != generation) return@execute
            val audio = elevenAudio(text)
            if (gen != generation) return@execute
            if (audio == null || !playFile(audio, gen)) {
                // ElevenLabs did not work this time: say it with the phone voice and wait for it.
                val id = UUID.randomUUID().toString()
                val latch = CountDownLatch(1)
                synchronized(lock) {
                    if (gen != generation) return@execute
                    waiting = latch
                    waitingId = id
                }
                speakOnPhone(text, id)
                latch.await(5_000L + text.length * 150L, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Stops whatever is being said and drops anything still waiting to be said. */
    fun stop() {
        synchronized(lock) {
            generation++
            try {
                player?.stop()
            } catch (e: Exception) {
            }
            waiting?.countDown()
            waiting = null
            waitingId = null
        }
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
    }

    fun shutdown() {
        stop()
        worker.shutdownNow()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        ready = false
    }

    // ------------------------------------------------------------------ phone voice

    private fun release(id: String?) {
        synchronized(lock) {
            if (id != null && id == waitingId) {
                waiting?.countDown()
                waiting = null
                waitingId = null
            }
        }
    }

    private fun speakOnPhone(text: String, id: String) {
        applyPhoneVoice()
        val r = tts?.speak(text, TextToSpeech.QUEUE_ADD, null as Bundle?, id)
        if (r != TextToSpeech.SUCCESS) release(id)
    }

    /** Applies the voice, speed and pitch chosen in the app, so changes take effect at once. */
    private fun applyPhoneVoice() {
        val t = tts ?: return
        val rate = prefs.ttsRate
        val pitch = prefs.ttsPitch
        val name = prefs.ttsVoice
        // Asking the speech engine for its voice list is slow, so only do it after a change.
        if (rate == appliedRate && pitch == appliedPitch && name == appliedVoice) return
        try {
            t.setSpeechRate(rate)
            t.setPitch(pitch)
            if (name != appliedVoice) {
                val v = if (name.isEmpty()) t.defaultVoice else t.voices?.firstOrNull { it.name == name }
                if (v != null) t.voice = v
            }
            appliedRate = rate
            appliedPitch = pitch
            appliedVoice = name
        } catch (e: Exception) {
            Log.d(TAG, "voice setup failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ ElevenLabs

    private fun cacheDir(): File = File(app.cacheDir, "eleven").apply { mkdirs() }

    private fun trimCache() {
        try {
            val files = cacheDir().listFiles()?.sortedBy { it.lastModified() } ?: return
            files.take((files.size - MAX_CACHED).coerceAtLeast(0)).forEach { it.delete() }
        } catch (e: Exception) {
        }
    }

    /** The spoken audio for this text as a file, or null if ElevenLabs could not provide it. */
    private fun elevenAudio(text: String): File? {
        val voice = prefs.elevenVoiceId
        val speed = prefs.ttsRate.coerceIn(0.7f, 1.2f) // the range ElevenLabs accepts
        val keep = text.length <= CACHE_MAX_CHARS
        val name = sha1("$voice|$MODEL|$speed|$text") + ".mp3"
        val file = File(cacheDir(), if (keep) name else "last-long.mp3")
        if (keep && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject()
                .put("text", text)
                .put("model_id", MODEL)
                .apply { if (speed != 1.0f) put("voice_settings", JSONObject().put("speed", speed.toDouble())) }
                .toString().toByteArray(Charsets.UTF_8)
            conn = URL("$API/text-to-speech/$voice?output_format=mp3_44100_128").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 4_000
            conn.readTimeout = 12_000
            conn.doOutput = true
            conn.setRequestProperty("xi-api-key", prefs.elevenApiKey.trim())
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "audio/mpeg")
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) {
                lastError = describe(conn.responseCode)
                Log.d(TAG, "ElevenLabs answered ${conn.responseCode}")
                return null
            }
            val tmp = File(cacheDir(), "$name.part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() == 0L) return null
            file.delete()
            if (!tmp.renameTo(file)) return null
            lastError = null
            file
        } catch (e: Exception) {
            lastError = "No connection to ElevenLabs"
            Log.d(TAG, "ElevenLabs failed: ${e.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Plays the file and waits until it has finished (or was stopped). False if it could not play. */
    private fun playFile(file: File, gen: Int): Boolean {
        val latch = CountDownLatch(1)
        var failed = false
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { latch.countDown() }
            mp.setOnErrorListener { _, _, _ ->
                failed = true
                latch.countDown()
                true
            }
            mp.prepare()
            synchronized(lock) {
                if (gen != generation) return true // stopped while loading: nothing more to say
                player = mp
                waiting = latch
                waitingId = null
                mp.start()
            }
            latch.await(mp.duration + 5_000L, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            failed = true
        } finally {
            synchronized(lock) {
                if (player === mp) player = null
                if (waiting === latch) waiting = null
            }
            try {
                mp.release()
            } catch (e: Exception) {
            }
        }
        if (failed) file.delete()
        return !failed
    }

    companion object {
        private const val TAG = "GlassesAlerts"
        private const val API = "https://api.elevenlabs.io/v1"
        // The fast, low-cost ElevenLabs model: short delay before speech starts.
        private const val MODEL = "eleven_flash_v2_5"
        private const val CACHE_MAX_CHARS = 80
        private const val MAX_CACHED = 300

        /** Why ElevenLabs last failed, in plain words, or null if the last request worked. */
        @Volatile
        var lastError: String? = null

        private fun describe(code: Int): String = when (code) {
            401 -> "ElevenLabs did not accept the API key, or the account is out of credits"
            402 -> "ElevenLabs: this voice needs a paid plan"
            404 -> "ElevenLabs could not find that voice"
            429 -> "ElevenLabs: out of credits or too many requests"
            else -> "ElevenLabs error $code"
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * The voices in the ElevenLabs account as (voice id, name). Call off the main thread.
         * Throws with a plain-words message if the list cannot be loaded.
         */
        fun elevenVoices(apiKey: String): List<Pair<String, String>> {
            val conn = URL("$API/voices").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 6_000
                conn.readTimeout = 12_000
                conn.setRequestProperty("xi-api-key", apiKey.trim())
                if (conn.responseCode != 200) throw IllegalStateException(describe(conn.responseCode))
                val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("voices")
                return (0 until arr.length()).map {
                    val v = arr.getJSONObject(it)
                    v.getString("voice_id") to v.optString("name", "Voice ${it + 1}")
                }.sortedBy { it.second.lowercase() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
