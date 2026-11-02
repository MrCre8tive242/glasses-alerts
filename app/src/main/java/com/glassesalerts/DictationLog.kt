package com.glassesalerts

import android.util.Log

/** Remembers how the last spoken reply went, so the settings screen can show it. */
object DictationLog {
    @Volatile
    var last: String = "none yet"

    fun record(msg: String) {
        last = msg
        Log.d("GlassesAlerts", msg)
    }
}
