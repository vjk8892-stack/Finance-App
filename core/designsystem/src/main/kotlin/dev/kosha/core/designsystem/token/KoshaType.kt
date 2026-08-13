package dev.kosha.core.designsystem.token

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type tokens (spec A2): tabular figures for ALL amounts (no reflow during
 * count-up), a quiet serif for insight sentences, sans-serif for UI chrome.
 */
object KoshaType {

    /** Applied to every amount rendering — keeps digits monospaced-width. */
    const val TabularFigures = "tnum"

    private val amountBase = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontFeatureSettings = TabularFigures,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    val AmountHero = amountBase.copy(fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp)
    val AmountLarge = amountBase.copy(fontSize = 28.sp, fontWeight = FontWeight.Normal)
    val AmountBody = amountBase.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val AmountSmall = amountBase.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium)

    /** Insight sentences ("You're ₹4,200 ahead of last month") — quiet serif. */
    val InsightSerif = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 26.sp,
    )

    val Title = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp)
    val Label = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
    val Caption = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp)
}
