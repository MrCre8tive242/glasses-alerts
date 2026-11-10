package com.glassesalerts

import android.content.Context
import android.content.SharedPreferences
import com.glassesalerts.logic.QuickReplies

/** Simple wrapper around SharedPreferences for the app's settings. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("glasses_alerts", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Also announce the regular WhatsApp app, not just WhatsApp Business. */
    var includeRegularWhatsApp: Boolean
        get() = sp.getBoolean("include_regular", false)
        set(v) = sp.edit().putBoolean("include_regular", v).apply()

    var includeGroups: Boolean
        get() = sp.getBoolean("include_groups", false)
        set(v) = sp.edit().putBoolean("include_groups", v).apply()

    /** true = read the message text, false = only say who it is from. */
    var readMessageBody: Boolean
        get() = sp.getBoolean("read_body", true)
        set(v) = sp.edit().putBoolean("read_body", v).apply()

    /** Only speak when a Bluetooth audio device (your glasses) is connected. */
    var onlyWhenBluetooth: Boolean
        get() = sp.getBoolean("only_bt", true)
        set(v) = sp.edit().putBoolean("only_bt", v).apply()

    var quietHoursEnabled: Boolean
        get() = sp.getBoolean("quiet_enabled", false)
        set(v) = sp.edit().putBoolean("quiet_enabled", v).apply()

    /** Hour of day, 0-23. Default quiet time is 10 PM to 7 AM. */
    var quietStartHour: Int
        get() = sp.getInt("quiet_start", 22)
        set(v) = sp.edit().putInt("quiet_start", v.coerceIn(0, 23)).apply()

    var quietEndHour: Int
        get() = sp.getInt("quiet_end", 7)
        set(v) = sp.edit().putInt("quiet_end", v.coerceIn(0, 23)).apply()

    /** Announce incoming calls and allow answer/decline with glasses taps. */
    var enableCalls: Boolean
        get() = sp.getBoolean("enable_calls", true)
        set(v) = sp.edit().putBoolean("enable_calls", v).apply()

    /** Offer quick replies after a message is read. */
    var enableReplies: Boolean
        get() = sp.getBoolean("enable_replies", true)
        set(v) = sp.edit().putBoolean("enable_replies", v).apply()

    /** Offer to play voice notes. Needs "All files access". */
    var enableVoiceNotes: Boolean
        get() = sp.getBoolean("enable_voice", true)
        set(v) = sp.edit().putBoolean("enable_voice", v).apply()

    /** true = after a message, a tap lets you speak the reply; false = pick a saved quick reply. */
    var replyDictate: Boolean
        get() = sp.getBoolean("reply_dictate", true)
        set(v) = sp.edit().putBoolean("reply_dictate", v).apply()

    /** One quick reply per line. */
    var quickReplies: String
        get() = sp.getString("quick_replies", QuickReplies.DEFAULT_RAW) ?: QuickReplies.DEFAULT_RAW
        set(v) = sp.edit().putString("quick_replies", v).apply()

    /** true = say "Message from X" and wait for a double tap to play it; false = read it straight away. */
    var tapToPlayMessages: Boolean
        get() = sp.getBoolean("tap_to_play", true)
        set(v) = sp.edit().putBoolean("tap_to_play", v).apply()

    /** Name of the chosen text-to-speech voice, or empty for the phone's default. */
    var ttsVoice: String
        get() = sp.getString("tts_voice", "") ?: ""
        set(v) = sp.edit().putString("tts_voice", v).apply()

    /** 0.5 (slow) to 2.0 (fast). */
    var ttsRate: Float
        get() = sp.getFloat("tts_rate", 1.0f)
        set(v) = sp.edit().putFloat("tts_rate", v.coerceIn(0.5f, 2.0f)).apply()

    var ttsPitch: Float
        get() = sp.getFloat("tts_pitch", 1.0f)
        set(v) = sp.edit().putFloat("tts_pitch", v.coerceIn(0.5f, 1.5f)).apply()
}
