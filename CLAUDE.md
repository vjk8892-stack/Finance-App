# Kosha — offline-first personal finance app (Android)

**Spec:** `docs/koshabuildspec.md` (v1.2) is the single source of truth for
product, design, and phasing. `DECISIONS.md` is the append-only decision log.

**Current state:** all 12 phases implemented; CI green (build + lint + unit
tests + debug APK). Gates that need physical devices are still open — see
`docs/DEVICE_GATES.md`.

## Stack
Kotlin · Jetpack Compose + Material 3 (custom "Kosha DS") · Room + SQLCipher ·
Hilt · WorkManager · ML Kit (on-device) · Glance · CameraX · custom Canvas
charts. `minSdk 26`, `compileSdk 35`.

## Module map (spec B2)
- `:app` — shell, navigation, DI graph, onboarding, Ring-0 app lock
- `:core:designsystem` — tokens (`token/Kosha*.kt` — the ONLY place colors/type/motion live), theme, base components
- `:core:database` — Room schema, DAOs, repositories, migrations, SQLCipher, DataStore settings
- `engines/common` (`dev.kosha:common`) — **pure JVM**: Money (Long paise), Periods, Result
- `engines/engine` (`dev.kosha:engine`) — **pure JVM**: ingestion pipeline, SMS/OCR parsing, dedup, period/budget math, forecast, insight engines, debt planner, query NLU, constitution (all exhaustively unit-tested here — 186 tests)
- `:feature:*` — ledger, ingest/{sms,ocr,review}, budget, income, insights, goals, vault, export, widgets

## Invariants (do not break)
- Ingest modules NEVER write to the transaction table directly — everything
  goes through the Ingestion Pipeline and `PipelineCommitter` (spec B2/B3).
- SMS detection is **bank-agnostic** (`TransactionClassifier`): amount +
  direction verb, no dependence on a balance line or on the bank being known.
  `SmsPatternLibrary` may only raise confidence and name the bank — it must
  never be what decides whether a message is a transaction.
- A transaction is **never attributed to an account the user did not confirm**.
  An unmatched SMS account tail creates its own account and goes to review;
  it must not fall back to the first account (see `MultiAccountAttributionTest`).
- **No `INTERNET` permission** in any manifest. Ever.
- **No red anywhere** in UI. Caution = amber `KoshaColors.Amber`.
- Amounts are `Money` (Long paise). Never floating point, never raw Long in UI.
- All amounts render through `AmountText` (tabular figures).
- Destructive DB migrations forbidden after Phase 2 — the schema is frozen;
  every change ships a tested `Migration` (harness in `:core:database` androidTest).
- Vault (Ring 2) never reaches exports or backups by default; `:feature:export`
  must never depend on `:feature:vault` (pinned by an instrumented test).
- New arithmetic goes in `engines/` with tests, not in a ViewModel.

## Sandbox builds (no Android SDK / Google Maven available locally)
- `./gradlew -p engines test` runs all engine unit tests with a bare JDK.
- The Android build consumes it via `includeBuild("engines")` as
  `dev.kosha:common` / `dev.kosha:engine`.
- Full Android build + lint + APK runs in GitHub Actions (`.github/workflows/ci.yml`).
- Instrumented tests (`androidTest`) need a device/emulator — run locally via
  `./gradlew connectedDebugAndroidTest`.
