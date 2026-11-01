package com.glassesalerts

import android.os.Environment
import com.glassesalerts.logic.FileInfo
import com.glassesalerts.logic.VoiceNotePicker
import java.io.File

/**
 * Finds the voice note file WhatsApp just saved. Needs "All files access".
 * WhatsApp Business keeps voice notes under
 *   Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes/<year+week>/
 * (older versions used a top-level "WhatsApp Business" folder). Paths can change between versions.
 */
object VoiceNoteFinder {

    fun roots(pkg: String): List<File> {
        val base = Environment.getExternalStorageDirectory()
        val name = if (pkg == AlertListenerService.BUSINESS_PKG) "WhatsApp Business" else "WhatsApp"
        return listOf(
            File(base, "Android/media/$pkg/$name/Media/$name Voice Notes"),
            File(base, "$name/Media/$name Voice Notes"),
        )
    }

    /** Blocks (call from a background thread). Returns the file or null after timeoutMs. */
    fun find(pkg: String, notifiedAt: Long, timeoutMs: Long = 12_000L): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val infos = roots(pkg).filter { it.isDirectory }.flatMap { root ->
                root.walkTopDown().maxDepth(3).filter { it.isFile }
                    .map { FileInfo(it.absolutePath, it.lastModified(), it.length()) }
                    .toList()
            }
            val pick = VoiceNotePicker.pick(infos, notifiedAt)
            if (pick != null) {
                val f = File(pick.path)
                val before = f.length()
                Thread.sleep(600) // make sure the download has finished writing
                if (f.length() == before) return f
            } else {
                Thread.sleep(700)
            }
        }
        return null
    }
}
