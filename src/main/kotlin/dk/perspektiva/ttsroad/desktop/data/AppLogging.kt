package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * A deliberately small rotating log writer for a desktop app with no console window.
 *
 * Rotation is size-based, bounded, and owner-only. Logging must never become a reason the app does
 * not start, so filesystem failures are reported to stderr and swallowed at this outer boundary.
 */
class RotatingFileLog(
    val file: File,
    private val maxBytes: Long = DefaultMaxBytes,
    private val backupCount: Int = DefaultBackupCount,
    private val clock: () -> Instant = Instant::now,
) {
    init {
        require(maxBytes >= 1_024) { "maxBytes must leave room for a useful diagnostic" }
        require(backupCount >= 0) { "backupCount cannot be negative" }
    }

    @Synchronized
    fun write(message: String) {
        runCatching {
            val directory = file.toPath().parent
            if (directory != null) {
                Files.createDirectories(directory)
                SecureFiles.restrictDirectoryToOwner(directory)
            }

            val entry = boundedEntry("${clock()} ${redactSecrets(message)}\n")
            if (file.isFile && file.length() + entry.size > maxBytes) rotate()
            Files.write(
                file.toPath(),
                entry,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE,
            )
            SecureFiles.restrictToOwner(file.toPath())
        }.onFailure {
            // Do not feed this back through AppLog: that would recurse into the writer that failed.
            System.err.println("[TTSRoad] persistent logging is unavailable")
        }
    }

    private fun boundedEntry(value: String): ByteArray {
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (encoded.size <= maxBytes) return encoded
        val marker = "${clock()} [TTSRoad] diagnostic truncated\n".toByteArray(Charsets.UTF_8)
        val room = (maxBytes - marker.size).coerceAtLeast(0).toInt()
        val candidate = value.takeLast(room).toByteArray(Charsets.UTF_8)
        // If multibyte characters made that too large, one quarter of the character count is
        // always safe for UTF-8 and, unlike slicing bytes, cannot leave a malformed log file.
        val tail = if (candidate.size <= room) candidate else value.takeLast(room / 4).toByteArray(Charsets.UTF_8)
        return marker + tail
    }

    private fun rotate() {
        if (backupCount == 0) {
            Files.deleteIfExists(file.toPath())
            return
        }
        for (index in backupCount downTo 1) {
            val source = if (index == 1) file else File(file.parentFile, "${file.name}.${index - 1}")
            if (!source.exists()) continue
            val target = File(file.parentFile, "${file.name}.$index")
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            SecureFiles.restrictToOwner(target.toPath())
        }
    }

    companion object {
        const val DefaultMaxBytes: Long = 1_048_576
        const val DefaultBackupCount: Int = 3
    }
}
