package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.ListeningStateReport
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningBackupStateHolderTest {

    @TempDir
    lateinit var tempDir: File

    private val document: Map<String, Any?> = mapOf(
        "version" to 1.0,
        "fictions" to listOf(mapOf("slug" to "wandering-inn")),
    )

    @Test
    fun `export writes the document to the chosen file`() = runTest {
        val target = File(tempDir, "backup.json")
        val repository = FakeRepository(exportListeningStateResult = Result.success(document))
        val holder = holder(repository, save = { target })

        holder.export()
        runCurrent()

        assertTrue(target.isFile)
        assertTrue(target.readText().contains("wandering-inn"))
        assertEquals(target.absolutePath, holder.state.value.savedTo)
        assertNull(holder.state.value.error)

        holder.clear()
    }

    @Test
    fun `the suggested filename is dated`() = runTest {
        var suggested: String? = null
        val repository = FakeRepository(exportListeningStateResult = Result.success(document))
        val holder = holder(
            repository,
            save = { name -> suggested = name; File(tempDir, "out.json") },
            today = { LocalDate.of(2026, 9, 4) },
        )

        holder.export()
        runCurrent()

        assertEquals("ttsroad-listening-2026-09-04.json", suggested)

        holder.clear()
    }

    @Test
    fun `the document is fetched before the save dialog opens`() = runTest {
        var dialogOpened = false
        val repository = FakeRepository(exportListeningStateResult = Result.success(null))
        val holder = holder(repository, save = { dialogOpened = true; null })

        holder.export()
        runCurrent()

        assertFalse(
            dialogOpened,
            "asking where to save first would leave an empty file behind when the server 404s",
        )
        assertEquals("This server cannot export listening state.", holder.state.value.error)

        holder.clear()
    }

    @Test
    fun `cancelling the save dialog leaves no trace`() = runTest {
        val repository = FakeRepository(exportListeningStateResult = Result.success(document))
        val holder = holder(repository, save = { null })

        holder.export()
        runCurrent()

        assertNull(holder.state.value.savedTo)
        assertNull(holder.state.value.error, "cancelling is not a failure")
        assertFalse(holder.state.value.busy)

        holder.clear()
    }

    @Test
    fun `import posts the document and reports the merge`() = runTest {
        val source = File(tempDir, "in.json")
        source.writeText("""{"version":1,"fictions":[]}""")
        val repository = FakeRepository(
            importListeningStateResult = Result.success(
                ListeningStateReport(fictionsMatched = 2, playbackRestored = 4),
            ),
        )
        val holder = holder(repository, open = { source })

        holder.import()
        runCurrent()

        assertEquals(1, repository.importedDocuments.size)
        assertTrue(holder.state.value.importLines.any { it == "4 positions restored" })
        assertNull(holder.state.value.error)

        holder.clear()
    }

    @Test
    fun `a wrapped document is unwrapped before posting`() = runTest {
        val source = File(tempDir, "wrapped.json")
        source.writeText("""{"document":{"version":1,"fictions":[]}}""")
        val repository = FakeRepository(
            importListeningStateResult = Result.success(ListeningStateReport(fictionsMatched = 1)),
        )
        val holder = holder(repository, open = { source })

        holder.import()
        runCurrent()

        val sent = repository.importedDocuments.single()
        assertTrue(
            sent.containsKey("version"),
            "the export wraps the document; posting the wrapper back would be a different shape",
        )
        assertFalse(sent.containsKey("document"))

        holder.clear()
    }

    @Test
    fun `a file that is not JSON is refused before a request`() = runTest {
        val source = File(tempDir, "notes.txt")
        source.writeText("this is not a backup")
        val repository = FakeRepository()
        val holder = holder(repository, open = { source })

        holder.import()
        runCurrent()

        assertEquals("That file is not a listening backup.", holder.state.value.error)
        assertTrue(
            repository.importedDocuments.isEmpty(),
            "the filename filter is a hint several window managers ignore",
        )

        holder.clear()
    }

    @Test
    fun `cancelling the open dialog does nothing at all`() = runTest {
        val repository = FakeRepository()
        val holder = holder(repository, open = { null })

        holder.import()
        runCurrent()

        assertFalse(holder.state.value.busy)
        assertNull(holder.state.value.error)
        assertTrue(repository.importedDocuments.isEmpty())

        holder.clear()
    }

    @Test
    fun `a rejected document surfaces the server's reason`() = runTest {
        val source = File(tempDir, "bad.json")
        source.writeText("""{"version":99}""")
        val repository = FakeRepository(
            importListeningStateResult = Result.failure(IllegalStateException("Unsupported version")),
        )
        val holder = holder(repository, open = { source })

        holder.import()
        runCurrent()

        assertNotNull(holder.state.value.error)
        assertTrue(holder.state.value.importLines.isEmpty())

        holder.clear()
    }

    private fun TestScope.holder(
        repository: FakeRepository,
        save: (String) -> File? = { null },
        open: () -> File? = { null },
        today: () -> LocalDate = { LocalDate.of(2026, 9, 4) },
    ) = ListeningBackupStateHolder(
        repository,
        savePicker = { name -> save(name) },
        openPicker = { open() },
        today = today,
        dispatcher = UnconfinedTestDispatcher(testScheduler),
    )
}
