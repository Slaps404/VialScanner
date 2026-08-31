package org.lji.vialscanner

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The visual language, in one place - the Kotlin half of `App/Theme.swift`.
 *
 * The reference is the ChatGPT iOS app: a near-white page, one weight of
 * hairline border, circular icon buttons in the corners, capsule controls at
 * the bottom, and colour reserved almost entirely for state. Nothing here is
 * decorative: in a freezer room with gloves on, contrast and target size are
 * the whole design.
 *
 * Every hex below is copied from the Swift file rather than eyeballed. When one
 * side changes a colour the other is now visibly wrong, which is the point: two
 * palettes that merely resemble each other drift, and nobody notices until the
 * two apps are held side by side.
 */
data class Palette(
    /** Page background. */
    val bg: Color,
    /** Cards, sheets, raised surfaces. */
    val surface: Color,
    /** Inset fills: pills, search fields, list rows. */
    val fill: Color,
    val fillStrong: Color,
    val hairline: Color,
    val text: Color,
    val textSecond: Color,
    val textFaint: Color,
    /** Primary action. Black on white, white on black. */
    val solid: Color,
    val onSolid: Color,
    val accent: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
)

private val LightPalette = Palette(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    fill = Color(0xFFF4F4F5),
    fillStrong = Color(0xFFECECEE),
    hairline = Color(0xFFE5E5E7),
    text = Color(0xFF0D0D0D),
    textSecond = Color(0xFF8E8EA0),
    textFaint = Color(0xFFB4B4C0),
    solid = Color(0xFF0D0D0D),
    onSolid = Color(0xFFFFFFFF),
    accent = Color(0xFF2F6FED),
    good = Color(0xFF1E9E62),
    warn = Color(0xFFD98416),
    bad = Color(0xFFD2453C),
)

private val DarkPalette = Palette(
    bg = Color(0xFF0D0D0D),
    surface = Color(0xFF1B1B1D),
    fill = Color(0xFF2A2A2D),
    fillStrong = Color(0xFF343437),
    hairline = Color(0xFF323235),
    text = Color(0xFFECECEC),
    textSecond = Color(0xFF9B9BA6),
    textFaint = Color(0xFF6E6E78),
    solid = Color(0xFFECECEC),
    onSolid = Color(0xFF0D0D0D),
    accent = Color(0xFF5C8FF6),
    good = Color(0xFF35C486),
    warn = Color(0xFFE8A33D),
    bad = Color(0xFFEE6A61),
)

/** The orb gradient from the voice screen, used for the scanning state. Not
 *  theme-dependent on iOS either. */
val OrbTop = Color(0xFF6B7BF7)
val OrbBottom = Color(0xFFC6CEFF)

/**
 * The live palette.
 *
 * Swift gets this for free: `UIColor { traits in ... }` resolves per view, so
 * `Theme.bg` is a constant that happens to know about dark mode. Compose has no
 * equivalent, and the alternative - a `CompositionLocal` read as
 * `VialTheme.colors.bg` - would have meant touching every call site in the app
 * to gain nothing visible. Backing it with snapshot state keeps `Ink.bg`
 * working *and* recomposes on a theme switch, because a snapshot read inside a
 * composable subscribes to it.
 *
 * The one rule: only `VialTheme` writes this.
 */
object Ink {
    internal var palette by mutableStateOf(DarkPalette)

    val bg: Color get() = palette.bg
    val surface: Color get() = palette.surface
    val fill: Color get() = palette.fill
    val fillStrong: Color get() = palette.fillStrong
    val hairline: Color get() = palette.hairline
    val text: Color get() = palette.text
    val textSecond: Color get() = palette.textSecond
    val textFaint: Color get() = palette.textFaint
    val solid: Color get() = palette.solid
    val onSolid: Color get() = palette.onSolid
    val accent: Color get() = palette.accent
    val good: Color get() = palette.good
    val warn: Color get() = palette.warn
    val bad: Color get() = palette.bad

    // Names the screens already use, kept so the port did not have to rename
    // every row and card in the same commit that introduced light mode.
    /** Cards and raised surfaces. */
    val card: Color get() = palette.surface
    /** Hairline rules and dividers. */
    val line: Color get() = palette.hairline
    /** Secondary text. */
    val dim: Color get() = palette.textSecond
    /** Informational state; iOS uses the accent for "new vial". */
    val info: Color get() = palette.accent
}

/** Metrics, matching `Theme.radius` / `cardRadius` / `pad`. */
object Metric {
    val radius = 18.dp
    val cardRadius = 22.dp
    val pad = 20.dp
}

/**
 * The type scale, matching Swift's `Theme.display` ... `Theme.mono`.
 *
 * Sizes are the same numbers as the Swift file. iOS points and Android sp are
 * not identical units, but at default text scale they land within a pixel of
 * each other, and honouring sp keeps the phone's font-size accessibility
 * setting working - which a hard dp would quietly break.
 */
object Type {
    val display = 30.sp
    val title = 22.sp
    val heading = 17.sp
    val body = 16.sp
    val label = 14.sp
    val caption = 12.sp
    val mono = 15.sp

    val displayWeight = FontWeight.Bold
    val titleWeight = FontWeight.SemiBold
    val headingWeight = FontWeight.SemiBold
    val labelWeight = FontWeight.Medium
    val monoFamily = FontFamily.Monospace
}

/**
 * Wraps the app, picks the palette from the system setting and publishes it.
 *
 * iOS says `.preferredColorScheme(nil)`, i.e. follow the system, so this does
 * the same rather than pinning dark.
 */
@Composable
fun VialTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    SideEffect { Ink.palette = palette }

    // Material's own scheme still shows through in the few places the app uses
    // stock components (text fields, chips, the snackbar), so point it at the
    // same colours instead of letting the purple default leak in.
    val scheme = if (dark) {
        darkColorScheme(
            background = palette.bg, surface = palette.surface,
            onBackground = palette.text, onSurface = palette.text,
            primary = palette.solid, onPrimary = palette.onSolid,
            secondary = palette.accent, error = palette.bad,
            surfaceVariant = palette.fill, onSurfaceVariant = palette.textSecond,
            outline = palette.hairline,
        )
    } else {
        lightColorScheme(
            background = palette.bg, surface = palette.surface,
            onBackground = palette.text, onSurface = palette.text,
            primary = palette.solid, onPrimary = palette.onSolid,
            secondary = palette.accent, error = palette.bad,
            surfaceVariant = palette.fill, onSurfaceVariant = palette.textSecond,
            outline = palette.hairline,
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(Metric.radius),
            large = androidx.compose.foundation.shape.RoundedCornerShape(Metric.cardRadius),
        ),
        content = content,
    )
}
