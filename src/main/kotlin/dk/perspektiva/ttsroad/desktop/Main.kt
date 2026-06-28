package dk.perspektiva.ttsroad.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dk.perspektiva.ttsroad.desktop.ui.TtsRoadTheme

fun main() = application {
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
