package io.github.madeye.meow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.ui.theme.MeowTheme
import io.github.madeye.meow.ui.theme.meow

/**
 * A tappable settings/hub row: circular accent icon chip, title, optional
 * subtitle or trailing content, chevron. Ported from meow-ios's `NavRow`.
 *
 * When [enabled] is false the row dims and stops responding — used for the
 * engine-backed screens (Connections/Rules/Logs) while the VPN is down, so the
 * user doesn't tap into three screens that can only fail to load.
 */
@Composable
fun NavRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = MaterialTheme.meow
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(colors.accent.copy(alpha = 0.10f * alpha), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent.copy(alpha = alpha),
                modifier = Modifier.size(17.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText.copy(alpha = alpha),
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.mutedText.copy(alpha = 0.6f * alpha),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFEAF8FF)
@Composable
private fun NavRowPreview() {
    MeowTheme(darkTheme = false) {
        GlassCard(modifier = Modifier.padding(16.dp)) {
            NavRow(title = "Connections", icon = Icons.Filled.Dns, onClick = {})
            NavRow(
                title = "Rules",
                icon = Icons.Filled.Dns,
                onClick = {},
                enabled = false,
                subtitle = "Connect to use",
            )
        }
    }
}
