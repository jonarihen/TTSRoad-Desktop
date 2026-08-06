package dk.perspektiva.ttsroad.desktop.nav

import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The back stack is plain Kotlin so its rules can be pinned here rather than inferred from a UI
 * test. Every case below is a navigation shape a real listening session produces.
 */
class AppNavigationTest {

    private fun fiction(id: Int, title: String = "Fiction $id") =
        Destination.Fiction(FictionSummary(id = id, title = title))

    // --- Keys ------------------------------------------------------------------------------

    @Test
    fun `a destination key identifies the screen, not the payload it was opened with`() {
        // The library and the chapters endpoint return different snapshots of the same fiction;
        // a fresher one must not read as a different screen.
        val stale = fiction(7, "A Test Serial")
        val fresh = Destination.Fiction(FictionSummary(id = 7, title = "A Test Serial", doneChapters = 9))

        assertEquals(stale.key, fresh.key)
        assertEquals("Fiction:7", stale.key)
    }

    @Test
    fun `a reader key never collides with the fiction of the same id`() {
        assertTrue(Destination.Reader(chapterId = 7, title = "Chapter 7").key != fiction(7).key)
    }

    @Test
    fun `every destination has a distinct key`() {
        val destinations = listOf(
            Destination.Library,
            fiction(7),
            Destination.Player,
            Destination.Reader(101, "Chapter 3"),
            Destination.Settings,
            Destination.Devices,
        )
        assertEquals(destinations.size, destinations.map { it.key }.toSet().size)
    }

    // --- Push / pop ------------------------------------------------------------------------

    @Test
    fun `opening a new destination pushes it`() {
        val stack = rootBackStack.navigateTo(fiction(7))

        assertEquals(listOf(Destination.Library, fiction(7)), stack)
        assertTrue(stack.canGoBack)
        assertEquals(fiction(7), stack.currentDestination)
    }

    @Test
    fun `re-opening the destination already on top changes nothing`() {
        val stack = rootBackStack.navigateTo(fiction(7))

        assertEquals(stack, stack.navigateTo(fiction(7)))
    }

    @Test
    fun `re-opening an already-open destination pops back to it instead of looping`() {
        // Fiction to player to fiction to player is an ordinary listening session; appending each
        // one would grow a stack whose Back walks a history the user never made.
        var stack = rootBackStack.navigateTo(fiction(7)).navigateTo(Destination.Player)
        stack = stack.navigateTo(fiction(7))

        assertEquals(listOf(Destination.Library, fiction(7)), stack)

        stack = stack.navigateTo(Destination.Player).navigateTo(fiction(7)).navigateTo(Destination.Player)
        assertEquals(3, stack.size, "the loop stays bounded: $stack")
    }

    @Test
    fun `popping walks back one entry at a time`() {
        val stack = rootBackStack.navigateTo(fiction(7)).navigateTo(Destination.Player)

        val afterOne = stack.popDestination()
        assertEquals(fiction(7), afterOne.currentDestination)

        val afterTwo = afterOne.popDestination()
        assertEquals(Destination.Library, afterTwo.currentDestination)
    }

    @Test
    fun `the root is never popped`() {
        assertEquals(rootBackStack, rootBackStack.popDestination())
        assertFalse(rootBackStack.canGoBack)
    }

    @Test
    fun `replacing the top swaps the screen without growing the stack`() {
        val stack = rootBackStack.navigateTo(Destination.Settings).replaceTop(Destination.Devices)

        assertEquals(listOf(Destination.Library, Destination.Devices), stack)
    }

    // --- Retained state release --------------------------------------------------------------

    @Test
    fun `popping reports the key whose retained state is now garbage`() {
        val before = rootBackStack.navigateTo(fiction(7))
        val after = before.popDestination()

        assertEquals(listOf("Fiction:7"), keysDroppedBy(before, after))
    }

    @Test
    fun `a key that survives the change is not dropped`() {
        val before = rootBackStack.navigateTo(fiction(7)).navigateTo(Destination.Player)
        val after = before.popDestination()

        assertEquals(listOf("Player"), keysDroppedBy(before, after))
    }

    // --- NavigationState ---------------------------------------------------------------------

    @Test
    fun `the live stack releases retained state for every destination it leaves`() {
        val dropped = mutableListOf<String>()
        val nav = NavigationState(onDestinationDropped = { dropped += it.toString() })

        nav.open(fiction(7))
        nav.open(Destination.Player)
        assertTrue(dropped.isEmpty(), "nothing left the stack yet")

        nav.back()
        nav.back()

        assertEquals(listOf("Player", "Fiction:7"), dropped)
        assertEquals(Destination.Library, nav.current)
    }

    @Test
    fun `popping back to an open destination releases everything above it`() {
        val dropped = mutableListOf<String>()
        val nav = NavigationState(onDestinationDropped = { dropped += it.toString() })
        nav.open(fiction(7))
        nav.open(Destination.Player)

        nav.open(fiction(7))

        assertEquals(listOf("Player"), dropped)
    }

    @Test
    fun `back at the root reports that it did nothing`() {
        val nav = NavigationState()

        assertFalse(nav.back())
        assertEquals(Destination.Library, nav.current)
    }

    @Test
    fun `a session ending drops every retained screen and returns to the library`() {
        val dropped = mutableListOf<String>()
        val nav = NavigationState(onDestinationDropped = { dropped += it.toString() })
        nav.open(fiction(7))
        nav.open(Destination.Settings)

        nav.resetToRoot()

        assertEquals(rootBackStack, nav.backStack)
        assertEquals(setOf("Fiction:7", "Settings"), dropped.toSet())
    }

    @Test
    fun `replacing the top releases the state of the screen it replaced`() {
        val dropped = mutableListOf<String>()
        val nav = NavigationState(onDestinationDropped = { dropped += it.toString() })
        nav.open(Destination.Settings)

        nav.replaceTop(Destination.Devices)

        assertEquals(listOf("Settings"), dropped)
        assertEquals(Destination.Devices, nav.current)
        assertTrue(nav.canGoBack, "Back still returns to the library, not to the pane just left")
    }
}
