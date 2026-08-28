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

    // Accent — money-flow visuals, and now selection state (see below).
    val AccentTeal = Color(0xFF2DD4BF)
    val AccentViolet = Color(0xFF8B5CF6)
    /** Brighter teal for text and fills that must read on charcoal. */
    val AccentTealBright = Color(0xFF5EEAD4)
    val AccentVioletBright = Color(0xFFA78BFA)

    // Caution — the only non-monochrome semantic color. Never red.
    val Amber = Color(0xFFD97706)
    /** Amber that survives being small text on a dark card. */
    val AmberBright = Color(0xFFFBBF24)

    /**
     * Category identity colors.
     *
     * The original palette was eight desaturated greys for ACCOUNTS only, and
     * categories had no color at all — so a treemap or a ledger of thirty rows
     * was one undifferentiated wash and nothing drew the eye to anything. These
     * are saturated enough to tell apart at icon size while staying inside the
     * one hard rule: no red. The warm end stops at orange.
     */
    val CategoryPalette = listOf(
        Color(0xFFF97316), // orange   — dining
        Color(0xFF22C55E), // green    — groceries
        Color(0xFF38BDF8), // sky      — transport
        Color(0xFFFACC15), // yellow   — fuel
        Color(0xFFE879F9), // fuchsia  — shopping
        Color(0xFF2DD4BF), // teal     — bills
        Color(0xFFA78BFA), // violet   — rent
        Color(0xFFFB923C), // amber-o  — emi
        Color(0xFF4ADE80), // mint     — health
        Color(0xFF60A5FA), // blue     — insurance
        Color(0xFFC084FC), // purple   — education
        Color(0xFFF472B6), // pink     — entertainment
        Color(0xFF34D399), // emerald  — subscriptions
        Color(0xFF818CF8), // indigo   — travel
        Color(0xFFFDE047), // lemon    — personal care
        Color(0xFF94A3B8), // slate    — construction
    )

    /**
     * Stable color for a category NAME, so the same category is the same color
     * everywhere — chart slice, ledger icon, budget ring — without needing a
     * column in the database.
     */
    fun categoryColor(name: String?): Color {
        if (name.isNullOrBlank()) return OffWhiteFaint
        val hash = name.fold(0) { acc, c -> acc * 31 + c.code }
        return CategoryPalette[hash.mod(CategoryPalette.size)]
    }

    /**
     * Kosha DS ("Bioluminescent Deep-Space") structural tokens: glowing
     * instrument-panel borders and glass-panel gradients. These are
     * STRUCTURE — a card's edge glowing teal is not the same claim as a
     * chart using the accent gradient to mean money movement, so the
     * accent-restraint rule above still holds.
     */
    val HudBorder = Color(0x4D2DD4BF)     // ~30% AccentTeal — lit card/chip edges
    val HudBorderDim = Color(0x1F2DD4BF)  // ~12% AccentTeal — idle hairline
    val GlassTop = Color(0xFF1A2420)      // teal-tinted glass highlight
    val GlassBottom = Color(0xFF0F1114)

    /** Soft radial "glow blob" backdrops — Home screen only, not global. */
    val GlowBlobTeal = Color(0x292DD4BF)    // ~16% AccentTeal
    val GlowBlobViolet = Color(0x268B5CF6)  // ~15% AccentViolet

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
