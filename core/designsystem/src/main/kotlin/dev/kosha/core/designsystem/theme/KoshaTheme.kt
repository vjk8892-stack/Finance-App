package dev.kosha.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.kosha.core.designsystem.token.KoshaColors

/**
 * Kosha is charcoal-first by design (spec A2) — there is no light theme in
 * v1. [vaultSkin] switches to the darker vault variant (spec C6).
 */
val LocalVaultSkin = staticCompositionLocalOf { false }

private fun koshaColorScheme(vault: Boolean): ColorScheme = darkColorScheme(
    primary = KoshaColors.AccentTeal,
    onPrimary = KoshaColors.Charcoal,
    secondary = KoshaColors.AccentViolet,
    onSecondary = KoshaColors.OffWhite,
    tertiary = KoshaColors.Amber,
    background = if (vault) KoshaColors.VaultBackground else KoshaColors.Charcoal,
    onBackground = KoshaColors.OffWhite,
    surface = if (vault) KoshaColors.VaultRaised else KoshaColors.CharcoalRaised,
    onSurface = KoshaColors.OffWhite,
    surfaceVariant = KoshaColors.CharcoalOverlay,
    onSurfaceVariant = KoshaColors.OffWhiteMuted,
    outline = KoshaColors.Outline,
    error = KoshaColors.Amber, // no red anywhere — even "error" renders amber
    onError = KoshaColors.Charcoal,
)

@Composable
fun KoshaTheme(
    vaultSkin: Boolean = false,
    content: @Composable () -> Unit,
) {
    // isSystemInDarkTheme() intentionally unused: Kosha renders charcoal in
    // both system modes. Kept referenced so the decision is explicit.
    isSystemInDarkTheme()
    CompositionLocalProvider(LocalVaultSkin provides vaultSkin) {
        MaterialTheme(
            colorScheme = koshaColorScheme(vaultSkin),
            content = content,
        )
    }
}
