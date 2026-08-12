import 'package:flutter/material.dart';

@immutable
class MeowColors extends ThemeExtension<MeowColors> {
  // Brand colors ported from meow-ios AppTheme (GlassCard.swift), sampled
  // from the peeking-cat app icon: sky blue, white ledge, ginger cat.
  static const brandAccent = Color(0xFF0077CC);
  static const brandAccentDark = Color(0xFF2CB5FF);
  static const brandGinger = Color(0xFFFE9B01);
  static const brandGingerDark = Color(0xFFFFB340);
  static const brandNavy = Color(0xFF0049B8);
  static const brandInk = Color(0xFF102A43);

  final Color canvas;
  final Color card;
  final Color connected;
  final Color upload;
  final Color download;

  const MeowColors({
    required this.canvas,
    required this.card,
    required this.connected,
    required this.upload,
    required this.download,
  });

  @override
  MeowColors copyWith({
    Color? canvas,
    Color? card,
    Color? connected,
    Color? upload,
    Color? download,
  }) {
    return MeowColors(
      canvas: canvas ?? this.canvas,
      card: card ?? this.card,
      connected: connected ?? this.connected,
      upload: upload ?? this.upload,
      download: download ?? this.download,
    );
  }

  @override
  MeowColors lerp(ThemeExtension<MeowColors>? other, double t) {
    if (other is! MeowColors) return this;
    return MeowColors(
      canvas: Color.lerp(canvas, other.canvas, t)!,
      card: Color.lerp(card, other.card, t)!,
      connected: Color.lerp(connected, other.connected, t)!,
      upload: Color.lerp(upload, other.upload, t)!,
      download: Color.lerp(download, other.download, t)!,
    );
  }
}

ThemeData buildAppTheme(Brightness brightness) {
  final isLight = brightness == Brightness.light;
  // Upload/download mirror the iOS traffic chart: Upload = warning amber,
  // Download = brand accent.
  final meow = isLight
      ? const MeowColors(
          canvas: Color(0xFFEAF8FF),
          card: Color(0xFFFFFFFF),
          connected: Color(0xFF148742),
          upload: Color(0xFFF29A00),
          download: MeowColors.brandAccent,
        )
      : const MeowColors(
          canvas: Color(0xFF0B1720),
          card: Color(0xFF152430),
          connected: Color(0xFF33C377),
          upload: Color(0xFFFFB84D),
          download: MeowColors.brandAccentDark,
        );

  final seeded = ColorScheme.fromSeed(
    seedColor: MeowColors.brandAccent,
    brightness: brightness,
    contrastLevel: isLight ? 0.35 : 0.25,
  );

  final scheme = seeded.copyWith(
    primary: isLight ? MeowColors.brandAccent : MeowColors.brandAccentDark,
    onPrimary: isLight ? Colors.white : const Color(0xFF00344F),
    primaryContainer: isLight
        ? const Color(0xFFDDF3FF)
        : const Color(0xFF1C3040),
    onPrimaryContainer: isLight
        ? const Color(0xFF00344F)
        : const Color(0xFFCBE8FF),
    secondary: isLight ? MeowColors.brandGinger : MeowColors.brandGingerDark,
    onSecondary: isLight ? const Color(0xFF3F2500) : const Color(0xFF3F2500),
    secondaryContainer: isLight
        ? const Color(0xFFFFE3BF)
        : const Color(0xFF5B3A00),
    onSecondaryContainer: isLight
        ? const Color(0xFF3A2100)
        : const Color(0xFFFFDDB0),
    tertiary: isLight ? MeowColors.brandNavy : const Color(0xFF9CC5FF),
    onTertiary: isLight ? Colors.white : const Color(0xFF00297A),
    tertiaryContainer: isLight
        ? const Color(0xFFD8E2FF)
        : const Color(0xFF003AA0),
    onTertiaryContainer: isLight
        ? const Color(0xFF001849)
        : const Color(0xFFD8E2FF),
    error: isLight ? const Color(0xFFD9363E) : const Color(0xFFFF6B70),
    onError: isLight ? Colors.white : const Color(0xFF5C0009),
    surface: meow.card,
    onSurface: isLight ? MeowColors.brandInk : const Color(0xFFE8F2FA),
    surfaceContainerHighest: isLight
        ? const Color(0xFFDDF3FF)
        : const Color(0xFF1C3040),
    onSurfaceVariant: isLight
        ? const Color(0xFF526B7A)
        : const Color(0xFF9DB6C6),
    outline: isLight ? const Color(0xFF5A7484) : const Color(0xFF8AA6B8),
    outlineVariant: isLight ? const Color(0xFFA8D8F0) : const Color(0xFF2C4A60),
  );

  final base = ThemeData(useMaterial3: true, brightness: brightness);

  return base.copyWith(
    colorScheme: scheme,
    scaffoldBackgroundColor: meow.canvas,
    extensions: [meow],
    appBarTheme: AppBarTheme(
      titleSpacing: 16,
      actionsPadding: const EdgeInsetsDirectional.only(end: 8),
      backgroundColor: meow.canvas,
      foregroundColor: scheme.onSurface,
      surfaceTintColor: Colors.transparent,
      scrolledUnderElevation: 0,
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: meow.card,
      indicatorColor: scheme.primaryContainer,
      labelTextStyle: WidgetStateProperty.resolveWith(
        (states) => TextStyle(
          color: states.contains(WidgetState.selected)
              ? scheme.onPrimaryContainer
              : scheme.onSurfaceVariant,
          fontSize: 12,
          fontWeight: states.contains(WidgetState.selected)
              ? FontWeight.w600
              : FontWeight.w500,
        ),
      ),
      iconTheme: WidgetStateProperty.resolveWith(
        (states) => IconThemeData(
          color: states.contains(WidgetState.selected)
              ? scheme.onPrimaryContainer
              : scheme.onSurfaceVariant,
        ),
      ),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      color: meow.card,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(8),
        side: BorderSide(color: scheme.outlineVariant),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: scheme.primary,
        foregroundColor: scheme.onPrimary,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: scheme.primary,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(
        foregroundColor: scheme.onSurfaceVariant,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: isLight ? const Color(0xFFF3FAFF) : const Color(0xFF101D28),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: BorderSide(color: scheme.primary, width: 1.5),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: BorderSide(color: scheme.outlineVariant),
      ),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: scheme.primary),
    dividerTheme: DividerThemeData(color: scheme.outlineVariant),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: isLight
          ? MeowColors.brandInk
          : const Color(0xFFE8F2FA),
      contentTextStyle: TextStyle(
        color: isLight ? Colors.white : MeowColors.brandInk,
      ),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.disabled)) return scheme.outlineVariant;
        return states.contains(WidgetState.selected)
            ? scheme.primary
            : scheme.outline;
      }),
      trackColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.disabled)) {
          return scheme.surfaceContainerHighest.withValues(alpha: 0.55);
        }
        return states.contains(WidgetState.selected)
            ? scheme.primaryContainer
            : scheme.surfaceContainerHighest;
      }),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        visualDensity: VisualDensity.compact,
        shape: WidgetStateProperty.all(
          RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        ),
        backgroundColor: WidgetStateProperty.resolveWith((states) {
          return states.contains(WidgetState.selected)
              ? scheme.primaryContainer
              : Colors.transparent;
        }),
        foregroundColor: WidgetStateProperty.resolveWith((states) {
          return states.contains(WidgetState.selected)
              ? scheme.onPrimaryContainer
              : scheme.onSurfaceVariant;
        }),
      ),
    ),
  );
}
