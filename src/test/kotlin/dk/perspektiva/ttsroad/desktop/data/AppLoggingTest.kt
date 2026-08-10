package dk.perspektiva.ttsroad.desktop.data

import java.io.File
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AppLoggingTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `persistent log redacts secrets at its own boundary`() {
        val file = File(tempDir, "ttsroad.log")
        val log = RotatingFileLog(file, clock = { Instant.EPOCH })

        log.write("Authorization: Bearer ttsr_Zm9vYmFyYmF6cXV1eA")

        val text = file.readText()
        assertFalse(text.contains("ttsr_Zm9vYmFyYmF6cXV1eA"), text)
        assertFalse(text.contains("Bearer"), text)
        assertTrue(text.contains(RedactionPlaceholder), text)
    }

    @Test
    fun `persistent log keeps a bounded current file and bounded backups`() {
        val file = File(tempDir, "ttsroad.log")
        val log = RotatingFileLog(
            file = file,
            maxBytes = 1_024,
            backupCount = 2,
            clock = { Instant.EPOCH },
        )

        repeat(5) { index -> log.write("entry-$index ${"x".repeat(700)}") }

        assertTrue(file.isFile && file.length() <= 1_024, "current=${file.length()}")
        assertTrue(File(tempDir, "ttsroad.log.1").isFile)
        assertTrue(File(tempDir, "ttsroad.log.2").isFile)
        assertFalse(File(tempDir, "ttsroad.log.3").exists())
    }
}
