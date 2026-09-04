package dk.perspektiva.ttsroad.desktop.data

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * The podcast URLs, and getting one onto the clipboard (#117).
 *
 * A private podcast feed is what this project is for, and this client could not show you its URL —
 * subscribing meant opening the web console in a browser. On a desktop the whole feature is a copy
 * button.
 *
 * **These strings are credentials.** The token in the URL is the entire authorization: anyone
 * holding it can read the feed without signing in. So nothing here goes near `AppLog`, which is
 * persistent, and no feed URL belongs in a window title, a notification or a diagnostics dump.
 */

/** One copyable row. [secret] marks the URLs that must never reach a log. */
data class FeedLink(
    val label: String,
    val detail: String,
    val url: String,
    val secret: Boolean = true,
)

/**
 * Writing to the system clipboard, as a seam.
 *
 * A `fun interface` rather than a direct AWT call so a holder can be driven from `runTest` without a
 * display, and so the "what was copied" assertion is about the value rather than about the platform.
 */
fun interface ClipboardWriter {
    fun write(text: String)
}

/** The real one. AWT rather than Compose's clipboard: this is a desktop app and it never composes. */
val SystemClipboardWriter: ClipboardWriter = ClipboardWriter { text ->
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

/**
 * The account's own two feeds, in the order they are useful.
 *
 * The combined feed first — it is the one most people want — then OPML, which is for importing
 * everything at once into an app that understands it.
 */
fun accountFeedLinks(library: LibraryFeedUrls?): List<FeedLink> {
    val feeds = library ?: return emptyList()
    return listOfNotNull(
        feeds.feedUrl?.takeIf { it.isNotBlank() }?.let {
            FeedLink(
                label = "Combined feed",
                detail = "Everything on your shelf, as one podcast.",
                url = it,
            )
        },
        feeds.opmlUrl?.takeIf { it.isNotBlank() }?.let {
            FeedLink(
                label = "OPML",
                detail = "Import every fiction as a separate podcast, in one go.",
                url = it,
            )
        },
    )
}

/** One row per fiction, dropping any the server sent without a URL. */
fun fictionFeedLinks(fictions: List<FictionFeedUrl>): List<FeedLink> = fictions.mapNotNull { row ->
    row.feedUrl?.takeIf { it.isNotBlank() }?.let {
        FeedLink(
            label = row.title.ifBlank { "Untitled fiction" },
            detail = "This fiction alone.",
            url = it,
        )
    }
}

/**
 * What rotating actually costs, for the confirmation.
 *
 * The name hides it: "rotate" sounds like housekeeping, and what it does is break every podcast app
 * already subscribed until each is given the new URL. It also does *not* cover the per-fiction
 * feeds, whose tokens come from the fiction rather than the account — saying so here is the only
 * place the distinction is visible, since the two lists sit next to each other.
 */
const val RotateFeedConfirmation: String =
    "Your combined feed and OPML URLs are replaced with new ones. Every podcast app already " +
        "subscribed stops receiving episodes until you give it the new URL. Per-fiction feeds are " +
        "not affected — those tokens belong to the fiction, not to your account."

/** The line after a successful rotate. */
const val RotatedFeedNotice: String =
    "New URLs issued. Re-subscribe anywhere you were using the old ones."
