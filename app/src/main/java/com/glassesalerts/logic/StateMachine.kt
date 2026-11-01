package com.glassesalerts.logic

// Pure Kotlin only (no Android imports).

enum class Button { PRIMARY, SECONDARY, CANCEL }

sealed class Effect {
    data class Speak(val text: String, val flush: Boolean = false) : Effect()
    object AnswerCall : Effect()
    object DeclineCall : Effect()
    data class SendReply(val key: String, val text: String) : Effect()
    data class PlayVoiceNote(val sender: String) : Effect()
}

sealed class Pending {
    data class Call(val sender: String) : Pending()
    data class Message(val sender: String, val key: String) : Pending()
    data class Select(val sender: String, val key: String, val index: Int) : Pending()
    data class Voice(val sender: String) : Pending()
}

/**
 * Decides what a glasses tap means right now. It only returns Effects; the Android service
 * carries them out. Pending actions expire after ttlMs.
 */
class ActionStateMachine(
    private val replies: () -> List<String>,
    private val clock: () -> Long,
    private val ttlMs: Long = 45_000L,
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

    fun onIncomingCall(sender: String, detail: String): List<Effect> {
        set(Pending.Call(sender))
        return listOf(
            Effect.Speak(Speech.call(sender, detail), flush = true),
            Effect.Speak(Speech.CALL_HINT),
        )
    }

    /** Call this after the message itself has been spoken. */
    fun onMessage(sender: String, key: String, canReply: Boolean): List<Effect> {
        val cur = current
        if (cur is Pending.Call || cur is Pending.Select) return emptyList()
        if (!canReply || replies().isEmpty()) {
            set(null)
            return emptyList()
        }
        set(Pending.Message(sender, key))
        return listOf(Effect.Speak(Speech.REPLY_HINT))
    }

    fun onVoiceNote(sender: String): List<Effect> {
        val cur = current
        if (cur is Pending.Call || cur is Pending.Select) return emptyList()
        set(Pending.Voice(sender))
        return listOf(Effect.Speak(Speech.VOICE_HINT))
    }

    fun onCallEnded() {
        if (current is Pending.Call) set(null)
    }

    fun clear() = set(null)

    fun onButton(b: Button): List<Effect> {
        val cur = current ?: return emptyList()
        return when (cur) {
            is Pending.Call -> when (b) {
                Button.PRIMARY -> { set(null); listOf(Effect.AnswerCall) }
                Button.SECONDARY -> { set(null); listOf(Effect.DeclineCall) }
                Button.CANCEL -> { set(null); emptyList() }
            }
            is Pending.Message -> when (b) {
                Button.PRIMARY -> select(cur.sender, cur.key, 0)
                else -> { set(null); emptyList() }
            }
            is Pending.Select -> when (b) {
                Button.PRIMARY -> {
                    val list = replies()
                    set(null)
                    if (list.isEmpty()) emptyList()
                    else listOf(Effect.SendReply(cur.key, list[cur.index.coerceIn(0, list.size - 1)]))
                }
                Button.SECONDARY -> select(cur.sender, cur.key, cur.index + 1)
                Button.CANCEL -> { set(null); listOf(Effect.Speak("Cancelled.")) }
            }
            is Pending.Voice -> {
                set(null)
                if (b == Button.PRIMARY) listOf(Effect.PlayVoiceNote(cur.sender)) else emptyList()
            }
        }
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
