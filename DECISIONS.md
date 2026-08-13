# DECISIONS.md — append-only

Format: date · decision · alternatives · why.

- **2026-08-13 · Adopt spec v1.2 as source of truth** · — · Attached master
  build spec committed as `docs/koshabuildspec.md`; all product/design/phasing
  questions resolve there first, then get logged here.
- **2026-08-13 · No `INTERNET` permission in core app** · optional dynamic
  feature later · Marketing-grade privacy claim; the app cannot phone home
  (spec B1).
- **2026-08-13 · No red anywhere; amber `#D97706` is the only caution color**
  · standard error red · Calm-ledger design principle (spec A2).
- **2026-08-13 · Amounts as Long paise (`Money` value class)** · BigDecimal ·
  Exact, fast, no FP drift; INR-only v1 (spec G1).
- **2026-08-13 · Pure-JVM engines live in a standalone composite build
  `engines/` (`dev.kosha:common`, `dev.kosha:engine`), a deviation from spec
  B2's `:core:common`/`:core:engine` project paths** · plain subprojects ·
  Spec Part E requires parser/dedup/forecast/debt engines to be pure Kotlin
  with exhaustive unit fixtures. A standalone build makes that structural AND
  keeps engine tests runnable with a bare JDK: the cloud sandbox this repo is
  built in blocks `dl.google.com` (Android SDK + Google Maven), so anything
  touching AGP can only build in GitHub Actions CI. Composite substitution
  (`includeBuild("engines")`) keeps them first-class for the Android build.
- **2026-08-13 · `PdfDocument` over PDF SDKs; hand-rolled CSV** · iText/POI ·
  Licensing + APK size (spec B1).
- **2026-08-13 · SQLCipher passphrase = random 32 bytes wrapped by a
  non-auth-bound Keystore AES-GCM key (StrongBox w/ TEE fallback)** · deriving
  from user PIN · Background writers must work while locked (Ring 1, spec B4).
- **2026-08-13 · Convention plugins in `build-logic/`** · per-module copy-paste
  config · 16 modules; one place to change compileSdk/JVM target.
- **2026-08-13 · Baseline budgets (Phase 0 gate): APK size measured by CI
  artifact on every build; cold-start + SMS-receiver-under-doze + OCR spikes
  REQUIRE physical devices and cannot run in the cloud sandbox** — flagged as
  owner action items; mitigations (battery-exemption prompt flow, WorkManager
  reconcile scan) are still being built into the code as specified.
