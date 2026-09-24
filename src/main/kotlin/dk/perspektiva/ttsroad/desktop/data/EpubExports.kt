package dk.perspektiva.ttsroad.desktop.data

import okhttp3.Call

interface EpubExportSession {
    fun newCall(): Call
    fun isCurrent(): Boolean
    fun publish(block: () -> Unit): Boolean
    suspend fun endSession(end: SessionEnd)
}

fun suggestedEpubFileName(title: String): String {
    val base = title.substringAfterLast('/').substringAfterLast('\\')
    val withoutExt = if (base.endsWith(".epub", ignoreCase = true)) base.dropLast(5) else base
    val sanitized = withoutExt
        .replace(Regex("[\\s\\u0000-\\u001f\\u007f<>:\"|?*]+"), "_")
        .trim('_', '.', ' ')
        .take(120)
        .takeIf { it.any(Char::isLetterOrDigit) }
        ?: "fiction"
    return "$sanitized.epub"
}
