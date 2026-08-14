package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.ParsedFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The search state machine.
 *
 * Every answer the screen can be in — nothing searched, results, no matches, failed, and "this
 * server cannot search" — is decided here rather than in the composable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchStateHolderTest {

    private fun holder(repository: FakeRepository) =
        SearchStateHolder(repository, UnconfinedTestDispatcher())

    @Test
    fun `nothing is asked of the server until a search is submitted`() = runTest {
        val repository = FakeRepository(searchResult = Result.success(ParsedFixtures.search))
        val search = holder(repository)

        search.queryChanged("ashf")
        search.queryChanged("ashfall")

        assertEquals(emptyList(), repository.searchQueries)
        assertFalse(search.state.value.hasSearched)
    }

    @Test
    fun `a submitted search publishes the results and the query they answer`() = runTest {
        val repository = FakeRepository(searchResult = Result.success(ParsedFixtures.search))
        val search = holder(repository)

        search.search("ashfall gate")

        val state = search.state.value
        assertEquals(listOf("ashfall gate"), repository.searchQueries)
        assertEquals("ashfall gate", state.resultQuery)
        assertEquals(3, state.result?.total)
        assertFalse(state.busy)
        assertNull(state.error)
    }

    @Test
    fun `a blank query clears rather than asking the server for everything`() = runTest {
        val repository = FakeRepository(searchResult = Result.success(ParsedFixtures.search))
        val search = holder(repository)
        search.search("gate")

        search.search("   ")

        assertEquals(listOf("gate"), repository.searchQueries)
        assertNull(search.state.value.result)
        assertEquals("", search.state.value.resultQuery)
    }

    @Test
    fun `a server that answers 404 is unsupported, not an error the user can retry`() = runTest {
        val repository = FakeRepository(searchResult = Result.success(null))
        val search = holder(repository)

        search.search("gate")

        assertTrue(search.state.value.unsupported)
        assertNull(search.state.value.error)
        assertNull(search.state.value.result)
    }

    @Test
    fun `a failed search keeps the results already on screen`() = runTest {
        // Same rule as a failed library refresh: an error explains less than blanking the screen
        // destroys, and the previous answer is still the best one available.
        val repository = FakeRepository(searchResult = Result.success(ParsedFixtures.search))
        val search = holder(repository)
        search.search("ashfall gate")

        repository.searchResult = Result.failure(java.io.IOException("connection reset"))
        search.search("gate")

        assertNotNull(search.state.value.result)
        assertEquals("ashfall gate", search.state.value.resultQuery)
        assertNotNull(search.state.value.error)
        assertFalse(search.state.value.busy)
    }

    @Test
    fun `an unsupported answer is cleared by the next successful search`() = runTest {
        val repository = FakeRepository(searchResult = Result.success(null))
        val search = holder(repository)
        search.search("gate")

        repository.searchResult = Result.success(ParsedFixtures.search)
        search.search("gate")

        assertFalse(search.state.value.unsupported)
        assertEquals(3, search.state.value.result?.total)
    }

    @Test
    fun `refresh re-runs the query the results belong to, not what is in the field`() = runTest {
        val repository = FakeRepository(searchResult = Result.success(ParsedFixtures.search))
        val search = holder(repository)
        search.search("ashfall gate")
        // The user has started typing a second query but has not submitted it.
        search.queryChanged("something else")

        search.refresh()

        assertEquals(listOf("ashfall gate", "ashfall gate"), repository.searchQueries)
    }

    @Test
    fun `refresh before any search has run does nothing`() = runTest {
        val repository = FakeRepository(searchResult = Result.success(ParsedFixtures.search))

        holder(repository).refresh()

        assertEquals(emptyList(), repository.searchQueries)
    }

    @Test
    fun `signing out drops the results, which belonged to that account`() = runTest {
        val repository = FakeRepository(searchResult = Result.success(ParsedFixtures.search))
        val search = holder(repository)
        search.search("ashfall gate")

        search.sessionEnded()

        assertEquals(SearchUiState(), search.state.value)
    }
}
