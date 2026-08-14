package dev.kosha.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import kotlinx.coroutines.delay

/**
 * A few seconds of grace after something irreversible.
 *
 * Deleting a transaction, approving fifty at once, or recategorizing a whole
 * merchant were all one tap with no way back — the delete confirmation
 * literally said "There is no undo". That is a bad trade in an app whose data
 * arrives from a parser: the actions most likely to be taken by mistake are
 * exactly the ones that were permanent. This is deliberately time-boxed rather
 * than a full history — it covers the slip, not the change of mind.
 */
@Composable
fun KoshaUndoBar(
    visible: Boolean,
    message: String,
    actionLabel: String,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    timeoutMillis: Long = 6_000,
) {
    // Re-armed whenever a new action arrives, so back-to-back deletes each get
    // their own window instead of inheriting the first one's remaining time.
    LaunchedEffect(visible, message) {
        if (visible) {
            delay(timeoutMillis)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(KoshaSpacing.cardRadius)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(KoshaSpacing.s)
                .clip(shape)
                .background(KoshaColors.CharcoalOverlay)
                .border(1.dp, KoshaColors.AccentTeal.copy(alpha = 0.5f), shape)
                .padding(horizontal = KoshaSpacing.m, vertical = KoshaSpacing.s),
        ) {
            Text(
                text = message,
                style = KoshaType.Body,
                color = KoshaColors.OffWhite,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = actionLabel,
                style = KoshaType.LabelStrong,
                color = KoshaColors.AccentTealBright,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onUndo)
                    .padding(horizontal = KoshaSpacing.s, vertical = KoshaSpacing.xxs),
            )
        }
    }
}
