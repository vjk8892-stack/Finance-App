package dev.kosha.core.designsystem.token

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.kosha.core.designsystem.R

/**
 * Type tokens (Kosha DS, "Bioluminescent Deep-Space"): tabular figures for
 * ALL amounts (no reflow during count-up), a quiet serif for insight
 * sentences, Chakra Petch for UI chrome (titles, labels, section headers) —
 * an angular HUD cadence — and JetBrains Mono for amounts, which reads as a
 * digital readout and is already tabular-figured. Body copy stays
 * sans-serif: a screen of display-face paragraphs reads as a poster, not a
 * calm ledger, so prose keeps its own voice.
 */
object KoshaType {

    /** Applied to every amount rendering — keeps digits monospaced-width. */
    const val TabularFigures = "tnum"

    /** Chrome / display face — titles, labels, section headers. */
    val ChakraPetch = FontFamily(
        Font(R.font.chakra_petch_light, FontWeight.Light),
        Font(R.font.chakra_petch_regular, FontWeight.Normal),
        Font(R.font.chakra_petch_medium, FontWeight.Medium),
        Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
        Font(R.font.chakra_petch_bold, FontWeight.Bold),
    )

    /** Digital-readout face — every amount, no exceptions. */
    val JetBrainsMono = FontFamily(
        Font(R.font.jetbrains_mono_light, FontWeight.Light),
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
        Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )

    private val amountBase = TextStyle(
        fontFamily = JetBrainsMono,
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

    val Title = TextStyle(fontFamily = ChakraPetch, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)

    /**
     * Screen titles. Bigger and heavier than section titles so a screen
     * announces itself — the previous single Title style made "Ledger" and a
     * card heading identical, which flattened the whole hierarchy.
     */
    val ScreenTitle = TextStyle(
        fontFamily = ChakraPetch,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.2.sp,
    )
    val Body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp)
    val Label = TextStyle(fontFamily = ChakraPetch, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)

    /** Label for a state that is ON — selected chips, active filters. */
    val LabelStrong = TextStyle(
        fontFamily = ChakraPetch,
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
        fontFamily = ChakraPetch,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
    val Caption = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp)
}
