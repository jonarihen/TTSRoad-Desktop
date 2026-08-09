package dk.perspektiva.ttsroad.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dk.perspektiva.ttsroad.desktop.data.ScreenBounds
import dk.perspektiva.ttsroad.desktop.data.AppLog
import dk.perspektiva.ttsroad.desktop.data.WindowPlacement
import dk.perspektiva.ttsroad.desktop.data.WindowPlacements
import dk.perspektiva.ttsroad.desktop.di.AppContainer
import dk.perspektiva.ttsroad.desktop.resources.Res
import dk.perspektiva.ttsroad.desktop.resources.ttsroad
import dk.perspektiva.ttsroad.desktop.ui.TtsRoadTheme
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.event.WindowEvent
import java.io.File
import java.util.ServiceLoader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JOptionPane
import javax.sound.sampled.spi.AudioFileReader
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

/**
 * `--smoke-test` boots the real window, renders one frame, then exits 0.
 *
 * CI runs the packaged jlink image with this flag under Xvfb, which is the only way to prove the
 * bundled runtime actually loads Skiko's native library and composes the UI — something no unit
 * test on the Gradle classpath can check.
 */
private const val SmokeTestFlag = "--smoke-test"
private const val SmokeTestEnvironment = "TTSROAD_SMOKE_TEST"
private const val SmokeServerEnvironment = "TTSROAD_SMOKE_SERVER_URL"

/**
 * MP3 decoding is provided by mp3spi/JLayer through a `META-INF/services` SPI registration, which
 * a minimised jlink image or a shaded jar can silently drop — the app would then start fine and
 * only fail the first time a user pressed play. Fail the smoke test instead.
 */
private fun verifyMp3SpiIsRegistered() {
    val readers = ServiceLoader.load(AudioFileReader::class.java).map { it.javaClass.name }
    println("javax.sound.sampled AudioFileReader providers: $readers")
    if (readers.none { it.contains("mpeg", ignoreCase = true) }) {
        System.err.println("FATAL: the MP3 AudioFileReader SPI is not registered in this runtime image")
        exitProcess(1)
    }
}

/**
 * The classes MPRIS needs that live outside the modules jlink infers.
 *
 * `com.sun.security.auth.module.UnixSystem` is in `jdk.security.auth` and is reached only by
 * reflection from dbus-java's unix-socket transport, so leaving that module out of the jlink list
 * produces the worst possible failure mode: the app starts, plays audio, and reports "no MPRIS
 * integration on this desktop" — indistinguishable from a machine that genuinely has no session
 * bus. Checked here rather than by connecting, because CI has no session bus and "no bus" must stay
 * a passing configuration while "no module" must not.
 *
 * Linux only: this is where MPRIS applies, and the class does not exist on Windows runtimes.
 */
private fun verifyMprisRuntimeModulesArePresent() {
    if (!System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) return
    val required = "com.sun.security.auth.module.UnixSystem"
    runCatching { Class.forName(required) }.onFailure {
        System.err.println("FATAL: $required is missing from this runtime image (jdk.security.auth)")
        exitProcess(1)
    }
    println("MPRIS runtime modules present: $required")
}

/**
 * The displays attached right now, in device pixels.
 *
 * Wrapped in `runCatching` because a headless JVM throws rather than returning nothing — and a
 * headless run (CI's `--smoke-test` under Xvfb is not one, but a build agent could be) must fall
 * back to "no displays", which the clamp reads as "let the window system place it".
 */
private fun attachedDisplays(): List<ScreenBounds> = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val bounds = device.defaultConfiguration.bounds
        ScreenBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    }
}.getOrElse { emptyList() }

fun main(args: Array<String>) {
    when {
        args.contains(VersionFlag) -> {
            println(versionText())
            return
        }

        args.contains(DiagnosticsFlag) -> {
            println(buildRuntimeDiagnostics())
            return
        }
    }

    val logFile = AppLog.configurePersistent()
    val crashReported = AtomicBoolean(false)
    Thread.setDefaultUncaughtExceptionHandler(
        terminatingUncaughtExceptionHandler(
            report = { thread, error -> reportFatalCrash(crashReported, logFile, thread, error) },
            terminate = ::exitProcess,
        ),
    )

    val smokeTest = args.contains(SmokeTestFlag) ||
        System.getenv(SmokeTestEnvironment).equals("1", ignoreCase = true)
    try {
        runDesktop(smokeTest)
    } catch (error: Throwable) {
        reportFatalCrash(crashReported, logFile, Thread.currentThread(), error)
        exitProcess(1)
    }
}

/**
 * A background-thread crash is process-fatal: after the report completes (including dismissal of
 * the desktop dialog), terminate rather than leaving Compose and possibly-corrupt shared state
 * running. The callbacks keep the ordering and the hard-termination fallback unit-testable.
 */
internal fun terminatingUncaughtExceptionHandler(
    report: (Thread, Throwable) -> Unit,
    terminate: (Int) -> Unit,
): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
    try {
        report(thread, error)
    } finally {
        terminate(1)
    }
}

