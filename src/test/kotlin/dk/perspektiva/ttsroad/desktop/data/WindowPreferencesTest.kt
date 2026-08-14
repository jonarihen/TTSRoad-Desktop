package dk.perspektiva.ttsroad.desktop.data

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Window placement is remembered across restarts, which means a saved rectangle can outlive the
 * monitor it was saved against. Every case below is a real way that goes wrong.
 */
class WindowPreferencesTest {

    private val primary = ScreenBounds(0, 0, 1920, 1080)
    private val secondaryLeft = ScreenBounds(-1920, 0, 1920, 1080)

    // --- Clamping ------------------------------------------------------------------------------

    @Test
    fun `a placement fully inside a display is kept as it is`() {
        val placement = WindowPlacement(x = 100, y = 80, width = 1200, height = 800)

        assertEquals(placement, WindowPlacements.clampToDisplays(placement, listOf(primary)))
    }

    @Test
    fun `a window saved on a monitor that is gone loses its position rather than opening offscreen`() {
        // The classic laptop-undocked case: the second display sat at x = 1920 and no longer
        // exists, so the stored position points at nothing a user can reach.
        val placement = WindowPlacement(x = 2400, y = 200, width = 1200, height = 800)

        val clamped = WindowPlacements.clampToDisplays(placement, listOf(primary))

        assertNull(clamped.x)
        assertNull(clamped.y)
        assertEquals(1200, clamped.width, "only the position was unusable, not the size")
    }

    @Test
    fun `a window hanging a little off the right edge is shifted back in`() {
        val placement = WindowPlacement(x = 1800, y = 100, width = 1200, height = 800)

        val clamped = WindowPlacements.clampToDisplays(placement, listOf(primary))

        assertEquals(1920 - 1200, clamped.x)
        assertEquals(100, clamped.y)
    }

    @Test
    fun `a window with only a sliver visible is treated as unreachable`() {
        // 40 px of title bar is not something a user can grab reliably.
        val placement = WindowPlacement(x = 1880, y = 1060, width = 1200, height = 800)

        val clamped = WindowPlacements.clampToDisplays(placement, listOf(primary))

        assertNull(clamped.x)
        assertNull(clamped.y)
    }

    @Test
    fun `a display to the left of the origin is a valid place to be`() {
        // Negative coordinates are normal on a multi-monitor desktop and must not read as invalid.
        val placement = WindowPlacement(x = -1800, y = 100, width = 1200, height = 800)

        val clamped = WindowPlacements.clampToDisplays(placement, listOf(primary, secondaryLeft))

        assertEquals(-1800, clamped.x)
        assertEquals(100, clamped.y)
    }

    @Test
    fun `a size below the supported minimum is raised to it`() {
        val clamped = WindowPlacements.clampToDisplays(
            WindowPlacement(x = 0, y = 0, width = 320, height = 200),
            listOf(primary),
        )

        assertEquals(WindowPlacements.MinWidth, clamped.width)
        assertEquals(WindowPlacements.MinHeight, clamped.height)
    }

    @Test
    fun `a size larger than the display is capped to it`() {
        val small = ScreenBounds(0, 0, 1280, 800)

        val clamped = WindowPlacements.clampToDisplays(
            WindowPlacement(x = 0, y = 0, width = 3840, height = 2160),
            listOf(small),
        )

        assertEquals(1280, clamped.width)
        assertEquals(800, clamped.height)
    }

    @Test
    fun `a display smaller than the supported minimum still gets a usable window`() {
        val tiny = ScreenBounds(0, 0, 400, 300)

        val clamped = WindowPlacements.clampToDisplays(WindowPlacement(), listOf(tiny))

        assertEquals(WindowPlacements.MinWidth, clamped.width)
        assertEquals(WindowPlacements.MinHeight, clamped.height)
    }

    @Test
    fun `with no displays at all the window system is left to place it`() {
        val clamped = WindowPlacements.clampToDisplays(
            WindowPlacement(x = 100, y = 100, width = 1200, height = 800),
            emptyList(),
        )

        assertNull(clamped.x)
        assertNull(clamped.y)
        assertEquals(1200, clamped.width)
    }

