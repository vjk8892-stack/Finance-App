package dev.kosha.core.designsystem.token

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * Kosha DS v2 ("Futuristic Calm") shape language: corners are chamfered, not
 * rounded. This is the one silhouette change that reads as instrument-panel
 * rather than rounded-card at a glance, and every card/chip/key in the app
 * draws its outline from here, so the whole app picks it up from one place.
 *
 * `@Composable`, not a plain function: [GenericShape]'s builder lambda runs
 * with `Path` as its receiver, not `Density`, so converting [cut] to pixels
 * needs [LocalDensity] read at the call site — every caller here is already
 * inside a composable, so this costs nothing at the use sites.
 */
object KoshaShapes {
    @Composable
    fun chamfered(cut: Dp): Shape {
        val cutPx = with(LocalDensity.current) { cut.toPx() }
        return remember(cutPx) {
            GenericShape { size, _ ->
                val c = cutPx.coerceAtMost(minOf(size.width, size.height) / 2f)
                moveTo(c, 0f)
                lineTo(size.width - c, 0f)
                lineTo(size.width, c)
                lineTo(size.width, size.height - c)
                lineTo(size.width - c, size.height)
                lineTo(c, size.height)
                lineTo(0f, size.height - c)
                lineTo(0f, c)
                close()
            }
        }
    }
}
