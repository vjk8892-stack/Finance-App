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
- **2026-08-13 · Review queue = `status` column on Transaction
  (committed | pending_review) + reviewReason/possibleDuplicateOfId** ·
  separate queue table · One source of truth; approving is a status flip;
  balance/ledger/analytics queries filter `status='committed'`. Schema change
  made in Phase 2, before the migration freeze.
- **2026-08-13 · Discard-with-log lands in logcat for now; the B3 debug
  screen for discards is deferred to the Phase 12 polish pass** · build it
  now · Zero user value until there's real discard volume to inspect.
- **2026-08-13 · SMS timestamps use receipt time, not text-parsed dates** ·
  parse per-bank date formats · Receipt time is within seconds of the event
  for live capture and exactly what the inbox stores for imports; per-bank
  date parsing is a large error surface for ~no dedup gain (the ±10 min
  window keys on receipt time on both sides).
- **2026-08-13 · OCR prefers the bank UTR over a UPI app's own transaction
  id** · first reference wins · Only the bank reference also appears in the
  bank's SMS, so it is the sole key that makes photo↔SMS dedup provable.
  Caught by the Phase-4 dedup test, which merged on the weaker
  amount/time/merchant rule until this was fixed.
- **2026-08-13 · Gallery imports score lower on TIMESTAMP than live camera
  captures** · treat all photos alike · A screenshot picked from the gallery
  can be months old, so capture time is not transaction time; imports land in
  review instead of auto-committing with a wrong date.
- **2026-08-13 · `SettingsRepository` lives in `:core:database`, not `:app`**
  · an interface indirection per feature · Features need the period anchor
  directly; one shared DataStore beats a provider interface per module.
- **2026-08-13 · Room Gradle plugin instead of a bare `room.schemaLocation`
  KSP arg** · keep the KSP arg · The bare arg leaves the schema directory
  untracked by Gradle, so the build cache restored a truncated schema JSON
  and failed KSP in CI. Exported schemas are non-negotiable (B5 migration
  tests), so the plugin is the fix.
- **2026-08-13 · Baseline budgets (Phase 0 gate): APK size measured by CI
  artifact on every build; cold-start + SMS-receiver-under-doze + OCR spikes
  REQUIRE physical devices and cannot run in the cloud sandbox** — flagged as
  owner action items; mitigations (battery-exemption prompt flow, WorkManager
  reconcile scan) are still being built into the code as specified.
