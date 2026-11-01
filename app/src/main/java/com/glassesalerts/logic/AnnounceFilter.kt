package com.glassesalerts.logic

// Pure Kotlin only (no Android imports).

/**
 * Decides whether a message notification is new enough to read aloud.
 * WhatsApp re-posts the same notification often (when other chats update, when the listener
 * reconnects, after you reply). Without this, old messages get read again and your own quick
 * reply gets read back to you.
 */
class AnnounceFilter(
    private val maxAgeMs: Long = 120_000L,
    private val selfReplyWindowMs: Long = 15_000L,
    private val maxKeys: Int = 50,
) {
    private val lastText = LinkedHashMap<String, String>()
    private var sentKey: String? = null
    private var sentText: String? = null
    private var sentAt = 0L

    /** Call right after sending a quick reply. */
    fun noteSentReply(key: String, text: String, now: Long) {
        sentKey = key
        sentText = text
        sentAt = now
    }

    /** Call when the notification is dismissed (chat opened), so a repeat text later still counts. */
    fun forget(key: String) {
        lastText.remove(key)
    }

    /** postedAt: the notification's message time (0 if unknown). */
    fun shouldAnnounce(key: String, text: String, postedAt: Long, now: Long): Boolean {
        if (postedAt > 0 && now - postedAt > maxAgeMs) return false
        if (lastText[key] == text) return false

        val mine = sentText
        if (mine != null && now - sentAt < selfReplyWindowMs &&
            key == sentKey && (text == mine || text.endsWith(mine))
        ) {
            remember(key, text)
            return false
        }
        remember(key, text)
        return true
    }

    private fun remember(key: String, text: String) {
        lastText.remove(key)
        lastText[key] = text
        while (lastText.size > maxKeys) lastText.remove(lastText.keys.first())
    }
}
