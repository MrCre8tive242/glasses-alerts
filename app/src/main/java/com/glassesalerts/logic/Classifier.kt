package com.glassesalerts.logic

// Pure Kotlin only (no Android imports) so this can be tested on a normal JVM.

enum class Kind { MESSAGE, VOICE_NOTE, INCOMING_CALL, IGNORE }

/** detail: for calls "video" or "voice". Unused otherwise. */
data class Classified(val kind: Kind, val sender: String = "", val detail: String = "")

object Classifier {
    data class Input(
        val title: String,
        val text: String,
        val isCall: Boolean = false,
        val hasAnswerAction: Boolean = false,
        val isGroupSummary: Boolean = false,
        val isOngoing: Boolean = false,
        val isGroupChat: Boolean = false,
    )

    private val SUMMARY = Regex("^\\d+\\s+(new\\s+)?messages?", RegexOption.IGNORE_CASE)
    private val TITLE_COUNT = Regex("\\s*\\(\\d+\\s+(new\\s+)?messages?\\)\\s*$", RegexOption.IGNORE_CASE)
    private val APP_NAMES = setOf("whatsapp", "whatsapp business")
    private val VOICE_WORDS = listOf(
        "voice message", "voice note", "mensaje de voz", "mensagem de voz",
        "message vocal", "sprachnachricht",
    )

    fun cleanTitle(title: String): String = title.replace(TITLE_COUNT, "").trim()

    fun isVoiceNote(text: String): Boolean {
        // microphone emoji (U+1F3A4) or studio microphone (U+1F399)
        if (text.contains("🎤") || text.contains("🎙")) return true
        val t = text.trimStart { !it.isLetter() }.lowercase()
        return VOICE_WORDS.any { t.startsWith(it) }
    }

    fun classify(i: Input, includeGroups: Boolean): Classified {
        val title = cleanTitle(i.title)

        if (i.isCall) {
            // WhatsApp can post the ring notification before its Answer button is attached, so
            // "Incoming ..." text counts too. Ongoing, outgoing and ended calls have neither.
            val incoming = i.hasAnswerAction || i.text.contains("incoming", ignoreCase = true)
            if (!incoming) return Classified(Kind.IGNORE)
            val detail = if (i.text.contains("video", ignoreCase = true)) "video" else "voice"
            return Classified(Kind.INCOMING_CALL, title.ifEmpty { "an unknown caller" }, detail)
        }

        if (i.isGroupSummary || i.isOngoing) return Classified(Kind.IGNORE)
        val text = i.text.trim()
        if (title.isEmpty() || text.isEmpty()) return Classified(Kind.IGNORE)
        if (title.lowercase() in APP_NAMES) return Classified(Kind.IGNORE)
        if (SUMMARY.containsMatchIn(text)) return Classified(Kind.IGNORE)
        if (text.contains("checking for new messages", ignoreCase = true)) return Classified(Kind.IGNORE)
        if (i.isGroupChat && !includeGroups) return Classified(Kind.IGNORE)

        return if (isVoiceNote(text)) Classified(Kind.VOICE_NOTE, title)
        else Classified(Kind.MESSAGE, title, text)
    }
}

object Speech {
    const val CALL_HINT = "Tap to answer."
    const val REPLY_HINT = "Tap to reply."
    const val VOICE_HINT = "Tap to play."
    private const val MAX_BODY = 300

    fun message(sender: String, body: String, readBody: Boolean): String =
        if (readBody) "WhatsApp Business message from $sender. ${body.take(MAX_BODY)}"
        else "New WhatsApp Business message from $sender."

    fun voiceNote(sender: String): String = "WhatsApp Business voice message from $sender."

    fun call(sender: String, detail: String): String =
        "WhatsApp Business ${if (detail == "video") "video call" else "call"} from $sender."

    fun replyOption(index: Int, total: Int, text: String): String =
        "Reply ${index + 1} of $total: $text. Tap to send."

    fun confirm(text: String): String = "You said: ${text.take(MAX_BODY)}. Tap to send."

    const val NOT_HEARD = "I did not catch that. Tap to try again."
    const val SPEAK_UNAVAILABLE = "Speaking is not available."
}

object QuickReplies {
    const val MAX_REPLIES = 8
    const val MAX_LENGTH = 200

    val DEFAULT_RAW: String = listOf(
        "Yes, thank you.",
        "One moment please.",
        "I will call you back shortly.",
        "Please send me more details.",
    ).joinToString("\n")

    /** One reply per line. Blank lines dropped. An empty result turns the reply feature off. */
    fun parse(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { it.take(MAX_LENGTH) }.take(MAX_REPLIES)
}

object QuietHours {
    /** hour 0-23. start == end means quiet hours cover nothing. Handles overnight ranges like 22 to 7. */
    fun contains(hour: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> hour in start until end
        else -> hour >= start || hour < end
    }
}

object ButtonMap {
    // android.view.KeyEvent codes written as numbers to stay Android-free.
    // 79 HEADSETHOOK, 85 MEDIA_PLAY_PAUSE, 86 MEDIA_STOP, 87 MEDIA_NEXT,
    // 88 MEDIA_PREVIOUS, 126 MEDIA_PLAY, 127 MEDIA_PAUSE
    //
    // Measured on Ray-Ban Meta Gen 2: a single tap sends MEDIA_NEXT (87), so that is the
    // main action. The other gestures are provisional until they are measured.
    fun map(keyCode: Int): Button? = when (keyCode) {
        87 -> Button.PRIMARY
        88 -> Button.SECONDARY
        79, 85, 86, 126, 127 -> Button.CANCEL
        else -> null
    }
}

data class FileInfo(val path: String, val modified: Long, val size: Long)

object VoiceNotePicker {
    private val EXTENSIONS = setOf("opus", "ogg", "m4a", "aac", "mp3", "amr")

    /** Newest audio file saved at or after (notifiedAt - slackMs). */
    fun pick(files: List<FileInfo>, notifiedAt: Long, slackMs: Long = 20_000L): FileInfo? =
        files.filter {
            it.size > 0 &&
                it.path.substringAfterLast('.', "").lowercase() in EXTENSIONS &&
                it.modified >= notifiedAt - slackMs
        }.maxByOrNull { it.modified }
}
