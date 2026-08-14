package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.ui.graphics.Color
import dk.perspektiva.ttsroad.desktop.data.ReaderTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Pins the measurable half of the visual accessibility audit instead of relying on screenshots. */
class ThemeAccessibilityTest {
    @Test
    fun `every normal AARIS text token meets WCAG AA on every application surface`() {
        val foregrounds = mapOf(
            "ink" to AarisColor.Ink,
            "muted" to AarisColor.Muted,
            "dim" to AarisColor.Dim,
            "accent" to AarisColor.Accent,
            "success" to AarisColor.Ok,
            "warning" to AarisColor.Warning,
            "danger" to AarisColor.Danger,
        )
        val surfaces = mapOf(
            "background" to AarisColor.Bg,
            "card" to AarisColor.BgRaise,
            "hover" to AarisColor.BgHover,
            "input" to AarisColor.BgInput,
        )

        foregrounds.forEach { (foregroundName, foreground) ->
            surfaces.forEach { (surfaceName, surface) ->
                assertContrast(foregroundName, foreground, surfaceName, surface)
            }
        }
        assertContrast("filled-button ink", AarisColor.Bg, "accent", AarisColor.Accent)
        assertContrast("hovered filled-button ink", AarisColor.Bg, "accent hover", AarisColor.AccentHover)
    }

    @Test
    fun `reader ink muted copy and word accent meet WCAG AA in every theme`() {
        ReaderTheme.entries.forEach { theme ->
            val palette = readerPalette(theme)
            assertContrast("$theme reader ink", palette.ink, "$theme background", palette.background)
            assertContrast("$theme reader muted", palette.muted, "$theme background", palette.background)
            assertContrast("$theme reader accent", palette.accent, "$theme background", palette.background)
        }
    }

    private fun assertContrast(
        foregroundName: String,
        foreground: Color,
        backgroundName: String,
        background: Color,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            ratio >= MinimumNormalTextContrast,
            "$foregroundName on $backgroundName is ${"%.2f".format(ratio)}:1, below $MinimumNormalTextContrast:1",
        )
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = first.relativeLuminance()
        val secondLuminance = second.relativeLuminance()
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun Color.relativeLuminance(): Double =
        0.2126 * red.toDouble().linearChannel() +
            0.7152 * green.toDouble().linearChannel() +
            0.0722 * blue.toDouble().linearChannel()

    private fun Double.linearChannel(): Double =
        if (this <= 0.04045) this / 12.92 else ((this + 0.055) / 1.055).pow(2.4)

    private companion object {
        const val MinimumNormalTextContrast = 4.5
    }
}
