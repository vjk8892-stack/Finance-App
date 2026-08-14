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

## Phase completion pass (2026-08-13)

- **2026-08-13 · Vault exclusion from backups is enforced on a snapshot, not
  the live DB** · filter at write time · The backup archives a database
  *file*, so a `DELETE ... WHERE 0` against the live connection excluded
  nothing. `VACUUM INTO` a temp copy, delete `vault_entries` from THAT copy,
  archive it, discard it.
- **2026-08-13 · CSV writer neutralizes leading `= + - @`** · plain RFC 4180
  quoting · A merchant name captured from an SMS is untrusted text; without
  the guard it executes as a formula when the export is opened in Excel.
- **2026-08-13 · Advisor output is asserted product-free by a test** ·
  reviewer judgement · The SEBI advisory boundary (spec F risk register) is
  easy to erode one helpful sentence at a time; a test that fails on
  "mutual fund", "ELSS", "SIP" etc. makes the boundary mechanical.
- **2026-08-13 · Glance `actionStartActivity` Intent overload lives in
  `androidx.glance.appwidget.action`** · — · The base `androidx.glance.action`
  one only accepts a ComponentName; noted because the import is easy to get
  wrong and the error message is unhelpful.
- **2026-08-13 · Device-dependent exit gates are documented, not assumed** ·
  quietly marking phases done · `docs/DEVICE_GATES.md` lists every gate that
  needs real hardware (SMS under doze, OCR accuracy on real screenshots,
  vault crypto, PDF viewers, backup round-trip, perf/size baselines).
- **2026-08-13 · `INTERNET` is explicitly removed via `tools:node="remove"`,
  and a CI check fails the build if it reappears** · trusting that we never
  declare it · Verifying a real APK showed `android.permission.INTERNET` in
  the merged manifest: manifest merger pulls it from a transitive dependency
  (ML Kit / Play Services), so the app COULD open sockets despite the spec-B1
  claim that it cannot. Nobody typed it, and no code review would have caught
  it — which is precisely why `scripts/check_no_internet.py` now inspects
  every built APK in CI. On-device ML Kit text recognition works without it.
  `ACCESS_NETWORK_STATE` is left in place: it only permits *querying*
  connectivity, WorkManager needs it, and without INTERNET it grants no
  ability to transmit.

## Bank-agnostic capture (2026-08-14)

- **2026-08-14 · SMS classification is bank-agnostic; the pattern library is
  demoted to a precision layer** · one curated regex per bank format (spec
  Part E as written) · On a real phone the library approach failed the way it
  was always going to: whether a message was a transaction depended on
  whether someone had written a regex for that bank, so unlisted banks were
  invisible and a bank rewording its alerts broke capture overnight. Every
  transaction alert has the same skeleton — an amount, a direction verb, and
  optionally an account tail, counterparty and reference — and
  `TransactionClassifier` detects THAT. The library still runs: when a
  curated pattern for the sender also matches, its captures win, its
  confidence replaces the generic one, and the bank gets named. It can no
  longer decide that a message *is* a transaction, and its absence can no
  longer hide one.
- **2026-08-14 · The spec-B4 sender allowlist becomes a sender-SHAPE gate** ·
  fixed list of bank sender codes · The privacy promise B4 is protecting is
  "personal messages are never parsed". Indian bank alerts arrive from
  alphanumeric DLT headers (`VM-HDFCBK`, `AD-ICICIB`); people text from
  numbers. Gating on that shape keeps the promise exactly — nothing numeric
  is ever read — while working for banks nobody has listed. This is a
  deliberate deviation from B4's literal wording, recorded here rather than
  quietly made.
- **2026-08-14 · Direction is read from the verb, never from a balance line** ·
  inferring from balance movement · Many alerts quote no balance at all, so
  anything keyed off "Avl Bal" simply loses those messages. Where a verb is
  two-way ("transferred") the preposition decides AND the result is flagged
  inexplicit, which scores it into the review queue instead of guessing
  silently.
- **2026-08-14 · An unmatched account tail creates its own account instead of
  falling back to the user's first one** · the previous silent fallback ·
  People hold accounts at several banks and typically add one. The old
  `resolveAccountId` attributed any unmatched tail to the first bank account,
  which folded a second bank's spending into the first account's balance —
  data corruption that looked like a working app. Now: a matching tail wins;
  an unrecognised tail becomes a `•• 1234` account; no tail with more than
  one account on file stays unattributed. The last two cases are forced to
  PENDING_REVIEW regardless of parse confidence, because unconfirmed
  attribution is worse than a row waiting for a glance. Capped at 12
  discovered accounts so a noisy parse cannot fill the account list.
- **2026-08-14 · Scans accept a start date, not just "last N months"** · — ·
  "Last N months" is the wrong frame when you know the date that matters (the
  day the account was opened, the day you started using Kosha). The picker
  reports UTC midnight and is re-anchored to local midnight, or an IST
  evening message on the boundary day is silently skipped.
- **2026-08-14 · The review queue states the actual reason a row is waiting** ·
  one generic "low confidence" line · Every row reading "Parsed with low
  confidence" tells the reader nothing about what to check; the committer
  already records why, so it is now shown.
