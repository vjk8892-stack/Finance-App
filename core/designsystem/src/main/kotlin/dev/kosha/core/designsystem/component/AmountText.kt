package dev.kosha.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import dev.kosha.core.common.Money
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaMotion
import dev.kosha.core.designsystem.token.KoshaType
import kotlin.math.roundToLong

/**
 * The ONLY way amounts are rendered in Kosha: tabular figures (no reflow),
 * optional smooth count-up (motion = feedback), TalkBack-friendly
 * description with currency and sign (spec Phase 12 accessibility rule).
 */
@Composable
fun AmountText(
    amount: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = KoshaType.AmountBody,
    color: Color = KoshaColors.OffWhite,
    withPaise: Boolean = true,
    signed: Boolean = false,
    countUp: Boolean = false,
) {
    val target = amount.paise.toFloat()
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = if (countUp) tween(KoshaMotion.CountUpMs) else tween(0),
        label = "amountCountUp",
    )
    val shown = if (countUp) Money(animated.roundToLong()) else amount
    val description = remember(amount, signed) {
        val sign = when {
            amount.isNegative -> "minus "
            signed && amount.paise > 0 -> "plus "
            else -> ""
        }
        "$sign${amount.abs.format(withSymbol = false)} rupees"
    }
    Text(
        text = shown.format(withPaise = withPaise, signed = signed),
        style = style,
        color = color,
        modifier = modifier.semantics { contentDescription = description },
    )
}
