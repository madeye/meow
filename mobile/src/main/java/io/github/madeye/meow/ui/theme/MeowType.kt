package io.github.madeye.meow.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The iOS app uses SF text styles semantically rather than a custom font; the
 * Android equivalent is the system font on the same semantic scale. The mapping
 * from iOS style to M3 role:
 *
 * | iOS                        | M3 role        |
 * |----------------------------|----------------|
 * | `.title2.semibold` (hero)  | headlineSmall  |
 * | `.headline` (card titles)  | titleMedium    |
 * | `.subheadline` (rows)      | bodyMedium     |
 * | `.caption` (secondary)     | bodySmall      |
 * | `.caption2.semibold.upper` | labelSmall     |
 */
val MeowTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp),
        labelSmall = labelSmall.copy(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        ),
    )
}

object MeowTextStyles {
    /**
     * Tabular figures. Byte counters, latencies and chart axes all update once a
     * second — proportional digits make them visibly jitter as glyph widths change.
     */
    val monoDigits = TextStyle(fontFeatureSettings = "tnum")

    val chartLabel = TextStyle(
        fontSize = 9.sp,
        fontFeatureSettings = "tnum",
        letterSpacing = 0.5.sp,
    )
}
