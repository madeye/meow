package io.github.madeye.meow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.ui.theme.MeowTheme
import io.github.madeye.meow.ui.theme.meow

/**
 * The universal surface primitive, ported from meow-ios's `GlassCard`:
 * 16pt padding, panel fill, 14pt radius, 1pt border, soft navy shadow.
 * Almost every piece of content in the app sits in one of these.
 *
 * Note: `ambientColor`/`spotColor` on [shadow] are only honoured on API 28+.
 * On 24–27 the shadow falls back to black, which is close enough to the dark
 * theme's navy token and acceptably subtle in light mode.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.meow
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            // clip = false so the shadow can escape the card bounds.
            .shadow(
                elevation = 8.dp,
                shape = shape,
                clip = false,
                ambientColor = colors.navy.copy(alpha = 0.10f),
                spotColor = colors.navy.copy(alpha = 0.18f),
            )
            .background(colors.panel, shape)
            .border(1.dp, colors.border, shape)
            .then(
                if (onClick != null) {
                    // Clip only in the clickable branch, so the ripple stays
                    // inside the radius without clipping the shadow above.
                    Modifier.clip(shape).clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
        content = content,
    )
}

@Preview(name = "GlassCard light", showBackground = true, backgroundColor = 0xFFEAF8FF)
@Composable
private fun GlassCardPreviewLight() {
    MeowTheme(darkTheme = false) {
        GlassCard(modifier = Modifier.padding(16.dp)) {
            SectionHeader("Profiles")
        }
    }
}

@Preview(name = "GlassCard dark", showBackground = true, backgroundColor = 0xFF0B1720)
@Composable
private fun GlassCardPreviewDark() {
    MeowTheme(darkTheme = true) {
        GlassCard(modifier = Modifier.padding(16.dp)) {
            SectionHeader("Profiles")
        }
    }
}