private fun runDesktop(smokeTest: Boolean) {

    // The one composition root. Owned by main() rather than by App() so it can be closed when
    // the window closes (stopping playback, deleting the temp file, draining the HTTP pools).
    val container = AppContainer()
    // Package lifecycle CI supplies a tiny local capabilities server. Seeding only the non-secret
    // server hint makes the real login screen probe it after composition, proving the installed
    // launcher, OkHttp stack and login window work together without creating a fake signed-in
    // session. Ignored for every normal launch and for smoke tests without the explicit variable.
    if (smokeTest) {
        System.getenv(SmokeServerEnvironment)?.takeIf { it.isNotBlank() }?.let { serverUrl ->
            container.sessionStore.save(container.sessionStore.current().copy(serverUrl = serverUrl))
        }
    }

    // Coil reuses the app's single OkHttpClient instead of building a third one.
    SingletonImageLoader.setSafe {
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.httpClient })) }
            .build()
    }

    // Restored *and clamped* before the window exists: a position saved against a monitor that has
    // since been unplugged is a window nobody can reach, which is indistinguishable from a crash.
    val restored = WindowPlacements.clampToDisplays(
        container.windowPreferences.load(),
        attachedDisplays(),
    )

    // `onCloseRequest` runs outside the window's own scope, so the frame it has to measure is
    // captured from inside the content instead of reached for at close time.
    val frame = AtomicReference<Frame?>(null)

    application {
        val state = rememberWindowState()
        val appIcon = painterResource(Res.drawable.ttsroad)
        Window(
            onCloseRequest = {
                frame.get()?.let { container.windowPreferences.save(currentPlacement(it, restored)) }
                container.close()
                exitApplication()
            },
            title = "${BuildInfo.APP_NAME} ${BuildInfo.VERSION}",
            icon = appIcon,
            state = state,
        ) {
            // Applied through AWT rather than through `WindowState`, deliberately: `WindowState`
            // speaks Dp and the saved rectangle is device pixels, so round-tripping through it
            // would grow or shrink the window by the display's scale factor on every restart.
            LaunchedEffect(Unit) {
                frame.set(window)
                // The window exists now, so Raise and Quit have something to act on. Skipped
                // under --smoke-test: claiming a bus name in CI would leave a player advertised
                // for the two seconds before the process exits, for no coverage in return.
                if (!smokeTest) {
                    container.startMpris(
                        onRaise = {
                            window.toFront()
                            window.requestFocus()
                            if (window.extendedState == Frame.ICONIFIED) window.extendedState = Frame.NORMAL
                        },
                        onQuit = { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) },
                    )
                }
                window.minimumSize = Dimension(WindowPlacements.MinWidth, WindowPlacements.MinHeight)
                window.setSize(restored.width, restored.height)
                val x = restored.x
                val y = restored.y
                if (x != null && y != null) window.setLocation(x, y) else window.setLocationRelativeTo(null)
                if (restored.isMaximized) window.extendedState = Frame.MAXIMIZED_BOTH
            }

            TtsRoadTheme {
                App(container)
            }
            if (smokeTest) {
                LaunchedEffect(Unit) {
                    delay(2_000)
                    verifyMp3SpiIsRegistered()
                    verifyMprisRuntimeModulesArePresent()
                    println("${BuildInfo.APP_NAME} ${BuildInfo.VERSION} smoke test OK")
                    container.close()
                    // Gradle's runReleaseDistributable launches on its own Java classpath rather
                    // than through the native launcher. AWT/native service threads can outlive
                    // Compose in that mode, so the bounded smoke path must terminate explicitly.
                    exitProcess(0)
                }
            }
        }
    }
}

private fun reportFatalCrash(
    reported: AtomicBoolean,
    logFile: File,
    thread: Thread,
    error: Throwable,
) {
    if (!reported.compareAndSet(false, true)) return
    AppLog.crash(thread, error)
    val message = "TTSRoad could not continue. Details were written to ${logFile.absolutePath}."
    System.err.println(message)
    if (GraphicsEnvironment.isHeadless()) return

    runCatching {
        val show = Runnable {
            JOptionPane.showMessageDialog(
                null,
                message,
                "TTSRoad",
                JOptionPane.ERROR_MESSAGE,
            )
        }
        if (EventQueue.isDispatchThread()) show.run() else EventQueue.invokeAndWait(show)
    }
}

/**
 * What to remember about the window as it closes.
 *
 * A maximised window reports its maximised rectangle, which is not what it should be restored to
 * when the user un-maximises later — so the size and position that were loaded at startup are kept
 * instead, and only the maximised flag changes.
 */
private fun currentPlacement(window: Frame, loaded: WindowPlacement): WindowPlacement {
    val maximized = (window.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
    if (maximized) return loaded.copy(isMaximized = true)
    return loaded.copy(
        x = window.x,
        y = window.y,
        width = window.width,
        height = window.height,
        isMaximized = false,
    )
}
