package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.MobileUser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FictionManagementStateHolderTest {

    @Test
    fun `capability and current user are independent gates`() = runTest {
        val repository = FakeRepository(
            currentUserResult = Result.success(MobileUser(1, "boss", isAdmin = true)),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = FictionManagementStateHolder(repository, cache, dispatcher = UnconfinedTestDispatcher(testScheduler))

        holder.ensureAccess(supported = false)
        assertEquals(FictionManagementAccess.Unsupported, holder.state.value.access)
        assertEquals(0, repository.currentUserCalls)

        holder.ensureAccess(supported = true)
        runCurrent()
        assertEquals(FictionManagementAccess.Admin, holder.state.value.access)
        assertEquals(1, repository.currentUserCalls)

        repository.currentUserResult = Result.success(MobileUser(1, "boss", isAdmin = false))
        holder.ensureAccess(supported = true, forceRefresh = true)
        runCurrent()
        assertEquals(FictionManagementAccess.NotAdmin, holder.state.value.access)
        assertEquals(2, repository.currentUserCalls)

        holder.clear()
        cache.close()
    }

    @Test
    fun `a non-admin account cannot open a management action`() = runTest {
        val repository = FakeRepository(
            currentUserResult = Result.success(MobileUser(2, "listener", isAdmin = false)),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = FictionManagementStateHolder(repository, cache, dispatcher = UnconfinedTestDispatcher(testScheduler))

        holder.ensureAccess(supported = true)
        runCurrent()
        holder.openAdd()
        holder.openEdit(FictionSummary(id = 7, title = "Serial", voice = "voice"))
        holder.askDelete(FictionSummary(id = 7, title = "Serial"))

        assertEquals(FictionManagementAccess.NotAdmin, holder.state.value.access)
        assertFalse(holder.state.value.hasOpenOverlay)
        holder.clear()
        cache.close()
    }

    @Test
    fun `adding trims the source and leaves a blank voice to the server default`() = runTest {
        val repository = adminRepository().apply {
            createFictionResult = Result.success(FictionSummary(id = 12, title = "New serial", voice = "default"))
        }
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = adminHolder(repository, cache)

        holder.openAdd()
        holder.updateAdd(fictionUrl = "  424242  ", voice = "  ")
        holder.submitEditor()
        runCurrent()

        assertEquals("424242", repository.createdFictions.single().fictionUrl)
        assertNull(repository.createdFictions.single().voice)
        assertNull(holder.state.value.editor)
        assertTrue(holder.state.value.notice.orEmpty().contains("New serial"))
        holder.clear()
        cache.close()
    }

    @Test
    fun `editing validates title then sends title author and voice`() = runTest {
        val repository = adminRepository().apply {
            updateFictionResult = Result.success(
                FictionSummary(id = 7, title = "Better", author = null, voice = "new-voice"),
            )
        }
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = adminHolder(repository, cache)

        holder.openEdit(FictionSummary(id = 7, title = "Old", author = "Writer", voice = "old-voice"))
        holder.updateEdit(title = "   ")
        holder.submitEditor()
        assertTrue(holder.state.value.error.orEmpty().contains("Title"))
        assertTrue(repository.updatedFictions.isEmpty())

        holder.updateEdit(title = " Better ", author = "", voice = " new-voice ")
        holder.submitEditor()
        runCurrent()

        val (fictionId, request) = repository.updatedFictions.single()
        assertEquals(7, fictionId)
        assertEquals("Better", request.title)
        assertEquals("", request.author)
        assertEquals("new-voice", request.voice)
        assertNull(holder.state.value.editor)
        holder.clear()
        cache.close()
    }

    @Test
    fun `delete waits for confirmation and publishes the id for navigation`() = runTest {
        val repository = adminRepository()
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = adminHolder(repository, cache)
        val fiction = FictionSummary(id = 7, title = "Shared serial")

        holder.askDelete(fiction)
        val pending = assertIs<FictionDeleteConfirmation>(holder.state.value.deleteConfirmation)
        assertEquals(7, pending.fictionId)
        assertTrue(repository.deletedFictions.isEmpty())

        holder.confirmDelete()
        runCurrent()

        assertEquals(listOf(7), repository.deletedFictions)
        assertEquals(7, holder.state.value.deletedFictionId)
        assertNull(holder.state.value.deleteConfirmation)
        holder.clear()
        cache.close()
    }

    private fun adminRepository(): FakeRepository = FakeRepository(
        currentUserResult = Result.success(MobileUser(1, "boss", isAdmin = true)),
    )

    private fun TestScope.adminHolder(
        repository: FakeRepository,
        cache: LibraryCache,
    ): FictionManagementStateHolder =
        FictionManagementStateHolder(repository, cache, dispatcher = UnconfinedTestDispatcher(testScheduler)).also {
            it.ensureAccess(supported = true)
            runCurrent()
            assertEquals(FictionManagementAccess.Admin, it.state.value.access)
        }

    // --- EPUB upload ------------------------------------------------------------------------------

    private class EpubFixture(
        val repository: FakeRepository,
        val cache: LibraryCache,
        val holder: FictionManagementStateHolder,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.adminFixture(
        picker: EpubFilePicker,
        epubUpload: Boolean = true,
        maxEpubBytes: Long? = 50L * 1024 * 1024,
    ): EpubFixture {
        val repository = FakeRepository(
            currentUserResult = Result.success(MobileUser(1, "boss", isAdmin = true)),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = FictionManagementStateHolder(
            repository,
            cache,
            picker = picker,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        holder.ensureAccess(supported = true, epubUpload = epubUpload, maxEpubBytes = maxEpubBytes)
        runCurrent()
        return EpubFixture(repository, cache, holder)
    }

    private fun epubFile(name: String, bytes: Int): java.io.File =
        java.nio.file.Files.createTempDirectory("ttsroad-epub").resolve(name).toFile()
            .also { it.writeBytes(ByteArray(bytes)) }

    @Test
    fun `a chosen EPUB is uploaded instead of an added URL`() = runTest {
        val epub = epubFile("A Book.epub", bytes = 4)
        val fixture = adminFixture(picker = { epub })

        fixture.holder.openAdd()
        fixture.holder.chooseEpub()
        fixture.holder.updateAdd(voice = "en-US-AriaNeural")
        fixture.holder.submitEditor()
        runCurrent()

        assertEquals(listOf<Pair<java.io.File, String?>>(epub to "en-US-AriaNeural"), fixture.repository.uploadedEpubs)
        assertTrue(fixture.repository.createdFictions.isEmpty(), "the Royal Road path must not also run")
        assertNull(fixture.holder.state.value.editor, "a successful upload closes the dialog")
        fixture.cache.close()
    }

    @Test
    fun `an EPUB removes the requirement for a URL`() = runTest {
        val epub = epubFile("A Book.epub", bytes = 4)
        val fixture = adminFixture(picker = { epub })
        fixture.holder.openAdd()

        // Without a file this fails validation before any request is made.
        fixture.holder.submitEditor()
        runCurrent()
        assertEquals(
            "A Royal Road URL, a fiction id, or an EPUB is required",
            fixture.holder.state.value.error,
        )

        fixture.holder.chooseEpub()
        fixture.holder.submitEditor()
        runCurrent()

        assertNull(fixture.holder.state.value.error)
        assertEquals(1, fixture.repository.uploadedEpubs.size)
        fixture.cache.close()
    }

    @Test
    fun `the wrong kind of file is refused before it is uploaded`() = runTest {
        // Telling somebody their 40 MB book was the wrong kind of file *after* sending it is the
        // worst possible order to do it in — and AWT's filename filter is only a hint anyway.
        val fixture = adminFixture(picker = { epubFile("notes.txt", bytes = 4) })
        fixture.holder.openAdd()

        fixture.holder.chooseEpub()

        assertEquals("Only .epub files can be uploaded", fixture.holder.state.value.error)
        assertNull(assertIs<FictionEditor.Add>(fixture.holder.state.value.editor).epubFile)
        fixture.cache.close()
    }

    @Test
    fun `a file over the server's published ceiling is refused with both numbers`() = runTest {
        val fixture = adminFixture(
            picker = { epubFile("Huge.epub", bytes = 3 * 1024 * 1024) },
            maxEpubBytes = 1024L * 1024,
        )
        fixture.holder.openAdd()

        fixture.holder.chooseEpub()

        val error = fixture.holder.state.value.error.orEmpty()
        assertTrue(error.contains("3.0 MB"), error)
        assertTrue(error.contains("1.0 MB"), error)
        fixture.cache.close()
    }

    @Test
    fun `a server that does not accept files offers no way to choose one`() = runTest {
        var asked = false
        val fixture = adminFixture(
            picker = {
                asked = true
                null
            },
            epubUpload = false,
        )
        fixture.holder.openAdd()

        fixture.holder.chooseEpub()

        assertFalse(asked, "the picker must not open where the server cannot accept the result")
        assertFalse(fixture.holder.state.value.epubUploadAvailable)
        fixture.cache.close()
    }

    @Test
    fun `cancelling the picker changes nothing`() = runTest {
        val fixture = adminFixture(picker = { null })
        fixture.holder.openAdd()
        fixture.holder.updateAdd(fictionUrl = "12345")

        fixture.holder.chooseEpub()

        val editor = assertIs<FictionEditor.Add>(fixture.holder.state.value.editor)
        assertNull(editor.epubFile)
        assertNull(fixture.holder.state.value.error, "cancelling is not an error")
        assertEquals("12345", editor.fictionUrl)
        fixture.cache.close()
    }
}
