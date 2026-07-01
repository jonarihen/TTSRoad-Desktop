package dk.perspektiva.ttsroad.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dk.perspektiva.ttsroad.desktop.ui.TtsRoadTheme

fun main() {
    SingletonImageLoader.setSafe {
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()
    }
    application {
        val state = rememberWindowState(width = 1140.dp, height = 780.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = "TTSRoad",
            state = state,
        ) {
            TtsRoadTheme {
                App()
            }
        }
    }
}
