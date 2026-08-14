package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File

/** One attached display, in the same virtual-desktop coordinate space as a window position. */
data class ScreenBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/**
 * The safe-to-persist part of the window's appearance and behaviour.
 *
 * Everything here is a fact about the window itself — its geometry, and what its own close control
 * does. Nothing transient (open dialogs, the current destination, a half-typed search) and nothing
 * secret is representable in this type at all, which is stronger than remembering not to write it:
 * a future field cannot leak a credential into `window.json` by accident because there is nowhere
 * to put one.
 *
 * A null [x]/[y] means "let the window system place it" — that is what an unusable saved position
 * degrades to, rather than a window opening off-screen.
 */
data class WindowPlacement(
    val x: Int? = null,
    val y: Int? = null,
    val width: Int = WindowPlacements.DefaultWidth,
    val height: Int = WindowPlacements.DefaultHeight,
    val isMaximized: Boolean = false,
    val sidebarWidth: Int = WindowPlacements.DefaultSidebarWidth,
    /**
     * Whether closing the window keeps playing in the tray instead of quitting.
     *
     * Defaults to **off**, which is the answer that cannot surprise anyone: a close control that
     * closes is what every user already expects, and a listener who wants the other behaviour has
     * a reason to go and ask for it. The reverse default would leave a process running, holding a
     * media session, for people who believed they had quit.
     */
    val closeToTray: Boolean = false,
    /** Whether the "still playing" notice has already been shown for a close-to-tray. */
    val trayNoticeShown: Boolean = false,
)

/**
 * Geometry from this run's window, behaviour from whatever the file says **now**.
 *
 * The two halves of `window.json` are written by different things at different times: geometry is
 * captured once, as the window closes, against the placement loaded at startup, while Settings can
 * flip the close behaviour at any point in between. Saving the startup snapshot wholesale would
 * quietly undo a setting the user changed five minutes ago.
 */
fun WindowPlacement.withBehaviourOf(latest: WindowPlacement): WindowPlacement =
    copy(closeToTray = latest.closeToTray, trayNoticeShown = latest.trayNoticeShown)

object WindowPlacements {
    /**
     * The supported minimum window size.
     *
     * Below this the header, the transport controls and a one-column library stop fitting without
     * clipping, so the window refuses to go smaller rather than rendering something broken.
     */
    const val MinWidth: Int = 720
    const val MinHeight: Int = 560

    const val DefaultWidth: Int = 1140
    const val DefaultHeight: Int = 780

    const val MinSidebarWidth: Int = 160
    const val MaxSidebarWidth: Int = 420
    const val DefaultSidebarWidth: Int = 220

    /** How much of the window must remain visible for a saved position to be worth restoring. */
    private const val MinVisibleWidth: Int = 120
    private const val MinVisibleHeight: Int = 48

    /**
     * Makes a saved placement safe for the displays that are attached **now**.
     *
     * This is the whole reason the placement is not simply handed back to the window system. A
     * laptop undocked since the last run, a rotated monitor, or a screen that used to sit at
     * x = 1920 and no longer exists all produce a stored position that is somewhere no user can
     * reach — a window that "did not open" is indistinguishable from a crash.
     *
     * Rules, in order:
     * 1. Size is clamped to at least the supported minimum and at most the target display.
     * 2. The target display is the one the saved rectangle overlaps most; with no saved position,
     *    or with no overlap anywhere, it is the first (primary) display.
     * 3. A position with almost nothing visible is discarded entirely rather than dragged back —
     *    the window system's own placement is a better answer than an arbitrary corner.
     * 4. Otherwise the position is shifted just far enough to fit inside the target display.
     */
    fun clampToDisplays(placement: WindowPlacement, displays: List<ScreenBounds>): WindowPlacement {
        val sidebarWidth = placement.sidebarWidth.coerceIn(MinSidebarWidth, MaxSidebarWidth)
        if (displays.isEmpty()) {
            return placement.copy(
                x = null,
                y = null,
                width = placement.width.coerceAtLeast(MinWidth),
                height = placement.height.coerceAtLeast(MinHeight),
                sidebarWidth = sidebarWidth,
            )
        }

        val target = displays.maxByOrNull { visibleArea(placement, it) }
            ?.takeIf { visibleArea(placement, it) > 0 }
            ?: displays.first()

        val width = placement.width.coerceIn(MinWidth, maxOf(MinWidth, target.width))
        val height = placement.height.coerceIn(MinHeight, maxOf(MinHeight, target.height))

        val x = placement.x
        val y = placement.y
        if (x == null || y == null || !isUsablyVisible(placement, target)) {
            return placement.copy(
                x = null,
                y = null,
                width = width,
                height = height,
                sidebarWidth = sidebarWidth,
            )
        }

        return placement.copy(
            x = x.coerceIn(target.x, maxOf(target.x, target.right - width)),
            y = y.coerceIn(target.y, maxOf(target.y, target.bottom - height)),
            width = width,
            height = height,
            sidebarWidth = sidebarWidth,
        )
    }

    private fun overlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int =
        (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0)

    private fun visibleWidth(placement: WindowPlacement, display: ScreenBounds): Int {
        val x = placement.x ?: return 0
        return overlap(x, x + placement.width, display.x, display.right)
    }

    private fun visibleHeight(placement: WindowPlacement, display: ScreenBounds): Int {
        val y = placement.y ?: return 0
        return overlap(y, y + placement.height, display.y, display.bottom)
    }

    private fun visibleArea(placement: WindowPlacement, display: ScreenBounds): Long =
        visibleWidth(placement, display).toLong() * visibleHeight(placement, display).toLong()

    private fun isUsablyVisible(placement: WindowPlacement, display: ScreenBounds): Boolean =
        visibleWidth(placement, display) >= MinVisibleWidth &&
            visibleHeight(placement, display) >= MinVisibleHeight
}

/** Seam so tests never touch the real user config directory. */
interface WindowPreferencesStore {
    fun load(): WindowPlacement
    fun save(placement: WindowPlacement)
}

/**
 * `window.json` beside the session settings, written owner-only through [SecureFiles].
 *
 * A read or write failure is deliberately silent-but-logged: losing the remembered window size is
 * not worth failing startup or blocking shutdown over.
 */
class FileWindowPreferencesStore(
    private val file: File = defaultFile(),
) : WindowPreferencesStore {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(WindowPlacement::class.java)

    override fun load(): WindowPlacement =
        runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .onFailure { AppLog.warn("could not read the window settings file", it) }
            .getOrNull()
            ?: WindowPlacement()

    override fun save(placement: WindowPlacement) {
        runCatching { SecureFiles.writeAtomically(file, adapter.toJson(placement)) }
            .onFailure { AppLog.warn("could not write the window settings file", it) }
    }

    companion object {
        fun defaultFile(): File = FileSessionStore.configDir().resolve("window.json")
    }
}