    @Test
    fun `the sidebar width is clamped into a usable range`() {
        val tooNarrow = WindowPlacements.clampToDisplays(
            WindowPlacement(sidebarWidth = 10),
            listOf(primary),
        )
        val tooWide = WindowPlacements.clampToDisplays(
            WindowPlacement(sidebarWidth = 4000),
            listOf(primary),
        )

        assertEquals(WindowPlacements.MinSidebarWidth, tooNarrow.sidebarWidth)
        assertEquals(WindowPlacements.MaxSidebarWidth, tooWide.sidebarWidth)
    }

    @Test
    fun `the maximised flag survives clamping`() {
        val clamped = WindowPlacements.clampToDisplays(
            WindowPlacement(x = 0, y = 0, isMaximized = true),
            listOf(primary),
        )

        assertTrue(clamped.isMaximized)
    }

    @Test
    fun `the display with the most of the window on it wins`() {
        val placement = WindowPlacement(x = -1900, y = 0, width = 1200, height = 800)

        val x = WindowPlacements.clampToDisplays(placement, listOf(primary, secondaryLeft)).x

        assertNotNull(x)
        assertTrue(x < 0, "it belongs on the left-hand display, not dragged onto the primary")
    }

    // --- Persistence ---------------------------------------------------------------------------

    @Test
    fun `a placement round-trips through the file store`() {
        val file = Files.createTempDirectory("ttsroad-window").resolve("window.json").toFile()
        val store = FileWindowPreferencesStore(file)
        val placement = WindowPlacement(x = 12, y = 34, width = 1000, height = 700, isMaximized = true, sidebarWidth = 260)

        store.save(placement)

        assertEquals(placement, FileWindowPreferencesStore(file).load())
    }

    @Test
    fun `only layout facts reach the disk`() {
        // The type has no field for anything transient or secret, and this pins that the file the
        // type produces contains nothing else either.
        val file = Files.createTempDirectory("ttsroad-window").resolve("window.json").toFile()
        FileWindowPreferencesStore(file).save(WindowPlacement(x = 1, y = 2))

        val json = file.readText()

        listOf("token", "password", "credential", "dialog", "destination", "search").forEach {
            assertFalse(json.contains(it, ignoreCase = true), "window.json must not carry '$it': $json")
        }
    }

    @Test
    fun `a missing or unreadable file is a default placement, not a crash`() {
        val directory = Files.createTempDirectory("ttsroad-window").toFile()
        val missing = FileWindowPreferencesStore(directory.resolve("window.json"))

        val corrupt = directory.resolve("corrupt.json")
        corrupt.writeText("{ this is not json")

        assertEquals(WindowPlacement(), missing.load())
        assertEquals(WindowPlacement(), FileWindowPreferencesStore(corrupt).load())
    }

    // --- Window behaviour, written by a different hand than the geometry -------------------------

    @Test
    fun `closing quits by default, so nobody is left with a process they thought they closed`() {
        assertFalse(WindowPlacement().closeToTray)
        assertFalse(WindowPlacement().trayNoticeShown)
    }

    @Test
    fun `saving this run's geometry does not undo a close preference changed since startup`() {
        // Geometry is captured once, against the placement loaded at startup; Settings can flip the
        // close behaviour at any point in between. Writing the startup snapshot wholesale would
        // silently revert it.
        val loadedAtStartup = WindowPlacement(x = 10, y = 20, width = 1000, height = 700)
        val onDiskNow = loadedAtStartup.copy(closeToTray = true, trayNoticeShown = true)
        val closingGeometry = loadedAtStartup.copy(x = 400, y = 300)

        val saved = closingGeometry.withBehaviourOf(onDiskNow)

        assertEquals(400, saved.x)
        assertEquals(300, saved.y)
        assertTrue(saved.closeToTray)
        assertTrue(saved.trayNoticeShown)
    }

    @Test
    fun `clamping a window back onto an attached display leaves its behaviour alone`() {
        val stored = WindowPlacement(x = 9_000, y = 9_000, closeToTray = true, trayNoticeShown = true)

        val clamped = WindowPlacements.clampToDisplays(stored, listOf(ScreenBounds(0, 0, 1920, 1080)))

        assertTrue(clamped.closeToTray)
        assertTrue(clamped.trayNoticeShown)
    }
}
