package io.github.madeye.meow.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Brand tokens ported from meow-ios (`App/Sources/Views/GlassCard.swift`,
 * `enum AppTheme`), sampled from the peeking-cat app icon: sky blue, white
 * ledge, ginger cat.
 *
 * These carry the semantics Material 3 has no role for — the card/canvas
 * split, the connected/upload/download traffic colors, the hairline card
 * border. Roles that *do* map onto M3 (primary, surface, error, …) live in the
 * [androidx.compose.material3.ColorScheme] built in `MeowTheme.kt` instead, so
 * stock components pick up the brand without per-call-site restyling.
 */
@Immutable
data class MeowColors(
    val canvas: Color,
    val canvasGradientEnd: Color,
    val panel: Color,
    val panelRaised: Color,
    val border: Color,
    val accent: Color,
    val navy: Color,
    val ginger: Color,
    val connected: Color,
    val warning: Color,
    val danger: Color,
    val mutedText: Color,
    val ink: Color,
) {
    /** Upload is always amber and download always brand blue — matches the iOS charts. */
    val upload: Color get() = warning
    val download: Color get() = accent

    /** The vertical wash every screen sits on. */
    val screenBrush: Brush get() = Brush.verticalGradient(listOf(canvas, canvasGradientEnd))
}

val MeowLightColors = MeowColors(
    canvas = Color(0xFFEAF8FF),
    canvasGradientEnd = Color(0xFFF7FCFF),
    panel = Color(0xFFFFFFFF),
    panelRaised = Color(0xFFDDF3FF),
    border = Color(0xFFA8D8F0),
    accent = Color(0xFF0077CC),
    navy = Color(0xFF0049B8),
    ginger = Color(0xFFFE9B01),
    connected = Color(0xFF148742),
    warning = Color(0xFFF29A00),
    danger = Color(0xFFD9363E),
    mutedText = Color(0xFF526B7A),
    ink = Color(0xFF102A43),
)

val MeowDarkColors = MeowColors(
    canvas = Color(0xFF0B1720),
    canvasGradientEnd = Color(0xFF0E1C2A),
    panel = Color(0xFF152430),
    panelRaised = Color(0xFF1C3040),
    border = Color(0xFF2C4A60),
    accent = Color(0xFF2CB5FF),
    navy = Color(0xFF000000),
    ginger = Color(0xFFFFB340),
    connected = Color(0xFF33C377),
    warning = Color(0xFFFFB84D),
    danger = Color(0xFFFF6B70),
    mutedText = Color(0xFF9DB6C6),
    ink = Color(0xFFE8F2FA),
)

/**
 * `static` rather than plain [androidx.compose.runtime.compositionLocalOf]: the
 * value only changes on a light/dark flip, and everything recomposes then anyway.
 */
val LocalMeowColors = staticCompositionLocalOf<MeowColors> {
    error("LocalMeowColors accessed outside MeowTheme")
}
