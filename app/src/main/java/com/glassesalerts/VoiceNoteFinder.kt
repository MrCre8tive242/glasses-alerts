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
 *
 * Only the newest few week-folders are looked at, so a big voice note history does not slow it down.
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

    private fun candidates(pkg: String): List<FileInfo> {
        val out = ArrayList<FileInfo>()
        for (root in roots(pkg)) {
            if (!root.isDirectory) continue
            val children = root.listFiles() ?: continue
            val newestDirs = children.filter { it.isDirectory }
                .sortedByDescending { it.lastModified() }
                .take(3)
            val files = children.filter { it.isFile } +
                newestDirs.flatMap { d -> d.listFiles()?.filter { it.isFile } ?: emptyList() }
            for (f in files) out.add(FileInfo(f.absolutePath, f.lastModified(), f.length()))
        }
        return out
    }

    /** Blocks (call from a background thread). Returns the file, or null after timeoutMs. */
    fun find(pkg: String, notifiedAt: Long, timeoutMs: Long = 25_000L): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pick = VoiceNotePicker.pick(candidates(pkg), notifiedAt)
            if (pick != null) {
                val f = File(pick.path)
                val before = f.length()
                Thread.sleep(250) // make sure the download has finished writing
                if (f.length() == before) return f
            } else {
                Thread.sleep(400)
            }
        }
        return null
    }
}
