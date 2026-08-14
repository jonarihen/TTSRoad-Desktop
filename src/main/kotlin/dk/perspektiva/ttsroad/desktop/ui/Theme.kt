package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AARIS design language — dark, square, thin-bordered, orange-accent, mono-labelled
 * "operator console". Ported from the Android client so the desktop app reads as the
 * same product. Tokens mirror the web app's aaris.css.
 */
object AarisColor {
    val Bg = Color(0xFF0E1014)
    val BgRaise = Color(0xFF12151A)
    val BgHover = Color(0xFF1A1E25)
    val BgInput = Color(0xFF0B0D11)
    val Ink = Color(0xFFE9ECEF)
    val Muted = Color(0xFF8B939E)
    // Secondary text still has to be text. This is the lowest AARIS foreground that maintains
    // WCAG AA 4.5:1 against Bg, BgRaise, BgHover and BgInput; the old #4D545E was only 2.39:1 on
    // cards and made durations/status hints effectively decorative for low-vision listeners.
    val Dim = Color(0xFF808995)
    val Line = Color(0xFF232830)
    val LineSoft = Color(0xFF1A1E25)
    val Accent = Color(0xFFFF5A1F)
    val AccentHover = Color(0xFFFF7A44)
    val Ok = Color(0xFF3FD97F)
    val Warning = Color(0xFFFFB224)
    // Bright enough to keep error text above 4.5:1 even on the hover surface.
    val Danger = Color(0xFFEC555A)
}

val MonoFamily: FontFamily = FontFamily.Monospace

private val AarisColorScheme = darkColorScheme(
    primary = AarisColor.Accent,
    onPrimary = AarisColor.Bg,
    secondary = AarisColor.Accent,
    onSecondary = AarisColor.Bg,
    tertiary = AarisColor.Warning,
    onTertiary = AarisColor.Bg,
    background = AarisColor.Bg,
    onBackground = AarisColor.Ink,
    surface = AarisColor.BgRaise,
    onSurface = AarisColor.Ink,
    surfaceVariant = AarisColor.BgHover,
    onSurfaceVariant = AarisColor.Muted,
    outline = AarisColor.Line,
    outlineVariant = AarisColor.LineSoft,
    error = AarisColor.Danger,
    onError = AarisColor.Bg,
)

private val SquareCorner = RoundedCornerShape(0.dp)
private val AarisShapes = Shapes(
    extraSmall = SquareCorner,
    small = SquareCorner,
    medium = SquareCorner,
    large = SquareCorner,
    extraLarge = SquareCorner,
)

private val AarisTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp),
)

@Composable
fun TtsRoadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AarisColorScheme,
        shapes = AarisShapes,
        typography = AarisTypography,
        content = content,
    )
}

/** Mono uppercase "meta" label (section kickers, captions, status text). */
@Composable
fun MetaText(text: String, modifier: Modifier = Modifier, color: Color = AarisColor.Muted) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        style = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.3.sp),
    )
}

/** Mono uppercase bordered chip — the AARIS `.tag`. */
@Composable
fun AarisTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.border(1.dp, AarisColor.Line).padding(horizontal = 8.dp, vertical = 4.dp),
        color = AarisColor.Muted,
        style = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.2.sp),
    )
}

/**
 * Flat, thin-bordered panel — the AARIS `.panel`. Clickable cards get desktop hover feedback
 * (raised background, lightened border, hand cursor) like the web app's `.panel:hover`.
 */
@Composable
fun AarisCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onClick == null) {
        OutlinedCard(
            modifier = modifier,
            colors = CardDefaults.outlinedCardColors(containerColor = AarisColor.BgRaise, contentColor = AarisColor.Ink),
            border = BorderStroke(1.dp, AarisColor.Line),
        ) { content() }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Keyboard focus gets its own, stronger treatment: hover is discoverable with a mouse, but a
    // keyboard-only user has nothing at all to go on unless focus is drawn.
    val focused by interaction.collectIsFocusedAsState()
    val container by animateColorAsState(if (hovered || focused) AarisColor.BgHover else AarisColor.BgRaise)
    val borderColor by animateColorAsState(
        when {
            focused -> AarisColor.Accent
            hovered -> AarisColor.Dim
            else -> AarisColor.Line
        },
    )
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
        interactionSource = interaction,
        colors = CardDefaults.outlinedCardColors(containerColor = container, contentColor = AarisColor.Ink),
        border = BorderStroke(1.dp, borderColor),
    ) { content() }
}
