package dev.kosha.core.designsystem.token

/**
 * Motion tokens (spec A2): motion = feedback, not decoration.
 * No shake/alarm animations anywhere.
 */
object KoshaMotion {
    /** Standard UI transitions. */
    const val StandardMs = 250

    /** Count-up animation for amounts. */
    const val CountUpMs = 650

    /** Pulse ring slow-breathing cycle (spec C2: 4s). */
    const val PulseBreatheMs = 4000

    /** Vault lock-forming transition. */
    const val VaultTransitionMs = 450

    /** Vault field auto re-mask window (spec B4: 20s). */
    const val VaultRemaskMs = 20_000
}
