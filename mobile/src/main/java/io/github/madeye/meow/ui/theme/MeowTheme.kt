package io.github.madeye.meow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Meow has a fixed brand identity shared with the iOS app, so it deliberately
 * does **not** use Material You dynamic color. Please don't add
 * `dynamicLightColorScheme`/`dynamicDarkColorScheme` here — the palette is the
 * product, not a system preference.
 *
 * The role mapping below is ported from the Flutter theme it replaces
 * (`flutter_module/lib/theme/app_theme.dart`), which had already hand-tuned
 * every role away from the seed algorithm's output.
 */
@Composable
fun MeowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val meow = if (darkTheme) MeowDarkColors else MeowLightColors
    CompositionLocalProvider(LocalMeowColors provides meow) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(meow) else lightScheme(meow),
            typography = MeowTypography,
            shapes = MeowShapes,
            content = content,
        )
    }
}

/** `MaterialTheme.meow.accent` — brand tokens alongside the M3 roles. */
val MaterialTheme.meow: MeowColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMeowColors.current

private fun lightScheme(c: MeowColors): ColorScheme = lightColorScheme(
    primary = c.accent,
    onPrimary = Color.White,
    primaryContainer = c.panelRaised,
    onPrimaryContainer = Color(0xFF00344F),
    secondary = c.ginger,
    onSecondary = Color(0xFF3F2500),
    secondaryContainer = Color(0xFFFFE3BF),
    onSecondaryContainer = Color(0xFF3A2100),
    tertiary = c.navy,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8E2FF),
    onTertiaryContainer = Color(0xFF001849),
    error = c.danger,
    onError = Color.White,
    background = c.canvas,
    onBackground = c.ink,
    surface = c.panel,
    onSurface = c.ink,
    surfaceContainerHighest = c.panelRaised,
    onSurfaceVariant = c.mutedText,
    outline = Color(0xFF5A7484),
    outlineVariant = c.border,
)

private fun darkScheme(c: MeowColors): ColorScheme = darkColorScheme(
    primary = c.accent,
    onPrimary = Color(0xFF00344F),
    primaryContainer = c.panelRaised,
    onPrimaryContainer = Color(0xFFCBE8FF),
    secondary = c.ginger,
    onSecondary = Color(0xFF3F2500),
    secondaryContainer = Color(0xFF5B3A00),
    onSecondaryContainer = Color(0xFFFFDDB0),
    tertiary = Color(0xFF9CC5FF),
    onTertiary = Color(0xFF00297A),
    tertiaryContainer = Color(0xFF003AA0),
    onTertiaryContainer = Color(0xFFD8E2FF),
    error = c.danger,
    onError = Color(0xFF5C0009),
    background = c.canvas,
    onBackground = c.ink,
    surface = c.panel,
    onSurface = c.ink,
    surfaceContainerHighest = c.panelRaised,
    onSurfaceVariant = c.mutedText,
    outline = Color(0xFF8AA6B8),
    outlineVariant = c.border,
)
