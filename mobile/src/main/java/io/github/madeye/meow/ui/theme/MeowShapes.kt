package io.github.madeye.meow.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * iOS uses a 14pt continuous ("squircle") radius for its cards. Compose has no
 * continuous corner primitive; at this radius a plain rounded rect is
 * indistinguishable, and pulling in `androidx.graphics:graphics-shapes` for the
 * difference is not worth it.
 */
val MeowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Layout constants shared across screens, so spacing stays consistent. */
object MeowSpacing {
    val screen = 16.dp
    val card = 16.dp
    val gap = 12.dp
    val section = 20.dp
}
