# Kosha — offline-first personal finance app (Android)

**Spec:** `docs/koshabuildspec.md` (v1.2) is the single source of truth for
product, design, and phasing. `DECISIONS.md` is the append-only decision log.

**Current phase:** Phases 0–5 shipped (app is a complete offline tracker:
manual + SMS + photo capture, budgets, income, period close, forecast).
Phase 7/9 analytics engines are built and tested; their Insights/Goals UI is
in progress. Phases 6, 8, 10–12 remain.

## Stack
Kotlin · Jetpack Compose + Material 3 (custom "Kosha DS") · Room + SQLCipher ·
Hilt · WorkManager · ML Kit (on-device) · Glance · CameraX · Vico + custom
Canvas charts. `minSdk 26`, `compileSdk 35`.

## Module map (spec B2)
- `:app` — shell, navigation, DI graph
- `:core:designsystem` — tokens (`token/Kosha*.kt` — the ONLY place colors/type/motion live), theme, base components
- `:core:database` — Room schema, DAOs, migrations, SQLCipher setup
- `engines/common` (`dev.kosha:common`) — **pure JVM**: Money (Long paise), Periods, Result
- `engines/engine` (`dev.kosha:engine`) — **pure JVM**: ingestion pipeline, parsers, dedup, analytics engines (all exhaustively unit-tested here)
- `:feature:*` — one module per feature area (ledger, ingest/{sms,ocr,review}, budget, income, insights, goals, vault, export, widgets)

## Invariants (do not break)
- Ingest modules NEVER write to the transaction table directly — everything
  goes through the unified Ingestion Pipeline in `:core:engine` (spec B3).
- **No `INTERNET` permission** in any manifest. Ever.
- **No red anywhere** in UI. Caution = amber `KoshaColors.Amber`.
- Amounts are `Money` (Long paise). Never floating point, never raw Long in UI.
- All amounts render through `AmountText` (tabular figures).
- Destructive DB migrations forbidden after Phase 2.
- Vault (Ring 2) data never crosses into exports/backups by default.

## Sandbox builds (no Android SDK / Google Maven available locally)
- `engines/` is a standalone composite build with zero Android dependency:
  `./gradlew -p engines test` (or `gradle test` inside `engines/`) runs all
  engine unit tests with a bare JDK.
- The Android build consumes it via `includeBuild("engines")` as
  `dev.kosha:common` / `dev.kosha:engine`.
- Full Android build + lint + APK runs in GitHub Actions (`.github/workflows/ci.yml`).
- Instrumented tests (`androidTest`) need a device/emulator — run locally via
  `./gradlew connectedDebugAndroidTest`.
