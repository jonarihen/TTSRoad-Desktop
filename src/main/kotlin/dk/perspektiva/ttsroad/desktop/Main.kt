package dk.perspektiva.ttsroad.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dk.perspektiva.ttsroad.desktop.di.AppContainer
import dk.perspektiva.ttsroad.desktop.ui.TtsRoadTheme
import java.util.ServiceLoader
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

    application {
        val state = rememberWindowState(width = 1140.dp, height = 780.dp)
        Window(
            onCloseRequest = {
                container.close()
                exitApplication()
            },
            title = "${BuildInfo.APP_NAME} ${BuildInfo.VERSION}",
            state = state,
        ) {
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
