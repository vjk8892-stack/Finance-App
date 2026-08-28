package dev.kosha.core.designsystem.token

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * Kosha DS v2 ("Futuristic Calm") shape language: corners are chamfered, not
 * rounded. This is the one silhouette change that reads as instrument-panel
 * rather than rounded-card at a glance, and every card/chip/key in the app
 * draws its outline from here, so the whole app picks it up from one place.
 */
object KoshaShapes {
    fun chamfered(cut: Dp): Shape = GenericShape { size, _ ->
        val c = cut.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
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
