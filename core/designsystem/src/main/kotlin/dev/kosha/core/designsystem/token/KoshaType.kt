package dev.kosha.core.designsystem.token

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type tokens (Kosha DS v2, "Futuristic Calm"): tabular figures for ALL
 * amounts (no reflow during count-up), a quiet serif for insight sentences,
 * MONOSPACE for UI chrome (titles, labels, section headers) — a console/HUD
 * cadence — and monospace for amounts too, which reads as a digital readout
 * and was already tabular-figured, so the switch costs nothing in alignment.
 * Body copy stays sans-serif: a screen of monospace paragraphs reads as a
 * terminal, not a calm ledger, so prose keeps its own voice.
 */
object KoshaType {

    /** Applied to every amount rendering — keeps digits monospaced-width. */
    const val TabularFigures = "tnum"

    private val amountBase = TextStyle(
        fontFamily = FontFamily.Monospace,
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

    val Title = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)

    /**
     * Screen titles. Bigger and heavier than section titles so a screen
     * announces itself — the previous single Title style made "Ledger" and a
     * card heading identical, which flattened the whole hierarchy.
     */
    val ScreenTitle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.2.sp,
    )
    val Body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp)
    val Label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)

    /** Label for a state that is ON — selected chips, active filters. */
    val LabelStrong = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
    )

    /**
     * Date headers in a list. The date is how you navigate a ledger, so it
     * needs to out-rank the rows beneath it — `Label` at 12sp in a muted tone
     * made the spine of the list its faintest element.
     */
    val SectionHeader = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
    val Caption = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp)
}
