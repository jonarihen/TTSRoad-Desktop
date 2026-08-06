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
import dk.perspektiva.ttsroad.desktop.data.WindowPlacement
import dk.perspektiva.ttsroad.desktop.data.WindowPlacements
import dk.perspektiva.ttsroad.desktop.di.AppContainer
import dk.perspektiva.ttsroad.desktop.ui.TtsRoadTheme
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.util.ServiceLoader
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.spi.AudioFileReader
import kotlin.system.exitProcess
import kotlinx.coroutines.delay

/**
 * `--smoke-test` boots the real window, renders one frame, then exits 0.
 *
 * CI runs the packaged jlink image with this flag under Xvfb, which is the only way to prove the
 * bundled runtime actually loads Skiko's native library and composes the UI — something no unit
 * test on the Gradle classpath can check.
 */
private const val SmokeTestFlag = "--smoke-test"

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
    val smokeTest = args.contains(SmokeTestFlag)

    // The one composition root. Owned by main() rather than by App() so it can be closed when
    // the window closes (stopping playback, deleting the temp file, draining the HTTP pools).
    val container = AppContainer()

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
        Window(
            onCloseRequest = {
                frame.get()?.let { container.windowPreferences.save(currentPlacement(it, restored)) }
                container.close()
                exitApplication()
            },
            title = "${BuildInfo.APP_NAME} ${BuildInfo.VERSION}",
            state = state,
        ) {
            // Applied through AWT rather than through `WindowState`, deliberately: `WindowState`
            // speaks Dp and the saved rectangle is device pixels, so round-tripping through it
            // would grow or shrink the window by the display's scale factor on every restart.
            LaunchedEffect(Unit) {
                frame.set(window)
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
                    println("${BuildInfo.APP_NAME} ${BuildInfo.VERSION} smoke test OK")
                    container.close()
                    exitApplication()
                }
            }
        }
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
