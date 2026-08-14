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
        val holder = FictionManagementStateHolder(repository, cache, UnconfinedTestDispatcher(testScheduler))

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
        val holder = FictionManagementStateHolder(repository, cache, UnconfinedTestDispatcher(testScheduler))

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
        FictionManagementStateHolder(repository, cache, UnconfinedTestDispatcher(testScheduler)).also {
            it.ensureAccess(supported = true)
            runCurrent()
            assertEquals(FictionManagementAccess.Admin, it.state.value.access)
        }
}
