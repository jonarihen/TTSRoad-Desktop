package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PodcastFeedsTest {

    @Test
    fun `the combined feed comes before OPML`() {
        val links = accountFeedLinks(
            LibraryFeedUrls(feedUrl = "https://x/feed?t=a", opmlUrl = "https://x/opml?t=a"),
        )

        assertContentEquals(
            listOf("Combined feed", "OPML"),
            links.map { it.label },
            "the combined feed is what most people want; OPML is the bulk-import case",
        )
    }

    @Test
    fun `a missing url is dropped rather than shown as a blank row`() {
        assertEquals(
            listOf("Combined feed"),
            accountFeedLinks(LibraryFeedUrls(feedUrl = "https://x/feed", opmlUrl = null)).map { it.label },
        )
        assertEquals(
            listOf("OPML"),
            accountFeedLinks(LibraryFeedUrls(feedUrl = "  ", opmlUrl = "https://x/opml")).map { it.label },
        )
        assertTrue(accountFeedLinks(null).isEmpty())
        assertTrue(accountFeedLinks(LibraryFeedUrls()).isEmpty())
    }

    @Test
    fun `a fiction with no url is dropped`() {
        val links = fictionFeedLinks(
            listOf(
                FictionFeedUrl(fictionId = 1, title = "Wandering Inn", feedUrl = "https://x/1"),
                FictionFeedUrl(fictionId = 2, title = "No URL", feedUrl = null),
                FictionFeedUrl(fictionId = 3, title = "Blank", feedUrl = ""),
            ),
        )

        assertEquals(listOf("Wandering Inn"), links.map { it.label })
    }

    @Test
    fun `an untitled fiction still gets a label`() {
        val links = fictionFeedLinks(listOf(FictionFeedUrl(fictionId = 1, title = "", feedUrl = "https://x/1")))

        assertEquals("Untitled fiction", links.single().label)
    }

    @Test
    fun `every link is marked secret`() {
        val account = accountFeedLinks(LibraryFeedUrls(feedUrl = "https://x/f", opmlUrl = "https://x/o"))
        val fictions = fictionFeedLinks(listOf(FictionFeedUrl(fictionId = 1, title = "A", feedUrl = "https://x/1")))

        // The token in the URL is the whole authorization, so nothing here may reach a log.
        assertTrue((account + fictions).all { it.secret })
    }

    @Test
    fun `the rotate warning names the consequence and the exception`() {
        assertTrue(RotateFeedConfirmation.contains("stops receiving episodes"))
        assertTrue(
            RotateFeedConfirmation.contains("Per-fiction feeds are not affected"),
            "the two lists sit next to each other, so the account rotate looks like it covers both",
        )
    }

    @Test
    fun `the clipboard writer is a seam that receives the url verbatim`() {
        val written = mutableListOf<String>()
        val writer = ClipboardWriter { written += it }

        writer.write("https://x/feed?token=secret")

        assertEquals(listOf("https://x/feed?token=secret"), written)
    }
}
