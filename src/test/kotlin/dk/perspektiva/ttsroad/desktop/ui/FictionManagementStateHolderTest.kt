package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.MobileUser
import dk.perspektiva.ttsroad.desktop.data.MobileVoice
import dk.perspektiva.ttsroad.desktop.data.SyncScope
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
    fun `the catalogue is fetched only for an admin on a server that advertises it`() = runTest {
        val voices = listOf(MobileVoice("en-US-BrianNeural", "en-US", "Male"))
        val repository = FakeRepository(
            currentUserResult = Result.success(MobileUser(1, "reader", isAdmin = false)),
            voicesResult = Result.success(voices),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = FictionManagementStateHolder(repository, cache, dispatcher = UnconfinedTestDispatcher(testScheduler))

        // Advertised, but this account cannot apply a choice — listing it would draw a picker whose
        // save is a 403.
        holder.ensureAccess(supported = true, voiceCatalogue = true)
        runCurrent()
        assertEquals(0, repository.voicesCalls)
        assertFalse(holder.state.value.canPickVoice)

        repository.currentUserResult = Result.success(MobileUser(1, "boss", isAdmin = true))
        holder.ensureAccess(supported = true, voiceCatalogue = true, forceRefresh = true)
        runCurrent()
        assertEquals(1, repository.voicesCalls)
        assertTrue(holder.state.value.canPickVoice)
        assertEquals(voices, holder.state.value.voices)

        holder.clear()
        cache.close()
    }

    @Test
    fun `a server that does not advertise the catalogue is never asked for it`() = runTest {
        val repository = FakeRepository(
            currentUserResult = Result.success(MobileUser(1, "boss", isAdmin = true)),
            voicesResult = Result.success(listOf(MobileVoice("en-US-BrianNeural", "en-US", "Male"))),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = FictionManagementStateHolder(repository, cache, dispatcher = UnconfinedTestDispatcher(testScheduler))

        holder.ensureAccess(supported = true, voiceCatalogue = false)
        runCurrent()

        assertEquals(0, repository.voicesCalls)
        assertNull(holder.state.value.voices)
        assertFalse(holder.state.value.canPickVoice)

        holder.clear()
        cache.close()
    }

    @Test
    fun `a catalogue that will not load leaves the field typed rather than raising an error`() = runTest {
        val repository = FakeRepository(
            currentUserResult = Result.success(MobileUser(1, "boss", isAdmin = true)),
            voicesResult = Result.failure(IllegalStateException("no route")),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = FictionManagementStateHolder(repository, cache, dispatcher = UnconfinedTestDispatcher(testScheduler))

        holder.ensureAccess(supported = true, voiceCatalogue = true)
        runCurrent()

        assertEquals(1, repository.voicesCalls)
        assertNull(holder.state.value.voices)
        assertNull(
            holder.state.value.error,
            "the picker is an improvement on a field that still works; its failure is not the user's problem",
        )
        assertEquals(FictionManagementAccess.Admin, holder.state.value.access)

        holder.clear()
        cache.close()
    }

    @Test
    fun `a malformed rate is reported on the add form before anything is sent`() = runTest {
        val repository = FakeRepository(
            currentUserResult = Result.success(MobileUser(1, "boss", isAdmin = true)),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = FictionManagementStateHolder(repository, cache, dispatcher = UnconfinedTestDispatcher(testScheduler))

        holder.ensureAccess(supported = true)
        runCurrent()
        holder.openAdd()

        assertNull(holder.state.value.rateProblem, "a blank rate means leave the default alone")

        holder.updateAdd(rate = "quickly")
        assertNotNull(holder.state.value.rateProblem)

        holder.updateAdd(rate = "+10%")
        assertNull(holder.state.value.rateProblem)

        holder.clear()
        cache.close()
    }

    @Test
    fun `a malformed rate is refused by the holder, not only by the button`() = runTest {
        val repository = FakeRepository(
            currentUserResult = Result.success(MobileUser(1, "boss", isAdmin = true)),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = FictionManagementStateHolder(repository, cache, dispatcher = UnconfinedTestDispatcher(testScheduler))

        holder.ensureAccess(supported = true)
        runCurrent()
        holder.openAdd()
        holder.updateAdd(fictionUrl = "https://www.royalroad.com/fiction/21220", rate = "quickly")

        holder.submitAdd()
        runCurrent()

        assertTrue(repository.createdFictions.isEmpty())
        assertNotNull(holder.state.value.error)

        holder.clear()
        cache.close()
    }

    @Test
    fun `a bare rate is signed before it is sent`() = runTest {
        val repository = FakeRepository(
            currentUserResult = Result.success(MobileUser(1, "boss", isAdmin = true)),
        )
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = FictionManagementStateHolder(repository, cache, dispatcher = UnconfinedTestDispatcher(testScheduler))

        holder.ensureAccess(supported = true)
        runCurrent()
        holder.openAdd()
        holder.updateAdd(fictionUrl = "https://www.royalroad.com/fiction/21220", rate = "10")
        holder.submitAdd()
        runCurrent()

        assertEquals("+10%", repository.createdFictions.single().rate)

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
        holder.submitAdd()
        runCurrent()

        assertEquals("424242", repository.createdFictions.single().fictionUrl)
        assertNull(repository.createdFictions.single().voice)
        assertNull(holder.state.value.editor)
        assertTrue(holder.state.value.notice.orEmpty().contains("New serial"))
        holder.clear()
        cache.close()
    }

    @Test
    fun `adding sends the web form's chapter limit rather than the whole backlog`() = runTest {
        val repository = adminRepository()
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = adminHolder(repository, cache)

        holder.openAdd()
        holder.updateAdd(fictionUrl = "424242")
        holder.submitAdd()
        runCurrent()

        // The backend reads an absent sync_limit as *every chapter* — `if body.sync_limit:` then
        // `poll_and_process_fiction(id, True)` — so omitting it queues a whole serial of TTS.
        val request = repository.createdFictions.single()
        assertEquals(25, request.syncLimit)
        assertEquals("last", request.syncDirection)
        assertTrue(request.enabled)
        holder.clear()
        cache.close()
    }

    @Test
    fun `the whole backlog is reachable, and only when it is actually asked for`() = runTest {
        val repository = adminRepository()
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = adminHolder(repository, cache)

        holder.openAdd()
        holder.updateAdd(fictionUrl = "424242", syncScope = SyncScope.Everything)
        holder.submitAdd()
        runCurrent()

        assertNull(repository.createdFictions.single().syncLimit, "null is the server's own no-limit")
        assertTrue(
            holder.state.value.notice.orEmpty().contains("whole backlog"),
            "the notice has to say what was actually queued: ${holder.state.value.notice}",
        )
        holder.clear()
        cache.close()
    }

    @Test
    fun `oldest first flips the direction rather than the limit`() = runTest {
        val repository = adminRepository()
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = adminHolder(repository, cache)

        holder.openAdd()
        holder.updateAdd(fictionUrl = "424242", syncScope = SyncScope.OldestTwentyFive)
        holder.submitAdd()
        runCurrent()

        val request = repository.createdFictions.single()
        assertEquals(25, request.syncLimit)
        assertEquals("first", request.syncDirection)
        holder.clear()
        cache.close()
    }

    @Test
    fun `rate and the auto-poll switch reach the request`() = runTest {
        val repository = adminRepository()
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = adminHolder(repository, cache)

        holder.openAdd()
        holder.updateAdd(fictionUrl = "424242", rate = "  +10%  ", autoPoll = false)
        holder.submitAdd()
        runCurrent()

        val request = repository.createdFictions.single()
        assertEquals("+10%", request.rate)
        assertFalse(request.enabled, "a finished work does not need a poller")
        holder.clear()
        cache.close()
    }

    @Test
    fun `a blank rate is omitted rather than sent as an empty string`() = runTest {
        val repository = adminRepository()
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        val holder = adminHolder(repository, cache)

        holder.openAdd()
        holder.updateAdd(fictionUrl = "424242", rate = "   ")
        holder.submitAdd()
        runCurrent()

        assertNull(repository.createdFictions.single().rate, "blank means leave the server's default")
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
        fixture.holder.submitAdd()
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
        fixture.holder.submitAdd()
        runCurrent()
        assertEquals(
            "A Royal Road URL, a fiction id, or an EPUB is required",
            fixture.holder.state.value.error,
        )

        fixture.holder.chooseEpub()
        fixture.holder.submitAdd()
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
        assertNull(assertIs<FictionAddDraft>(fixture.holder.state.value.editor).epubFile)
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

        val editor = assertIs<FictionAddDraft>(fixture.holder.state.value.editor)
        assertNull(editor.epubFile)
        assertNull(fixture.holder.state.value.error, "cancelling is not an error")
        assertEquals("12345", editor.fictionUrl)
        fixture.cache.close()
    }
}
