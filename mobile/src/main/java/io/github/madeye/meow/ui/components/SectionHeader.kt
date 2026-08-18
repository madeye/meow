package io.github.madeye.meow.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.ui.theme.meow
import java.util.Locale

/**
 * Small-caps group label above a card — "PROFILES", "ENGINE", "GENERAL".
 * Mirrors meow-ios's `SectionHeader` (caption2, semibold, muted, uppercased).
 *
 * `uppercase` is a no-op for Chinese, which is the desired behaviour and matches
 * SwiftUI's `.textCase(.uppercase)`.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.meow.mutedText,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}
