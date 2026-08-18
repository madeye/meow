package io.github.madeye.meow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.MeowTheme
import io.github.madeye.meow.ui.theme.meow

/**
 * Latency pill for a proxy node. Thresholds and the 18%-opacity tint come from
 * meow-ios: under 200 ms good, under 500 ms marginal, above that bad.
 *
 * The minimum width keeps rows from jittering as results stream in during a
 * group speed test.
 */
@Composable
fun DelayBadge(
    delayMs: Int?,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val colors = MaterialTheme.meow
    Box(
        modifier = modifier.widthIn(min = 56.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = colors.accent,
            )

            delayMs != null && delayMs > 0 -> {
                val tint = when {
                    delayMs < 200 -> colors.connected
                    delayMs < 500 -> colors.warning
                    else -> colors.danger
                }
                Text(
                    text = "$delayMs ms",
                    style = MaterialTheme.typography.labelMedium.merge(MeowTextStyles.monoDigits),
                    color = tint,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .background(tint.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            else -> Icon(
                imageVector = Icons.Outlined.RemoveCircleOutline,
                contentDescription = null,
                tint = colors.mutedText.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun DelayBadgePreview() {
    MeowTheme(darkTheme = false) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
        ) {
            DelayBadge(delayMs = 76)
            DelayBadge(delayMs = 320)
            DelayBadge(delayMs = 812)
            DelayBadge(delayMs = null)
            DelayBadge(delayMs = null, loading = true)
        }
    }
}
