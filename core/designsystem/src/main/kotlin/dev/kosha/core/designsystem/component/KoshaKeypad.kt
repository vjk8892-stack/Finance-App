package dev.kosha.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/**
 * Amount-first keypad (spec C4): biggest tap targets in the app.
 * Emits raw key events; the caller owns the amount string state.
 */
sealed interface KeypadKey {
    data class Digit(val value: Int) : KeypadKey
    data object Decimal : KeypadKey
    data object Backspace : KeypadKey
}

@Composable
fun KoshaKeypad(
    onKey: (KeypadKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows: List<List<Pair<String, KeypadKey>>> = listOf(
        listOf("1" to KeypadKey.Digit(1), "2" to KeypadKey.Digit(2), "3" to KeypadKey.Digit(3)),
        listOf("4" to KeypadKey.Digit(4), "5" to KeypadKey.Digit(5), "6" to KeypadKey.Digit(6)),
        listOf("7" to KeypadKey.Digit(7), "8" to KeypadKey.Digit(8), "9" to KeypadKey.Digit(9)),
        listOf("." to KeypadKey.Decimal, "0" to KeypadKey.Digit(0), "⌫" to KeypadKey.Backspace),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs),
            ) {
                row.forEach { (label, key) ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(RoundedCornerShape(KoshaSpacing.chipRadius))
                            .background(KoshaColors.CharcoalRaised)
                            .clickable { onKey(key) },
                    ) {
                        Text(
                            text = label,
                            style = KoshaType.AmountLarge.copy(fontSize = 24.sp),
                            color = KoshaColors.OffWhite,
                        )
                    }
                }
            }
        }
    }
}
