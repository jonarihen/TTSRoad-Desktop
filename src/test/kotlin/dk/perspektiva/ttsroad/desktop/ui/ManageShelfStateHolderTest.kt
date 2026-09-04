package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import dk.perspektiva.ttsroad.desktop.data.LibraryResponse
import kotlin.test.assertContentEquals
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

/**
 * The shelf editor's whole job is telling the truth about a job half done.
 *
 * The unfollows go out one at a time against the existing per-fiction route, so a failure part-way
 * through is the normal case rather than an edge: some rows are genuinely gone and the rest are
 * genuinely still there, and neither "done" nor "failed" describes that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageShelfStateHolderTest {

    private val shelf = listOf(
        FictionSummary(id = 1, title = "Wandering Inn", following = true),
        FictionSummary(id = 2, title = "Mother of Learning", following = true),
        FictionSummary(id = 3, title = "Worth the Candle", following = true),
    )

    @Test
    fun `the shelf arrives from the cache`() = runTest {
        val fixture = fixture()
        fixture.cache.refreshLibrary()
        runCurrent()

        assertEquals(3, fixture.holder.state.value.fictions.size)
        assertEquals(0, fixture.holder.state.value.selectedCount)
        assertFalse(fixture.holder.state.value.canUnfollow)

        fixture.close()
    }

    @Test
    fun `select all toggles both ways from one control`() = runTest {
        val fixture = fixture()
        fixture.cache.refreshLibrary()
        runCurrent()

        fixture.holder.toggleAll()
        assertEquals(setOf(1, 2, 3), fixture.holder.state.value.selected)
        assertTrue(fixture.holder.state.value.allSelected)

        fixture.holder.toggleAll()
        assertEquals(emptySet(), fixture.holder.state.value.selected)

        fixture.close()
    }

    @Test
    fun `a selection is pruned when the shelf stops holding the fiction`() = runTest {
        val fixture = fixture()
        fixture.cache.refreshLibrary()
        runCurrent()
        fixture.holder.toggleAll()

        fixture.repository.libraryResult = Result.success(LibraryResponse(fictions = shelf.take(1)))
        fixture.cache.refreshLibrary()
        runCurrent()

        assertEquals(
            setOf(1),
            fixture.holder.state.value.selected,
            "a count naming rows the screen cannot show is a count nobody can act on",
        )

        fixture.close()
    }

    @Test
    fun `unfollowing everything selected reports what went`() = runTest {
        val fixture = fixture()
        fixture.cache.refreshLibrary()
        runCurrent()
        fixture.holder.toggleAll()
        fixture.holder.askToUnfollow()

        assertEquals(3, fixture.holder.state.value.confirming)
        assertNotNull(fixture.holder.state.value.confirmationBody)

        fixture.repository.libraryResult = Result.success(LibraryResponse())
        fixture.holder.confirmUnfollow()
        runCurrent()

        assertContentEquals(
            listOf(1 to false, 2 to false, 3 to false),
            fixture.repository.followCalls,
            "attempted in shelf order, so a partial result reads as a prefix",
        )
        assertEquals("Unfollowed 3 fictions.", fixture.holder.state.value.notice)
        assertEquals(emptySet(), fixture.holder.state.value.selected)
        assertFalse(fixture.holder.state.value.isBusy)

        fixture.close()
    }

    @Test
    fun `a failure part-way through keeps the failures ticked and reports both numbers`() = runTest {
        val fixture = fixture()
        fixture.cache.refreshLibrary()
        runCurrent()
        fixture.holder.toggleAll()

        fixture.repository.followResultFor = { id ->
            if (id == 2) Result.failure(IllegalStateException("boom")) else null
        }
        fixture.holder.askToUnfollow()
        fixture.holder.confirmUnfollow()
        runCurrent()

        assertEquals(
            "Unfollowed 2 of 3. 1 could not be removed and are still on your shelf.",
            fixture.holder.state.value.notice,
        )
        assertEquals(
            setOf(2),
            fixture.holder.state.value.selected,
            "what failed stays ticked, because that is what a retry acts on",
        )
        assertNull(fixture.holder.state.value.error, "a partial result is a notice, not an error")

        fixture.close()
    }

    @Test
    fun `a 404 is not counted as a removal`() = runTest {
        val fixture = fixture()
        fixture.cache.refreshLibrary()
        runCurrent()
        fixture.holder.toggle(1)

        // Null is the server's 404: no such fiction, or no such endpoint. Neither removed anything.
        fixture.repository.followResult = Result.success(null)
        fixture.holder.askToUnfollow()
        fixture.holder.confirmUnfollow()
        runCurrent()

        assertEquals(
            "That fiction could not be unfollowed. It is still on your shelf.",
            fixture.holder.state.value.notice,
        )
        assertEquals(setOf(1), fixture.holder.state.value.selected)

        fixture.close()
    }

    @Test
    fun `nothing is sent without a confirmation`() = runTest {
        val fixture = fixture()
        fixture.cache.refreshLibrary()
        runCurrent()
        fixture.holder.toggle(1)

        fixture.holder.confirmUnfollow()
        runCurrent()

        assertTrue(
            fixture.repository.followCalls.isEmpty(),
            "confirmUnfollow is the answer to a question; without the question it is not an answer",
        )

        fixture.close()
    }

    @Test
    fun `an empty selection raises no confirmation`() = runTest {
        val fixture = fixture()
        fixture.cache.refreshLibrary()
        runCurrent()

        fixture.holder.askToUnfollow()

        assertNull(fixture.holder.state.value.confirming)

        fixture.close()
    }

    @Test
    fun `dismissing the confirmation sends nothing`() = runTest {
        val fixture = fixture()
        fixture.cache.refreshLibrary()
        runCurrent()
        fixture.holder.toggle(2)
        fixture.holder.askToUnfollow()

        fixture.holder.dismissConfirmation()
        runCurrent()

        assertNull(fixture.holder.state.value.confirming)
        assertTrue(fixture.repository.followCalls.isEmpty())
        assertEquals(setOf(2), fixture.holder.state.value.selected, "dismissing is not de-selecting")

        fixture.close()
    }

    // --- fixture ----------------------------------------------------------------------------------

    private class Fixture(
        val repository: FakeRepository,
        val cache: LibraryCache,
        val holder: ManageShelfStateHolder,
    ) {
        fun close() {
            holder.clear()
            cache.close()
        }
    }

    private fun TestScope.fixture(): Fixture {
        val repository = FakeRepository(libraryResult = Result.success(LibraryResponse(fictions = shelf)))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val cache = LibraryCache(repository, dispatcher)
        return Fixture(repository, cache, ManageShelfStateHolder(cache, dispatcher))
    }
}
