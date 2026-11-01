package com.glassesalerts

import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Remembers the last glasses button press so the settings screen can show it. */
object ButtonLog {
    @Volatile
    var last: String = "No button pressed yet"

    fun record(keyCode: Int) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        last = "${friendly(keyCode)} (code $keyCode) at $time"
    }

    /** Spoken/readable name, e.g. "MEDIA PLAY PAUSE". */
    fun friendly(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
}
