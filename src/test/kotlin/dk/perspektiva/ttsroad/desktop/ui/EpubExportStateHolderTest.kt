package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.EpubExportSession
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.ServerCapabilities
import dk.perspektiva.ttsroad.desktop.data.SessionEnd
import dk.perspektiva.ttsroad.desktop.data.SessionState
import dk.perspektiva.ttsroad.desktop.download.EpubDownloadResult
import dk.perspektiva.ttsroad.desktop.download.EpubExportDownloader
import dk.perspektiva.ttsroad.desktop.download.EpubExportOperation
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class EpubExportStateHolderTest {
    @TempDir
    lateinit var tempDir: File

    private val fiction = FictionSummary(id = 12, title = "A Magical Story")

    @Test
    fun `successful export updates notice and resets busy state`() = runTest {
        val destination = File(tempDir, "story.epub")
        val fixture = fixture(picker = { destination })
        fixture.repository.capabilitiesResult = ServerCapabilities(ebookExport = true)
        fixture.repository.refreshCurrentCapabilities()

        fixture.downloader.result = EpubDownloadResult.Success(destination, 5000L)

        fixture.holder.download(fiction)
        runCurrent()

        assertEquals("Saved EPUB to $destination", fixture.holder.state.value.notice)
        assertNull(fixture.holder.state.value.error)
        assertFalse(fixture.holder.state.value.isBusy)

        fixture.close()
    }

    @Test
    fun `canceling picker does not download or leave busy state`() = runTest {
        val fixture = fixture(picker = { null })
        fixture.repository.capabilitiesResult = ServerCapabilities(ebookExport = true)
        fixture.repository.refreshCurrentCapabilities()

        fixture.holder.download(fiction)
        runCurrent()

        assertEquals(0, fixture.downloader.calls)
        assertFalse(fixture.holder.state.value.isBusy)
        assertNull(fixture.holder.state.value.notice)

        fixture.close()
    }

    @Test
    fun `download is gated on ebookExport capability`() = runTest {
        val destination = File(tempDir, "story.epub")
        val fixture = fixture(picker = { destination })
        fixture.repository.capabilitiesResult = ServerCapabilities.Baseline
        fixture.repository.refreshCurrentCapabilities()

        fixture.holder.download(fiction)
        runCurrent()

        assertEquals(0, fixture.downloader.calls)
        assertFalse(fixture.holder.state.value.isBusy)

        fixture.close()
    }

    @Test
    fun `download failure sets error message`() = runTest {
        val destination = File(tempDir, "story.epub")
        val fixture = fixture(picker = { destination })
        fixture.repository.capabilitiesResult = ServerCapabilities(ebookExport = true)
        fixture.repository.refreshCurrentCapabilities()

        fixture.downloader.result = EpubDownloadResult.Failed("Server returned 409")

        fixture.holder.download(fiction)
        runCurrent()

        assertEquals("Server returned 409", fixture.holder.state.value.error)
        assertNull(fixture.holder.state.value.notice)
        assertFalse(fixture.holder.state.value.isBusy)

        fixture.close()
    }

    @Test
    fun `session sign-out cancels active export`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val destination = File(tempDir, "story.epub")
        val fixture = fixture(dispatcher = dispatcher, picker = { destination })
        fixture.repository.capabilitiesResult = ServerCapabilities(ebookExport = true)
        fixture.repository.refreshCurrentCapabilities()
        fixture.downloader.suspendUntilCancelled = true

        fixture.holder.download(fiction)
        runCurrent()

        assertTrue(fixture.holder.state.value.isBusy)

        fixture.sessionStore.clearToken()
        runCurrent()

        assertFalse(fixture.holder.state.value.isBusy)
        assertNull(fixture.holder.state.value.notice)

        fixture.close()
    }

    private class FakeDownloader : EpubExportDownloader {
        var calls = 0
        var suspendUntilCancelled: Boolean = false
        var result: EpubDownloadResult = EpubDownloadResult.Success(File("dummy.epub"), 100L)

        override suspend fun download(
            operation: EpubExportOperation,
            destination: File,
            onProgress: (Long, Long) -> Unit,
        ): EpubDownloadResult {
            calls++
            if (suspendUntilCancelled) {
                kotlinx.coroutines.awaitCancellation()
            }
            return result
        }
    }

    private class Fixture(
        val sessionStore: InMemorySessionStore,
        val repository: FakeRepository,
        val downloader: FakeDownloader,
        val holder: EpubExportStateHolder,
    ) {
        fun close() {
            holder.clear()
        }
    }

    private fun TestScope.fixture(
        dispatcher: TestDispatcher = UnconfinedTestDispatcher(testScheduler),
        picker: EpubSavePicker = EpubSavePicker { null },
    ): Fixture {
        val sessionStore = InMemorySessionStore(
            SessionState(serverUrl = "https://example.com/", token = "token123", username = "tester"),
        )
        val repository = object : FakeRepository(serverUrl = "https://example.com/") {
            override fun epubExportSession(fictionId: Int): EpubExportSession? {
                if (!currentCapabilities.value.ebookExport) return null
                return object : EpubExportSession {
                    val client = OkHttpClient()
                    var isCur = true
                    override fun newCall(): Call = client.newCall(Request.Builder().url("https://example.com/api/fictions/$fictionId/export.epub").build())
                    override fun isCurrent(): Boolean = isCur
                    override fun publish(block: () -> Unit): Boolean {
                        if (!isCur) return false
                        block()
                        return true
                    }
                    override suspend fun endSession(end: SessionEnd) {
                        isCur = false
                        sessionStore.clearToken()
                    }
                }
            }
        }
        val downloader = FakeDownloader()
        val holder = EpubExportStateHolder(repository, downloader, sessionStore, picker, dispatcher)
        return Fixture(sessionStore, repository, downloader, holder)
    }
}
