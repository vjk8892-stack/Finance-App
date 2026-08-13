package dev.kosha.core.designsystem.token

import androidx.compose.ui.graphics.Color

/**
 * Kosha color tokens (spec A2 + G3). Single source of truth — no literal
 * colors anywhere else in the app.
 *
 * Rules: NO red anywhere; amber is the only caution color. The teal→violet
 * accent gradient is reserved for money-flow visuals (Pulse ring, Sankey,
 * forecast line).
 */
object KoshaColors {
    // Base
    val Charcoal = Color(0xFF0F1114)        // app background (OLED-friendly)
    val CharcoalRaised = Color(0xFF16191E)  // cards / raised surfaces
    val CharcoalOverlay = Color(0xFF1D2127) // sheets, dialogs
    val OffWhite = Color(0xFFF2EFEA)        // primary text
    val OffWhiteMuted = Color(0xFFB9B4AC)   // secondary text
    val OffWhiteFaint = Color(0xFF6E6A64)   // hints, disabled
    val Outline = Color(0xFF2A2E35)

    // Accent — money-flow visuals ONLY
    val AccentTeal = Color(0xFF2DD4BF)
    val AccentViolet = Color(0xFF8B5CF6)

    // Caution — the only non-monochrome semantic color. Never red.
    val Amber = Color(0xFFD97706)

    // Vault skin (darker variant)
    val VaultBackground = Color(0xFF0A0C0E)
    val VaultRaised = Color(0xFF101317)

    /** Account palette, `colorToken` 0–7 (spec G3). Never used for amounts or semantic states. */
    val AccountPalette = listOf(
        Color(0xFF64748B), // 0 slate
        Color(0xFF6B8F71), // 1 sage
        Color(0xFF7C6F9B), // 2 dusk
        Color(0xFFA8916C), // 3 sand
        Color(0xFF5E7A8A), // 4 steel
        Color(0xFF96706F), // 5 rose-ash
        Color(0xFF77836A), // 6 moss
        Color(0xFF52565C), // 7 graphite
    )

    fun accountColor(colorToken: Int): Color =
        AccountPalette[colorToken.mod(AccountPalette.size)]
}
