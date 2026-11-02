package com.glassesalerts.logic

// Pure Kotlin only (no Android imports).

enum class Button { PRIMARY, SECONDARY, CANCEL }

/** What a tap does after a message: speak a reply, or pick one of the saved quick replies. */
enum class ReplyMode { DICTATE, QUICK }

enum class DictationFailure { NOTHING_HEARD, UNAVAILABLE }

sealed class Effect {
    data class Speak(val text: String, val flush: Boolean = false) : Effect()
    object AnswerCall : Effect()
    object DeclineCall : Effect()
    data class SendReply(val key: String, val text: String) : Effect()
    data class PlayVoiceNote(val sender: String) : Effect()
    object StartDictation : Effect()
    object CancelDictation : Effect()
}

sealed class Pending {
    data class Call(val sender: String) : Pending()
    data class Message(val sender: String, val key: String) : Pending()
    data class Select(val sender: String, val key: String, val index: Int) : Pending()
    data class Voice(val sender: String) : Pending()
    data class Dictating(val sender: String, val key: String) : Pending()
    data class Confirm(val sender: String, val key: String, val text: String) : Pending()
}

/**
 * Decides what a glasses tap means right now. It only returns Effects; the Android service
 * carries them out. Pending actions expire after ttlMs.
 */
class ActionStateMachine(
    private val replies: () -> List<String>,
    private val clock: () -> Long,
    private val ttlMs: Long = 45_000L,
    private val replyMode: () -> ReplyMode = { ReplyMode.QUICK },
) {
    private var pending: Pending? = null
    private var since = 0L

    val current: Pending?
        get() {
            if (pending != null && clock() - since > ttlMs) pending = null
            return pending
        }

    fun hasPending(): Boolean = current != null

    private fun set(p: Pending?) {
        pending = p
        since = clock()
    }

    /** Something the user is in the middle of; a new notification must not take it over. */
    private fun busy(p: Pending?): Boolean =
        p is Pending.Call || p is Pending.Select || p is Pending.Dictating || p is Pending.Confirm

    private fun canOffer(): Boolean = replyMode() == ReplyMode.DICTATE || replies().isNotEmpty()

    fun onIncomingCall(sender: String, detail: String): List<Effect> {
        set(Pending.Call(sender))
        // One short sentence, so "tap to answer" is heard as early as possible.
        return listOf(Effect.Speak(Speech.call(sender, detail) + " " + Speech.CALL_HINT, flush = true))
    }

    /** Call this after the message itself has been spoken. */
    fun onMessage(sender: String, key: String, canReply: Boolean): List<Effect> {
        if (busy(current)) return emptyList()
        if (!canReply || !canOffer()) {
            set(null)
            return emptyList()
        }
        set(Pending.Message(sender, key))
        return listOf(Effect.Speak(Speech.REPLY_HINT))
    }

    fun onVoiceNote(sender: String): List<Effect> {
        if (busy(current)) return emptyList()
        set(Pending.Voice(sender))
        return listOf(Effect.Speak(Speech.VOICE_HINT))
    }

    fun onCallEnded() {
        if (current is Pending.Call) set(null)
    }

    fun clear() = set(null)

    /** The speech recognizer produced text. */
    fun onDictationResult(text: String): List<Effect> {
        val cur = current as? Pending.Dictating ?: return emptyList()
        val t = text.trim()
        if (t.isEmpty()) return onDictationFailed(DictationFailure.NOTHING_HEARD)
        set(Pending.Confirm(cur.sender, cur.key, t))
        return listOf(Effect.Speak(Speech.confirm(t), flush = true))
    }

    fun onDictationFailed(reason: DictationFailure): List<Effect> {
        val cur = current as? Pending.Dictating ?: return emptyList()
        if (reason == DictationFailure.NOTHING_HEARD) {
            set(Pending.Message(cur.sender, cur.key))
            return listOf(Effect.Speak(Speech.NOT_HEARD, flush = true))
        }
        // Speaking is not possible right now (no permission, no recognizer, no network).
        val list = replies()
        if (list.isEmpty()) {
            set(null)
            return listOf(Effect.Speak(Speech.SPEAK_UNAVAILABLE + " Please reply on your phone.", flush = true))
        }
        set(Pending.Select(cur.sender, cur.key, 0))
        return listOf(
            Effect.Speak(Speech.SPEAK_UNAVAILABLE + " " + Speech.replyOption(0, list.size, list[0]), flush = true)
        )
    }

    fun onButton(b: Button): List<Effect> {
        val cur = current ?: return emptyList()
        return when (cur) {
            is Pending.Call -> when (b) {
                Button.PRIMARY -> { set(null); listOf(Effect.AnswerCall) }
                Button.SECONDARY, Button.CANCEL -> { set(null); listOf(Effect.DeclineCall) }
            }
            is Pending.Message -> when (b) {
                Button.PRIMARY -> start(cur.sender, cur.key, replyMode())
                Button.SECONDARY -> start(cur.sender, cur.key, other(replyMode()))
                Button.CANCEL -> { set(null); emptyList() }
            }
            is Pending.Select -> when (b) {
                Button.PRIMARY -> {
                    val list = replies()
                    set(null)
                    if (list.isEmpty()) emptyList()
                    else listOf(Effect.SendReply(cur.key, list[cur.index.coerceIn(0, list.size - 1)]))
                }
                Button.SECONDARY -> select(cur.sender, cur.key, cur.index + 1)
                Button.CANCEL -> { set(null); listOf(Effect.Speak("Cancelled.", flush = true)) }
            }
            is Pending.Dictating -> when (b) {
                Button.CANCEL -> { set(null); listOf(Effect.CancelDictation) }
                else -> emptyList() // still listening; ignore stray taps
            }
            is Pending.Confirm -> when (b) {
                Button.PRIMARY -> { set(null); listOf(Effect.SendReply(cur.key, cur.text)) }
                Button.SECONDARY -> start(cur.sender, cur.key, ReplyMode.DICTATE) // say it again
                Button.CANCEL -> {
                    // Back to "Tap to reply" so a mistake can be redone without waiting.
                    set(Pending.Message(cur.sender, cur.key))
                    listOf(Effect.Speak(Speech.CANCELLED_AGAIN, flush = true))
                }
            }
            is Pending.Voice -> {
                set(null)
                if (b == Button.PRIMARY) listOf(Effect.PlayVoiceNote(cur.sender)) else emptyList()
            }
        }
    }

    private fun other(m: ReplyMode): ReplyMode =
        if (m == ReplyMode.DICTATE) ReplyMode.QUICK else ReplyMode.DICTATE

    private fun start(sender: String, key: String, mode: ReplyMode): List<Effect> =
        when (mode) {
            ReplyMode.DICTATE -> {
                set(Pending.Dictating(sender, key))
                listOf(Effect.StartDictation)
            }
            ReplyMode.QUICK -> select(sender, key, 0)
        }

    private fun select(sender: String, key: String, wanted: Int): List<Effect> {
        val list = replies()
        if (list.isEmpty()) {
            set(null)
            return emptyList()
        }
        val idx = ((wanted % list.size) + list.size) % list.size
        set(Pending.Select(sender, key, idx))
        return listOf(Effect.Speak(Speech.replyOption(idx, list.size, list[idx]), flush = true))
    }
}
