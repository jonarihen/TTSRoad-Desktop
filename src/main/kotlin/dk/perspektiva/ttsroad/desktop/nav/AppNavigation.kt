package dk.perspektiva.ttsroad.desktop.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.perspektiva.ttsroad.desktop.data.FictionSummary

/**
 * Everywhere the app can be.
 *
 * A destination carries the *arguments* a screen needs, never the screen's own state: search text,
 * scroll offsets and loaded data are retained separately (see [Destination.key] and the saveable
 * state holder in `App`), so a destination stays a cheap value that can be compared and re-created.
 *
 * [Fiction] carries the whole [FictionSummary] rather than an id so the detail header can paint
 * immediately from what the library already knows, instead of showing an empty frame while the
 * chapter list loads.
 */
sealed interface Destination {
    data object Library : Destination

    data class Fiction(val fiction: FictionSummary) : Destination

    data object Player : Destination

    /**
     * Read-along for one chapter.
     *
     * Carries a chapter id and a display title rather than a `ChapterSummary` because it is also
     * reachable from the player, where only the currently loaded media is known.
     *
     * A Reader entry is deliberately distinct from the Fiction entry with the same numeric id, so
     * its find query, follow state, and lazy-list position have their own retained lifetime.
     */
    data class Reader(val chapterId: Int, val title: String) : Destination

    /**
     * Server-side search results.
     *
     * Carries no query. The query and its results live in the hoisted search holder, so opening a
     * hit and coming back finds the list still there — and so re-opening this destination from the
     * library with a *new* query is not defeated by the pop-back-to-existing rule below, which
     * keeps the older entry's payload.
     */
    data object Search : Destination

    data object Settings : Destination

    data object Devices : Destination
}

/**
 * Stable identity of a destination.
 *
 * Two things key off this and both are why it exists: the back stack pops back to an *existing*
 * entry instead of stacking a second copy, and retained per-destination UI state (search text,
 * scroll position) is stored under this string. It deliberately ignores the payload — a fresher
 * [FictionSummary] for fiction 7 is still "the fiction 7 screen", not a new one.
 */
val Destination.key: String
    get() = when (this) {
        Destination.Library -> "Library"
        is Destination.Fiction -> "Fiction:${fiction.id}"
        Destination.Player -> "Player"
        is Destination.Reader -> "Reader:$chapterId"
        Destination.Search -> "Search"
        Destination.Settings -> "Settings"
        Destination.Devices -> "Devices"
    }

/** The stack a fresh — or freshly signed-in — session starts from. */
val rootBackStack: List<Destination> = listOf(Destination.Library)

val List<Destination>.currentDestination: Destination get() = last()

val List<Destination>.canGoBack: Boolean get() = size > 1

/**
 * Opens [destination], popping back to it when it is already open.
 *
 * The pop is the point. Library → Fiction → Player → Fiction → Player … is a normal listening
 * session; appending every one of those would grow an unbounded stack whose Back button walks
 * backwards through a history the user does not remember creating.
 */
fun List<Destination>.navigateTo(destination: Destination): List<Destination> {
    val existing = indexOfLast { it.key == destination.key }
    return when {
        existing == lastIndex && existing >= 0 -> this
        existing >= 0 -> subList(0, existing + 1).toList()
        else -> this + destination
    }
}

/** Pops one entry. The root is never popped, so the stack is never empty. */
fun List<Destination>.popDestination(): List<Destination> =
    if (size > 1) subList(0, size - 1).toList() else this

/**
 * Swaps the top entry for [destination] without growing the stack.
 *
 * Used where one screen changes what it *is* rather than navigating: picking the device-sessions
 * pane inside Settings turns the top entry from `Settings` into `Devices`, and Back still returns
 * to whatever was underneath rather than to the pane the user just left.
 */
fun List<Destination>.replaceTop(destination: Destination): List<Destination> =
    if (isEmpty()) listOf(destination) else dropLast(1) + destination

/**
 * Keys that exist in [previous] but not in [next] — i.e. whose retained UI state is now garbage.
 *
 * Without this, popping a fiction and opening a different one would restore the *first* fiction's
 * scroll offset into the second, and every screen ever visited would keep its state alive for the
 * lifetime of the process.
 */
fun keysDroppedBy(previous: List<Destination>, next: List<Destination>): List<String> {
    val kept = next.mapTo(HashSet()) { it.key }
    return previous.map { it.key }.filterNot { it in kept }.distinct()
}

/**
 * The live back stack.
 *
 * Compose snapshot state rather than a `StateFlow` so navigation is synchronous with the frame
 * that requested it; it is still plain Kotlin, so every rule above is exercised from a unit test
 * without a display.
 *
 * [onDestinationDropped] is invoked for every key that leaves the stack, which is how retained
 * screen state is released — see `rememberSaveableStateHolder` in `App`.
 */
class NavigationState(
    initial: List<Destination> = rootBackStack,
    private val onDestinationDropped: (Any) -> Unit = {},
) {
    var backStack: List<Destination> by mutableStateOf(initial)
        private set

    val current: Destination get() = backStack.currentDestination

    val canGoBack: Boolean get() = backStack.canGoBack

    fun open(destination: Destination) = applyStack(backStack.navigateTo(destination))

    fun back(): Boolean {
        if (!canGoBack) return false
        applyStack(backStack.popDestination())
        return true
    }

    fun replaceTop(destination: Destination) = applyStack(backStack.replaceTop(destination))

    /** Back to a single Library entry, dropping every retained screen. Used when the session ends. */
    fun resetToRoot() = applyStack(rootBackStack)

    private fun applyStack(next: List<Destination>) {
        val previous = backStack
        if (previous == next) return
        backStack = next
        keysDroppedBy(previous, next).forEach(onDestinationDropped)
    }
}
