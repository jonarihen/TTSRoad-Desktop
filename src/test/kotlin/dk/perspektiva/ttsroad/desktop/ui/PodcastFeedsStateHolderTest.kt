package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.ClipboardWriter
import dk.perspektiva.ttsroad.desktop.data.FeedsResponse
import dk.perspektiva.ttsroad.desktop.data.FictionFeedUrl
import dk.perspektiva.ttsroad.desktop.data.LibraryFeedUrls
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastFeedsStateHolderTest {

    private val response = FeedsResponse(
        library = LibraryFeedUrls(
            feedTokenVersion = 2,
            feedUrl = "https://ttsroad/feed/7?t=abc",
            opmlUrl = "https://ttsroad/opml/7?t=abc",
        ),
        fictions = listOf(
            FictionFeedUrl(fictionId = 1, title = "Wandering Inn", feedUrl = "https://ttsroad/f/1?t=xyz"),
        ),
    )

    @Test
    fun `loads once and not again`() = runTest {
        val repository = FakeRepository(feedsResult = Result.success(response))
        val holder = holder(repository)

        holder.ensureLoaded()
        runCurrent()
        holder.ensureLoaded()
        runCurrent()

        assertEquals(1, repository.feedsCalls)
        assertEquals(2, holder.state.value.account.size)
        assertEquals(1, holder.state.value.fictions.size)

        holder.clear()
    }

    @Test
    fun `refresh asks again`() = runTest {
        val repository = FakeRepository(feedsResult = Result.success(response))
        val holder = holder(repository)

        holder.ensureLoaded()
        runCurrent()
        holder.ensureLoaded(force = true)
        runCurrent()

        assertEquals(2, repository.feedsCalls)

        holder.clear()
    }

    @Test
    fun `a 404 is unsupported, not an error`() = runTest {
        val repository = FakeRepository(feedsResult = Result.success(null))
        val holder = holder(repository)

        holder.ensureLoaded()
        runCurrent()

        assertTrue(holder.state.value.unsupported)
        assertNull(holder.state.value.error)

        holder.clear()
    }

    @Test
    fun `copying puts the url on the clipboard and does not echo it back`() = runTest {
        val repository = FakeRepository(feedsResult = Result.success(response))
        val written = mutableListOf<String>()
        val holder = holder(repository, ClipboardWriter { written += it })
        holder.ensureLoaded()
        runCurrent()

        val link = holder.state.value.account.first()
        holder.copy(link)

        assertContentEquals(listOf("https://ttsroad/feed/7?t=abc"), written)
        val notice = holder.state.value.notice
        assertNotNull(notice)
        assertEquals("Copied the Combined feed URL.", notice)
        assertFalse(
            notice.contains("t=abc"),
            "repeating a credential into a status line puts it where a screenshot catches it",
        )

        holder.clear()
    }

    @Test
    fun `a clipboard that refuses is reported without losing the list`() = runTest {
        val repository = FakeRepository(feedsResult = Result.success(response))
        val holder = holder(repository, ClipboardWriter { error("no clipboard") })
        holder.ensureLoaded()
        runCurrent()

        holder.copy(holder.state.value.account.first())

        assertEquals("Could not reach the clipboard.", holder.state.value.error)
        assertEquals(2, holder.state.value.account.size)

        holder.clear()
    }

    @Test
    fun `rotate asks first and sends nothing until confirmed`() = runTest {
        val repository = FakeRepository(feedsResult = Result.success(response))
        val holder = holder(repository)
        holder.ensureLoaded()
        runCurrent()

        holder.askToRotate()
        assertTrue(holder.state.value.confirmingRotate)
        assertEquals(0, repository.rotateFeedCalls)

        holder.dismissRotate()
        runCurrent()
        assertFalse(holder.state.value.confirmingRotate)
        assertEquals(
            0,
            repository.rotateFeedCalls,
            "dismissing must not revoke URLs every subscribed podcast app is using",
        )

        holder.clear()
    }

    @Test
    fun `confirming adopts the new urls from the same response`() = runTest {
        val rotated = response.copy(
            library = LibraryFeedUrls(
                feedTokenVersion = 3,
                feedUrl = "https://ttsroad/feed/7?t=new",
                opmlUrl = "https://ttsroad/opml/7?t=new",
            ),
        )
        val repository = FakeRepository(
            feedsResult = Result.success(response),
            rotateFeedResult = Result.success(rotated),
        )
        val holder = holder(repository)
        holder.ensureLoaded()
        runCurrent()

        holder.askToRotate()
        holder.confirmRotate()
        runCurrent()

        assertEquals(1, repository.rotateFeedCalls)
        assertEquals(
            1,
            repository.feedsCalls,
            "the rotate answers with the same shape, so re-fetching would be a second request " +
                "and a window showing revoked URLs as if they worked",
        )
        assertEquals("https://ttsroad/feed/7?t=new", holder.state.value.account.first().url)
        assertNotNull(holder.state.value.notice)

        holder.clear()
    }

    @Test
    fun `confirming without asking does nothing`() = runTest {
        val repository = FakeRepository(feedsResult = Result.success(response))
        val holder = holder(repository)
        holder.ensureLoaded()
        runCurrent()

        holder.confirmRotate()
        runCurrent()

        assertEquals(0, repository.rotateFeedCalls)

        holder.clear()
    }

    @Test
    fun `a failed load reports an error`() = runTest {
        val repository = FakeRepository(feedsResult = Result.failure(IllegalStateException("boom")))
        val holder = holder(repository)

        holder.ensureLoaded()
        runCurrent()

        assertNotNull(holder.state.value.error)
        assertFalse(holder.state.value.unsupported)
        assertFalse(holder.state.value.loading)

        holder.clear()
    }

    private fun kotlinx.coroutines.test.TestScope.holder(
        repository: FakeRepository,
        clipboard: ClipboardWriter = ClipboardWriter { },
    ) = PodcastFeedsStateHolder(repository, clipboard, UnconfinedTestDispatcher(testScheduler))
}
