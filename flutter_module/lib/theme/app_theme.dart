import 'package:flutter/material.dart';

@immutable
class MeowColors extends ThemeExtension<MeowColors> {
  static const iconOrange = Color(0xFFF0881A);
  static const iconAmber = Color(0xFFFFB24A);
  static const iconCoral = Color(0xFFE77B61);
  static const iconCream = Color(0xFFFFF3E0);

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
  final meow = isLight
      ? const MeowColors(
          canvas: MeowColors.iconCream,
          card: Color(0xFFFFFBF5),
          connected: Color(0xFFD96F00),
          upload: MeowColors.iconOrange,
          download: MeowColors.iconCoral,
        )
      : const MeowColors(
          canvas: Color(0xFF1B1209),
          card: Color(0xFF261A10),
          connected: Color(0xFFFFB15F),
          upload: Color(0xFFFFB15F),
          download: Color(0xFFFF9A82),
        );

  final seeded = ColorScheme.fromSeed(
    seedColor: MeowColors.iconOrange,
    brightness: brightness,
    contrastLevel: isLight ? 0.35 : 0.25,
  );

  final scheme = seeded.copyWith(
    primary: isLight ? MeowColors.iconOrange : const Color(0xFFFFB15F),
    onPrimary: isLight ? const Color(0xFF2D1600) : const Color(0xFF2F1500),
    primaryContainer: isLight
        ? const Color(0xFFFFDEB8)
        : const Color(0xFF6B3400),
    onPrimaryContainer: isLight
        ? const Color(0xFF3E1D00)
        : const Color(0xFFFFE2C2),
    secondary: isLight ? MeowColors.iconCoral : const Color(0xFFFFB4A4),
    onSecondary: isLight ? const Color(0xFF3A0C05) : const Color(0xFF4D160E),
    secondaryContainer: isLight
        ? const Color(0xFFFFDAD3)
        : const Color(0xFF7A2C21),
    onSecondaryContainer: isLight
        ? const Color(0xFF4A120B)
        : const Color(0xFFFFDAD3),
    tertiary: isLight ? const Color(0xFFA76500) : MeowColors.iconAmber,
    onTertiary: isLight ? Colors.white : const Color(0xFF3B2100),
    tertiaryContainer: isLight
        ? const Color(0xFFFFDDB0)
        : const Color(0xFF5B3A00),
    onTertiaryContainer: isLight
        ? const Color(0xFF3A2100)
        : const Color(0xFFFFDDB0),
    surface: meow.card,
    onSurface: isLight ? const Color(0xFF241A10) : const Color(0xFFF4E7DA),
    surfaceContainerHighest: isLight
        ? const Color(0xFFF2DED0)
        : const Color(0xFF504539),
    onSurfaceVariant: isLight
        ? const Color(0xFF5B4A3A)
        : const Color(0xFFD5C3B5),
    outline: isLight ? const Color(0xFF927764) : const Color(0xFFA78D78),
    outlineVariant: isLight ? const Color(0xFFE6CDBA) : const Color(0xFF5C4A3A),
  );

  final base = ThemeData(useMaterial3: true, brightness: brightness);

  return base.copyWith(
    colorScheme: scheme,
    scaffoldBackgroundColor: meow.canvas,
    extensions: [meow],
    appBarTheme: AppBarTheme(
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
      fillColor: isLight ? const Color(0xFFFFF7EC) : const Color(0xFF21160E),
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
          ? const Color(0xFF3B2818)
          : const Color(0xFFFFE2C2),
      contentTextStyle: TextStyle(
        color: isLight ? Colors.white : const Color(0xFF2D1600),
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
