package dk.perspektiva.ttsroad.desktop

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class MainTest {
    @Test
    fun `an uncaught background exception is reported before process termination`() {
        val events = mutableListOf<String>()
        val thread = Thread("playback-worker")
        val error = IllegalStateException("broken shared state")
        val handler = terminatingUncaughtExceptionHandler(
            report = { reportedThread, reportedError ->
                events += "report:${reportedThread.name}:${reportedError.message}"
            },
            terminate = { status -> events += "exit:$status" },
        )

        handler.uncaughtException(thread, error)

        assertEquals(listOf("report:playback-worker:broken shared state", "exit:1"), events)
    }

    @Test
    fun `a failed crash report still terminates the process`() {
        val exitStatuses = mutableListOf<Int>()
        val handler = terminatingUncaughtExceptionHandler(
            report = { _, _ -> error("reporting failed") },
            terminate = { status -> exitStatuses += status },
        )

        runCatching { handler.uncaughtException(Thread.currentThread(), IllegalStateException()) }

        assertEquals(listOf(1), exitStatuses)
    }
}
