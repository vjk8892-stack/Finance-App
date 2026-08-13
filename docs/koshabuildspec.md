# KOSHA — Offline-First Personal Finance App
## Master Build Specification & Phased Execution Plan

**Version:** 1.2 (v1.1 gap closure + uniform consistency pass — cross-references, numbering, and formula alignment verified end-to-end)
**Platform:** Android — Kotlin, Jetpack Compose, Material 3 (heavily customized)
**Architecture principle:** 100% offline-first. No server, no bank credentials, no network permission in core app.
**Codename meaning:** *Kosha* — Sanskrit: treasury/vault/sheath. The name signals security without shouting it.

---

# PART A — PRODUCT FOUNDATION

## A1. Product Vision

A calm, premium, fully offline personal finance tool that:
- Logs transactions automatically (SMS parsing + photo/OCR) and manually
- Treats the **savings gap** (actual income − actual spend) as the hero metric
- Advises on surplus allocation via a transparent rule engine — never product recommendations
- Respects the user: no red, no guilt, no gamification, no ads, no cloud

**Positioning:** Most finance apps compete on data density. Kosha competes on clarity and restraint.

## A2. Design Philosophy — "Calm Ledger"

| Principle | Concrete rule |
|---|---|
| One number that matters | Savings-gap Pulse is the hero; all else is one tap away |
| No anxiety design | **No red anywhere.** Overspend = muted amber. No streaks, badges, confetti |
| Provenance always visible | Every transaction shows a subtle source glyph (SMS / photo / manual / recurring) |
| Empty states disappear | Review queue chip renders ONLY when non-empty; no permanent clutter |
| Motion = feedback, not decoration | Numbers count smoothly; Pulse ring "breathes"; no shake/alarm animations |

### Visual language
- **Base:** near-black charcoal `#0F1114` (OLED-friendly, softer than pure black); warm off-white text `#F2EFEA`
- **Accent (used ONLY for money-flow visuals):** bioluminescent teal→violet gradient (`#2DD4BF` → `#8B5CF6`). Sankey, Pulse ring, forecast line only. Everything else monochrome
- **Amber for caution:** `#D97706` muted — never red `#FF0000`-family anywhere in the app
- **Typography:** tabular/monospaced figures for ALL amounts (no reflow during count-up animations); a quiet serif for insight sentences ("You're ₹4,200 ahead of last month"). Sans-serif for UI chrome
- **Vault mode:** darker skin variant, lock-forming transition animation, biometric gate before render

## A3. Locked Feature Set (Final)

### Core
- [x] Automatic transaction logging via SMS parsing
- [x] Manual transaction logging
- [x] Budget planner (with per-category limits + pre-exceed alerts — borrowed from Axio)
- [x] Fully offline, on-device

### Money Intelligence
- [x] Income setting + actual-spend tracking (SMS + manual combined)
- [x] Savings-gap ledger, persisted per period (`PeriodSummary`)
- [x] Rule-based investment/surplus advisory engine (allocation logic only — no instruments; keeps clear of SEBI advisory territory)
- [x] Sophisticated charts: Sankey flow, calendar heatmap, treemap, trend overlay
- [x] PDF export (financial-statement style) + share via email/intent

### Capture & Automation
- [x] Photo/OCR capture: UPI app screenshots + physical bills, on-device ML Kit
- [x] Dedup engine between SMS-parsed and photo-parsed entries ⚠️ **elevated to critical — top complaint against Axio is duplicate spends**
- [x] Line-item extraction from itemized bills (stretch within its phase)
- [x] Batch photo capture mode
- [x] Merchant fuzzy-matching auto-categorization
- [x] Home-screen widgets: one-tap entry + glanceable dashboard (borrowed from Axio)
- [x] Quick-add shortcuts: quick-settings tile + long-press app icon shortcut
- [x] Geofenced logging nudges (offline geofence, no cloud)

### Security & Privacy
- [x] Masked/encrypted account vault: per-field biometric/passcode reveal, copy with clipboard auto-clear, auto re-mask on idle, excluded from all exports

### Structural
- [x] Multi-account tracking (bank / cash / card / wallet / meal-card instruments)
- [x] Recurring transactions (bills, EMIs, subscriptions) + credit-card due-date reminders
- [x] CSV export (+ email delivery)
- [x] Local encrypted backup/restore (SAF)

### Forward-Looking Analytics
- [x] Cash-flow forecast (next-30-day projected balance per account)
- [x] Spending-leak detector (annualized micro-spend)
- [x] Anomaly flagging (outlier vs. merchant/category history)
- [x] What-if simulator (cut category X% → annual impact)
- [x] Financial health score (composite, trended)
- [x] Retroactive opportunity-cost simulator (user-entered benchmark rate)

### Goals & Planning
- [x] Goal-based sinking funds (rendered as filling "jars")
- [x] Debt/EMI payoff planner (avalanche/snowball simulation)
- [x] Net worth tracking (manual assets/liabilities)
- [x] Tax-relevant tagging (80C/80D/HRA — India-specific)

### Novel
- [x] Personal financial constitution (user-authored rules; violation trend tracking)
- [x] Spending DNA snapshot (radar-chart category fingerprint per month)
- [x] Mood-tagged manual entries (impulse/planned/necessary)
- [x] Warranty tracker from receipt OCR
- [x] Query-builder "personal SQL" view (filter/slice, saved views)
- [x] On-device query assistant — conversational "how much did I spend on X" (rule/template-based NLU in v1, NOT an LLM)

---

# PART B — TECHNICAL ARCHITECTURE

## B1. Stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Kotlin | |
| UI | Jetpack Compose + Material 3 | Custom design system ("Kosha DS") on top; design tokens in one file |
| DB | Room + SQLCipher | Whole DB encrypted; vault uses an ADDITIONAL key layer (see B4) |
| DI | Hilt | |
| Background | WorkManager + BroadcastReceiver (`SMS_RECEIVED`) | OEM battery-kill mitigation required (Phase 0) |
| OCR | ML Kit Text Recognition v2 (on-device) | No cloud fallback — by design |
| Charts | Vico (line/bar) + custom Compose Canvas (Sankey, heatmap, treemap, radar) | No good off-the-shelf Compose Sankey exists; hand-roll once, reuse |
| PDF | Android native `PdfDocument` | Avoids heavy PDF SDK licensing/size |
| CSV | Hand-rolled writer | Trivial; avoids Apache POI Android issues entirely |
| Biometrics | AndroidX Biometric | BiometricPrompt with device-credential fallback |
| Key storage | Android Keystore (StrongBox where available) | |
| Geofence | Play Services Geofencing API (works offline after registration) | Optional module — degrade gracefully if Play Services absent |
| Widgets | Glance (Compose for widgets) | |
| Camera | CameraX | Capture pipeline for OCR; gallery import via Photo Picker (no storage permission needed) |
| Notifications | NotificationManager + channels | `POST_NOTIFICATIONS` runtime permission (Android 13+) — see G9 permissions matrix |

**No `INTERNET` permission in the core app manifest.** This is a marketing-grade privacy claim: the app *cannot* phone home. If a future optional Drive-backup module is added, ship it as a separate dynamic feature or revisit explicitly.

## B2. Module Structure (Gradle)

```
:app                    — shell, navigation, DI graph
:core:designsystem      — Kosha DS: tokens, typography, components, motion
:core:database          — Room schema, DAOs, migrations, SQLCipher setup
:core:common            — currency/date utils, Result wrappers
:feature:ledger         — transaction list, entry UI, query builder
:feature:ingest:sms     — receiver, parser engine, pattern library
:feature:ingest:ocr     — camera, ML Kit pipeline, template matchers
:feature:ingest:review  — unified review queue (shared by sms + ocr)
:feature:budget         — budgets, alerts
:feature:income         — income sources, PeriodSummary engine
:feature:insights       — charts, analytics engines, health score, advisor
:feature:goals          — sinking funds, debt planner, net worth
:feature:vault          — secure vault (isolated; own encryption boundary)
:feature:export         — PDF, CSV, backup/restore
:feature:widgets        — Glance widgets, QS tile, app shortcuts
```

Rule: ingest modules NEVER write directly to the transaction table — everything flows through the unified Ingestion Pipeline (B3) so dedup/confidence logic can never be bypassed.

## B3. Unified Ingestion Pipeline (the trust-critical core)

```
[SMS Receiver]──┐
[OCR Capture]───┤──► Normalizer ──► Dedup Engine ──► Confidence Scorer ──┬─► Auto-commit (high conf.)
[Manual Entry]──┤        │                                               └─► Review Queue (low conf.)
[Recurring]─────┘        └── extracts: amount, type, account-last4,
                             merchant, timestamp, reference/UTR
```

**Dedup rules (v1):**
1. Exact UTR/reference match → merge unconditionally
2. Same amount + same account + timestamp within ±10 min → merge candidate; auto-merge if merchant fuzzy-matches, else surface as "possible duplicate" in review queue
3. Recurring-rule instance vs. detected real transaction (SMS/photo) in expected window → link, never double-count
4. Merged entries retain ALL sources as evidence (SMS text + photo thumbnail attached to one transaction)

**Confidence scoring:** each extracted field gets a score; transaction score = weighted min. Thresholds: ≥0.9 auto-commit, 0.5–0.9 review queue, <0.5 discard-with-log (viewable in a debug screen).

## B4. Security Model

Three-ring model:
- **Ring 0 (app lock, optional):** gates app open. BiometricPrompt with device-credential (PIN/pattern/password) fallback — the app does NOT maintain its own PIN store, so there is no custom "forgot PIN" flow to build or get wrong; recovery = device credential, which the OS owns. Configurable timeout: lock immediately / after 1 min / after 5 min in background. Enabled during onboarding step 7 or later in Settings.
- **Ring 1 (whole app data):** SQLCipher DB key generated on first run, stored in Android Keystore (StrongBox when available). Not tied to user auth — background workers (SMS receiver, recurring engine) must be able to write while the phone is locked.
- **Ring 2 (vault):** vault fields encrypted with a SEPARATE Keystore key with `setUserAuthenticationRequired(true)` and a 20-second authentication validity window. Vault breach ≠ ledger breach and vice versa.
- **Key-loss behavior (explicit):** if Keystore keys are invalidated (e.g., user removes device lock screen, or factory reset), Ring-1 data is recoverable only from a backup file; Ring-2 vault data is unrecoverable by design. Onboarding and vault-setup screens must state this in plain language.

Vault behaviors:
- Masked rendering by default everywhere (`•••• 1234`); reveal is per-field, never global
- Auto re-mask after 20s idle on a revealed field
- Copy → clipboard with `ClipDescription.EXTRA_IS_SENSITIVE` flag + auto-clear after 30s via WorkManager
- Vault table excluded from CSV/PDF export and from backup by default (opt-in inclusion with an extra warning)
- Screenshot blocking (`FLAG_SECURE`) on vault screens

SMS privacy:
- Sender allowlist FIRST — non-bank senders never parsed
- Raw SMS body NOT stored by default; only extracted fields (raw retention = opt-in debug toggle)
- Explicit onboarding screen: which senders, what's extracted, what never leaves the device

## B5. Data Model (Room)

```
Account         id, name, type(bank|cash|card|wallet|mealcard), last4?, 
                openingBalance, currentBalance, isActive, colorToken
                -- currentBalance = openingBalance + Σ(parent transactions).
                -- Recomputed, never independently mutated. If an SMS reports
                -- an available balance that disagrees, show a "reconcile"
                -- suggestion (adjustment transaction) — never silently overwrite.
                -- colorToken = index into the 8-swatch account palette (G3).

Transfer        id, fromTransactionId, toTransactionId
                -- links a debit in one account to a credit in another
                -- (self-transfer, card bill payment). Both legs excluded from
                -- income/expense/savings-gap math; included in balance math.
                -- Dedup engine proposes transfer links when amount matches
                -- and accounts differ within ±10 min.

Category        id, name, type(expense|income), icon, parentId?, isSystem

Transaction     id, accountId, categoryId?, amount, type(debit|credit),
                merchantRaw, merchantNormalized?, note?, timestamp,
                source(sms|ocr|manual|recurring), confidence,
                moodTag?(impulse|planned|necessary), taxTag?(80C|80D|HRA|...),
                recurringRuleId?, dedupGroupId?, parentTransactionId?,
                createdAt, updatedAt
                -- parentTransactionId enables SPLIT (C3 swipe action) and
                -- line-item extraction: children carry categories and sum to
                -- the parent amount; parent carries the real money movement.
                -- Category totals use children when present (else parent);
                -- account balance math uses parents only. Never both.

TransactionEvidence  id, transactionId, kind(smsText|photoUri|utr), payload(encrypted)

SmsPattern      id, senderIdPattern, regexTemplate, fieldMap(json), 
                bankLabel, isActive, version

OcrTemplate     id, appLabel(phonepe|gpay|paytm|generic-bill), 
                anchorKeywords(json), fieldHeuristics(json), version

RecurringRule   id, accountId, categoryId, amount?, merchantPattern,
                frequency, nextDueDate, autoLog(bool), isCreditCardDue(bool)

Budget          id, categoryId?(null=overall), period(weekly|monthly),
                limitAmount, alertThresholdPct(default 80), startDate

IncomeSource    id, name, amount, frequency(monthly|oneTime|variable),
                expectedDay?, accountId?

PeriodSummary   id, periodStart, periodEnd, expectedIncome, actualIncome,
                totalExpense, savingsGap, untrackedGap, closedAt, notes?
                -- persisted at period close; immutable ledger of history

FinancialGoal   id, name, targetAmount, targetDate?, allocated, priority,
                linkedAccountId?, kind(sinkingFund|emergencyFund)

DebtAccount     id, name, principal, rate, emiAmount, tenureMonths, startDate
                -- the authoritative source for any tracked loan/EMI. Net worth
                -- (below) auto-includes each DebtAccount's remaining balance
                -- as a liability — NEVER re-entered manually in AssetLiability.

AssetLiability  id, name, kind(asset|liability), value, valuationDate, notes?
                -- for everything else: property, gold, EPF, informal loans not
                -- modeled as a DebtAccount. UI enforces this split (Phase 9
                -- "Add liability" flow offers "loan with EMI?" → routes to
                -- DebtAccount instead) so the same debt is never entered twice.

VaultEntry      id, label, kind(account|card|custom), fieldsEncrypted(blob),
                createdAt, updatedAt        -- Ring-2 encrypted

ConstitutionRule id, ruleText, machineCheck(json)?, isActive
RuleViolation    id, ruleId, transactionId?, periodId?, timestamp

WarrantyItem    id, transactionId?, itemName, purchaseDate, warrantyMonths,
                expiryDate, receiptPhotoUri?

SavedQuery      id, name, filterJson, sortJson    -- query-builder views
```

Migration policy: destructive migrations FORBIDDEN after Phase 2; every schema change ships a tested `Migration` with an instrumentation test on a seeded fixture DB.

---

# PART C — SCREEN-BY-SCREEN DESIGN SPEC

## C1. Navigation

Bottom nav, 5 items: **Home · Ledger · [Add — center, camera-first FAB-style] · Insights · Vault**
Budget & Goals reached from Home budget rings and from Insights; Settings from Home top-right.

## C2. Home (top → bottom)

1. **Financial weather line** — serif, one sentence, computed from period state ("Calm skies — ₹12,400 ahead this month"). Tone bands: ahead / on-track / heads-up (amber). Never alarmist.
2. **The Pulse** — hero savings-gap number in a slow-breathing gradient ring (4s cycle). Tap → expands to income / expense / gap breakdown with count-up animation. Long-press → period switcher.
3. **Quick Add row** — 4–5 most-used category chips (one-thumb log with amount keypad sheet) + camera button as the visually primary action.
4. **Review Queue chip** — count + oldest-item age. RENDERS ONLY WHEN NON-EMPTY.
5. **Budget rings** — horizontal scroll of slim per-category rings; ring turns amber at `alertThresholdPct`. Tap → Budget detail.
6. **Forecast strip** — 30-day sparkline; quiet amber dot if projected negative before next expected income.
7. **Insight card** — ONE rotating card (leak / anomaly / opportunity-cost), swipe to cycle, tap → full insight in Insights hub.

## C3. Ledger

- Day-grouped chronological list; sticky month headers with month totals
- Each row: category icon, merchant, amount (tabular figures), source glyph (⌁ SMS / ▣ photo / ✎ manual / ⟳ recurring — defined in G12)
- Swipe right = recategorize; swipe left = edit/delete/split/**mark as transfer** (links to the matching opposite-leg transaction per the `Transfer` entity)
- Filter icon → **Query Builder**: field-operator-value rows (category = X AND amount > Y AND merchant CONTAINS Z), date presets, save as named view; saved views appear as chips atop the ledger
- Search bar doubles as the **query assistant** entry: typed natural questions ("dining last month") parsed by template NLU → translated to a filter + summary answer card

## C4. Add (center nav)

Opens directly to **camera** (UPI screenshot / bill) with three tabs: **Scan · Manual · Import**
- Scan: shutter + batch toggle; after capture → extraction preview with editable parsed fields + confidence highlights; confirm → pipeline
- Manual: amount-first keypad (biggest tap targets), then category grid, account, optional note/mood tag; ≤3 taps for a common entry
- Import: gallery multi-select (existing screenshots), same preview flow

## C5. Insights Hub

Sectioned single scroll, each expandable to full screen:
1. **Flow** — Sankey: Income → Categories → Savings (the accent-gradient showpiece)
2. **Rhythm** — calendar heatmap of daily spend intensity
3. **Shape** — category treemap + Spending DNA radar (this month vs. 3-month average overlay)
4. **Trajectory** — 12-month lines: income / expense / savings-gap
5. **Health** — composite score dial + trend; contributing factors listed with plain-language explanations
6. **Advisor** — rule-engine output: emergency-fund status → surplus split suggestion → "redirecting ₹X/mo covers goal in Y months" statements. Always shows its reasoning ("because your 4-month average surplus is ₹...")
7. **Leaks & Anomalies** — annualized micro-spends; outlier flags with dismiss/confirm (feedback tunes thresholds)
8. **What-If** — category sliders → live annual-impact projection
9. **Opportunity Cost** — pick category + user-entered benchmark rate → "would be worth ₹Y" (clearly labeled as hypothetical, user's own rate assumption)

## C6. Vault

- Entry: lock-forming transition + biometric prompt BEFORE any content renders; `FLAG_SECURE` on
- Darker skin variant of the DS
- Cards list (masked); per-field eye icon → biometric (if validity window expired) → reveal with 20s re-mask countdown ring; copy icon → sensitive-flagged clipboard + 30s auto-clear toast ("Copied — clears in 30s")
- Add/edit entry: label, kind, arbitrary field pairs
- Settings inside vault: re-mask timing, export-exclusion (locked ON by default)

## C7. Budget & Goals

- Budgets: per-category limit editor, threshold slider, period selector; pre-exceed notification copy is calm ("Dining is at 80% with 9 days left")
- Goals: sinking-fund **jars** that visibly fill; emergency-fund jar pinned first with months-of-expenses coverage label
- Debt planner: debts list → avalanche vs. snowball side-by-side simulation (payoff date + total interest for each)
- Net worth: manual assets/liabilities, trend line

## C8. Onboarding (first run)

1. Philosophy screen (one sentence: calm, offline, yours)
2. Accounts quick-setup
3. Income setup
4. SMS permission — full-disclosure screen (senders allowlist, extracted fields, "never leaves device"), with explicit **"Skip — manual only"** path that leaves the app fully functional
5. Historical SMS import offer (last 3/6/12 months) with progress + review summary
6. Notification permission (`POST_NOTIFICATIONS`, Android 13+) — framed by value ("budget heads-ups and bill reminders"), skippable; app functions fully without it (alerts simply don't fire; affected settings screens show a quiet "notifications off" note with a deep link to system settings)
7. Optional app lock setup (Ring 0)
8. Land on Home with real data already populated (the "wow" moment if import ran)

Permissions NOT requested at onboarding (requested in context, first use): Camera (first Scan tab open), Location (only if geofence nudges are enabled in Settings — never prompted otherwise). Gallery import uses the system Photo Picker — no storage permission at all.

---

# PART D — PHASED EXECUTION PLAN

Thin-vertical-slice strategy: get a usable app early (Phases 0–3), then widen. Each phase has an explicit **exit gate** — do not proceed until gates pass.

## Phase 0 — Feasibility & Foundations *(1–2 weeks)*
- Repo scaffold, module skeleton, CI (GitHub Actions: build + unit tests + lint)
- `CLAUDE.md` at root + per-module; `DECISIONS.md` log
- Kosha DS v0: tokens (colors, type scale, spacing, motion durations), theme, 5 base components (Amount text, Chip, Ring, Card, Keypad)
- SQLCipher + Room + Hilt wiring proof
- **SMS spike:** BroadcastReceiver reliability test on ≥2 real devices incl. one aggressive-OEM (Xiaomi/Vivo class); document battery-optimization mitigation (exemption prompt flow)
- **OCR spike:** ML Kit on 5 PhonePe/GPay screenshots + 5 paper bills; record raw accuracy
- **Baseline measurements (recorded in `DECISIONS.md`):** APK size with SQLCipher + ML Kit + CameraX + Vico all linked (this sets the realistic size budget — do NOT lock a size target before this number exists); cold-start time including SQLCipher open on the mid-range test device (sets the perf budget Phase 12 is held to)
- ⛔ **Exit gate:** SMS receiver fires reliably under doze on test devices; OCR extracts amount correctly on ≥8/10 samples; encrypted DB round-trips; baseline size/perf numbers recorded

## Phase 1 — Data Core & Manual Entry *(2 weeks)*
- Full Room schema (all entities above, even if unused yet — avoids migration churn)
- Seed system categories; account CRUD; manual transaction flow (≤3-tap path)
- Ledger list v1 (grouping, source glyphs, swipe actions)
- Mood tag + tax tag fields in entry UI (cheap now, enables Novel features later)
- ⛔ **Exit gate:** daily-driveable as a manual tracker; migration test harness in place

## Phase 2 — Ingestion Pipeline + SMS *(3 weeks)*
- Normalizer / Dedup / Confidence pipeline (built FIRST, SMS plugs into it)
- Pattern library: top 15–20 Indian bank/UPI sender templates as versioned data files
- Review queue UI; historical inbox import with progress
- Onboarding flow incl. SMS disclosure + skip path, notification permission step, and **Ring 0 app lock** (BiometricPrompt + timeout setting — small, but onboarding step 7 needs it to exist)
- ⛔ **Exit gate:** 2 weeks of live self-testing: zero duplicate commits, zero OTP/promo false-positives; unrecognized formats land in review, never silently dropped

## Phase 3 — Budgets + Income + The Pulse *(2 weeks)*
- Budget CRUD, threshold alerts (local notifications, calm copy)
- Income sources; period engine; `PeriodSummary` close flow (manual close + auto-close on rollover)
- Home v1: weather line, Pulse ring, quick-add row, review chip, budget rings
- ⛔ **Exit gate:** month-end close produces correct immutable summary against hand-verified numbers
- 🏁 **Milestone: the app is now a complete, shippable core product**

## Phase 4 — Photo/OCR Ingestion *(3 weeks)*
- Camera + gallery import; UPI templates (PhonePe/GPay/Paytm) + generic bill heuristics
- Extraction preview UI; batch mode; evidence attachment
- Dedup extended to photo↔SMS merge (UTR + amount/time window)
- Warranty capture prompt when a bill parse succeeds (item + months → `WarrantyItem`)
- ⛔ **Exit gate:** screenshot→committed transaction ≥90% no-edit rate on your real screenshots; photo of an SMS-covered transaction NEVER creates a duplicate

## Phase 5 — Recurring + Forecast *(2 weeks)*
- Recurring rules; auto-log vs. remind; credit-card due reminders
- Recurring↔actual linking (no double-count)
- 30-day cash-flow forecast engine + Home forecast strip
- ⛔ **Exit gate:** forecast matches hand-computed projection on fixture data; a recurring EMI detected via SMS links instead of duplicating

## Phase 6 — Insights Hub I: Charts *(3 weeks)*
- Custom Canvas: Sankey, calendar heatmap, treemap, radar (Spending DNA)
- Vico trend lines; Insights hub scaffold; Home rotating insight card
- ⛔ **Exit gate:** all charts render correct values from fixture data; 60fps scroll on a mid-range device

## Phase 7 — Insights Hub II: Intelligence *(3 weeks)*
- Spending-leak detector; anomaly engine (robust median/MAD z per G5, with dismiss feedback)
- What-if simulator; financial health score (documented formula); opportunity-cost simulator
- **Advisor rule engine:** emergency-fund-first logic → surplus split → plain-language reasoned output. Hard rule: allocation amounts only, never instruments/products. **Phase 7 ships with emergency-fund logic in a degraded mode** (asks the user to set an emergency-fund target amount as a lightweight standalone field, not yet the full `FinancialGoal` UI) since Goals management doesn't ship until Phase 9 — Phase 9 then upgrades the Advisor to read the real `FinancialGoal` record
- ⛔ **Exit gate:** every insight number reproducible by hand from the ledger; advisor output reviewed for advice-boundary compliance; health score's active-component set matches its phase (2-component now, confirmed NOT claiming debt/emergency-fund coverage it can't yet measure)

## Phase 8 — Vault *(2 weeks)* *(parallel-safe: can run alongside 6–7)*
- Ring-2 crypto; vault UI + mode transition; per-field reveal/re-mask/copy-clear; FLAG_SECURE
- Export-exclusion enforcement tests
- ⛔ **Exit gate:** vault data absent from DB dumps without Ring-2 key; absent from every export artifact; clipboard clears verified

## Phase 9 — Goals, Debt, Net Worth, Tax *(2–3 weeks)*
- Sinking-fund jars; emergency fund linkage to Advisor; debt avalanche/snowball simulator; net-worth entries + trend; tax-tag report view (80C/80D/HRA totals for FY)
- ⛔ **Exit gate:** debt simulations match spreadsheet-verified amortization

## Phase 10 — Export & Backup *(2 weeks)*
- CSV writer + share intent (email); PDF statement via `PdfDocument` (summary, category table, embedded chart bitmaps, recurring list)
- Encrypted backup/restore via SAF (versioned format; restore tested across app versions)
- ⛔ **Exit gate:** PDF renders correctly across 3 viewers; backup→wipe→restore is lossless (vault excluded by default, verified)

## Phase 11 — Query Power & Assistant *(2 weeks)*
- Query builder UI + `SavedQuery`; template-NLU assistant over the same filter engine ("how much on dining last month" → filter + answer card). Explicitly out-of-scope: LLM/embedding anything — v1 is deterministic templates
- ⛔ **Exit gate:** 20 canonical phrasings resolve correctly; unknown phrasings fail gracefully to the builder UI

## Phase 12 — Widgets, Shortcuts, Constitution, Polish *(2–3 weeks)*
- Glance widgets (dashboard + quick-add); QS tile; app-icon shortcuts
- Geofenced cash-log nudges (optional module, graceful degrade)
- Personal constitution: rule editor (machine-checkable subset auto-flags; free-text rules manual-review at period close); violation trend in Insights
- Onboarding final pass; performance profiling (cold start ≤ Phase-0 baseline + 30%; if baseline was ≤1.2s, hold the 1.5s line); accessibility pass (TalkBack on Pulse/rings; amounts announced with currency and sign; charts have contentDescription summaries)
- ⛔ **Exit gate:** full regression on 3 device classes; APK ≤ Phase-0 baseline + 8 MB (features added since should be mostly code, not native libs — investigate any excess)

**Deliberately deferred (not in v1):** P2P household sync, shared splits, Drive backup, any LLM-based assistant, multi-currency, non-Latin OCR scripts, exact-time alarms, iOS.

---

# PART E — ENGINEERING DISCIPLINE

- **`CLAUDE.md`** (root): stack, module map, design tokens location, pipeline invariants ("ingest modules never write Transaction directly"), current phase pointer
- **`DECISIONS.md`**: append-only log (date, decision, alternatives, why) — e.g., "no INTERNET permission," "no red," "PdfDocument over PDF SDK"
- **Testing pyramid:** parser/dedup/forecast/debt engines = pure Kotlin with exhaustive unit fixtures (real anonymized SMS samples as test resources); Room migrations = instrumented; charts = screenshot tests; one E2E happy path (manual entry → budget → period close)
- **Pattern library as data:** SMS/OCR templates in versioned JSON assets with their own test corpus, so new bank formats are data PRs, not code changes
- **Definition of Done per phase:** exit gate passed + tests green + `DECISIONS.md` updated + demo APK built by CI

---

# PART F — RISK REGISTER

| Risk | Severity | Mitigation |
|---|---|---|
| OEM battery managers kill SMS receiver | High | Phase-0 spike; exemption-request flow; WorkManager periodic reconcile scan of inbox as safety net |
| Bank SMS format changes silently | High | Confidence scoring routes unknowns to review (never silent drop); pattern versioning; reconcile scan catches missed txns |
| Duplicate transactions (Axio's top complaint) | High | Pipeline-level dedup as an architectural invariant, not a feature; Phase-2/4 exit gates test it explicitly |
| OCR accuracy on crumpled/thermal bills | Medium | Confidence thresholds + editable preview; bills default to review queue; batch mode reduces friction of confirming |
| Google Play SMS-permission policy | Medium | Core functionality declared honestly; manual-only mode fully functional (also the fallback if distribution is sideload/alt-store only) |
| Advisory feature ↔ regulatory boundary | Medium | Allocation-only rule engine, reasoning always shown, no products; boundary check in Phase-7 gate |
| Scope creep (38 locked features) | High | Phase gates are hard stops; deferred list is explicit; Phase 3 milestone = shippable core if motivation/time dips |

---

# PART G — CONCRETE SPECIFICATIONS (Gap Closure)

Everything previously referenced but undefined. An implementer should never have to invent a number or list — if it's not here, it goes to `DECISIONS.md` first.

## G1. Scope Decisions (previously implicit — now explicit)

| Decision | Ruling |
|---|---|
| Currency | **Single currency, INR, v1.** Amounts stored as `Long` paise (never floating point). Formatting: Indian digit grouping (₹1,23,456.78) via `NumberFormat` for `en-IN`. Multi-currency is OUT of v1 — deferred list updated. |
| Period definition | **Configurable month anchor**, default = calendar month (1st). User may set salary-day anchor (e.g., month runs 5th→4th) since Indian salaries commonly credit on a fixed day. One global anchor, not per-budget. Weekly budgets run Mon–Sun. |
| Financial year (tax tags) | India FY: **1 April – 31 March.** Tax report groups by FY, not calendar year. `taxTag` applies only to genuine outflows in expense/income transactions (e.g. insurance premium debits, HRA-relevant rent). Money moved into an ELSS/PPF investment is a `Transfer` (not an expense) per B5 and is therefore untagged here — it belongs in a future net-worth/investment-tracking view, not the expense tax-tag report. |
| Language | English UI v1. All user-facing strings in resources from day one (localization-ready, not localized). |
| OCR script | ML Kit **Latin** recognizer v1 (Indian bank SMS and virtually all printed bills/UPI screens are Latin-script). Devanagari/Kannada recognizers = deferred. |
| Backup file format | Single `.kosha` file = ZIP containing `manifest.json` (schemaVersion, appVersion, createdAt, checksum) + SQLite dump + evidence images, all AES-256-GCM encrypted with a key derived (PBKDF2, ≥600k iterations) from a user-chosen backup passphrase. Passphrase ≠ app PIN; shown once with a "write this down" screen. Restore supports same-or-older schemaVersion; migrations run post-restore. |
| Timestamps | Stored as epoch millis UTC + recorded zone offset; displayed in device zone. Matters for SMS-vs-photo dedup windows around midnight. |

## G2. Seed Categories (Phase 1 fixture)

Expense (16): Food & Dining · Groceries · Transport · Fuel · Shopping · Bills & Utilities · Rent · EMI & Loans · Health · Insurance · Education · Entertainment · Subscriptions · Travel · Personal Care · Construction & Home *(yes, deliberately a first-class default — also useful to most Indian users renovating/building)*
Expense system-reserved (3): Transfers *(hidden from budgets)* · Cash Withdrawal *(see below)* · Uncategorized
**ATM withdrawal semantics:** an ATM debit SMS is auto-proposed as a `Transfer` bank→Cash account (money moved, not spent). The Cash Withdrawal category exists only as a fallback when the user has no Cash account; if used, it IS excluded from spend analytics (money isn't spent yet) — actual spending is logged manually from the Cash account. Either path avoids the classic double-count of withdrawal + cash spend.
Income (5): Salary · Business · Interest & Dividends · Refunds & Cashback · Other Income

Each ships with icon token + default sort order. `isSystem=true` rows are non-deletable (rename allowed). Sub-categories: user-creatable from day one (schema already supports `parentId`).

## G3. Account Color Palette (`colorToken` 0–7)

Muted, desaturated tones that sit on charcoal without competing with the accent gradient:
`0 slate #64748B · 1 sage #6B8F71 · 2 dusk #7C6F9B · 3 sand #A8916C · 4 steel #5E7A8A · 5 rose-ash #96706F · 6 moss #77836A · 7 graphite #52565C`
Auto-assigned round-robin at account creation; user-changeable. Used in: account chips, ledger row left-edge tick, per-account forecast lines. Never used for amounts or semantic states.

## G4. Financial Health Score (0–100, computed at period close, shown with trend)

`Score = 35·SavingsRate + 25·EmergencyCover + 20·BudgetDiscipline + 20·DebtLoad`

- **SavingsRate** = clamp(savingsGap / actualIncome, 0, 0.40) / 0.40 — full marks at a 40% savings rate
- **EmergencyCover** = clamp(emergencyFundBalance / avg. 3-month expenses, 0, 6) / 6 — full marks at 6 months
- **BudgetDiscipline** = 1 − (over-budget categories / categories with budgets); = 0.5 neutral if no budgets set
- **DebtLoad** = 1 − clamp(monthly EMI outflow / actualIncome, 0, 0.5)/0.5 — zero marks at 50% EMI-to-income
- Fewer than 2 closed periods → show "collecting data" instead of a score (no fake precision)
- **Phase-sequencing rule:** EmergencyCover and DebtLoad read from `FinancialGoal`/`DebtAccount`, whose management UI ships in Phase 9. Until then, those two components are excluded from the formula (weights redistributed proportionally across the remaining components) rather than silently scoring as "perfect" on empty tables — a zero-debt score must mean verified zero debt, never "no UI to enter debt yet." Phase 7 ships a 2-component score (SavingsRate + BudgetDiscipline, reweighted to 100); Phase 9 upgrades it to the full 4-component formula.
- Formula displayed in-app on tap ("How is this calculated?") — transparency is the feature, including which components are active

## G5. Anomaly Engine Parameters

- Scope: per normalized merchant if ≥5 prior transactions, else per category if ≥8 prior; else **inactive** (no flags — cold-start honesty)
- Method: robust z via median/MAD (resists outliers better than mean/σ on small personal samples): flag if |amount − median| / (1.4826·MAD) > 3, AND absolute deviation > ₹200 (suppresses trivial flags on small amounts)
- History window: trailing 6 months, same merchant/category
- Feedback: "expected" dismissal excludes that transaction from future baselines; two dismissals for the same merchant raises its threshold to 4
- Hard cap: max 3 active anomaly flags shown at once (calm-design rule)

## G6. Cash-Flow Forecast Algorithm (30-day, per account + total)

`Projected(d) = today's balance + Σ expected income credits (next 30d) − Σ scheduled recurring outflows (next 30d) − dailyDiscretionaryRate·d`
- dailyDiscretionaryRate = trailing-60-day average daily spend EXCLUDING recurring-linked and transfer transactions (they're already counted explicitly)
- Income: expected `IncomeSource` credits placed on `expectedDay`; if income is `variable`, use trailing 3-month median
- Fewer than 21 days of history → show recurring-only projection labeled "early estimate"
- Amber flag threshold: projected balance < ₹0 (per account) before next expected income credit

## G7. Merchant Normalization & Fuzzy Matching

1. Normalize: uppercase → strip UPI/txn noise tokens (`UPI`, `POS`, ref numbers, trailing digits/dates, `PVT LTD`, city suffixes) → collapse whitespace
2. Exact match on normalized form → same merchant
3. Else Jaro-Winkler ≥ 0.90 against known normalized merchants → auto-link; 0.82–0.90 → suggest in review queue; below → new merchant
4. Auto-categorization: new transaction inherits the category used on ≥3 of the last 4 transactions of the linked merchant; otherwise lands Uncategorized (never guesses from merchant name semantics in v1)
5. User recategorization of a merchant 2× consecutively → offer "always use this category for X"

## G8. Query Assistant — v1 Template Grammar (Phase 11 gate list)

Slots: `{category|merchant} {sum|count|avg|max|list} {period}` where period ∈ {today, yesterday, this week, last week, this month, last month, last N days/weeks/months, month-name, this year, FY}
The 20 canonical phrasings for the exit gate include: "how much on dining last month" · "total spent this week" · "biggest expense in March" · "average grocery spend" · "how many Swiggy orders last month" · "list transactions above 2000 this month" · "what did I save in June" · "salary received this year" · "EMI total this FY" · "cash spent last 30 days" *(full 20-item list to live in the Phase-11 test fixture)*
Unknown phrasing → "I couldn't parse that — here's the filter builder" with slots pre-filled from whatever WAS recognized.

## G9. Permissions Matrix (complete)

| Permission | When asked | If denied |
|---|---|---|
| `RECEIVE_SMS`, `READ_SMS` | Onboarding step 4 (full disclosure) | Manual + photo modes fully functional; re-promptable from Settings |
| `POST_NOTIFICATIONS` (13+) | Onboarding step 6 | All features work; alerts silently disabled + settings note |
| `CAMERA` | First open of Scan tab | Import tab (Photo Picker) still works |
| `ACCESS_FINE_LOCATION` + background | Only when user enables geofence nudges in Settings | Feature stays off; nothing else affected |
| `USE_BIOMETRIC` | Normal-level, no prompt | Device-credential fallback automatic |
| Storage | **Never** | Photo Picker + SAF cover gallery, backup, export with zero storage permissions |
| `INTERNET` | **Absent from manifest** | — (the point) |

Also declared: `SCHEDULE_EXACT_ALARM` NOT used — reminders use inexact WorkManager windows (±15 min is fine for bill reminders; avoids the Play Store exact-alarm justification burden).

## G10. PDF Statement Layout (Phase 10)

A4 portrait, 3 sections/pages: (1) Period header + Pulse summary block (income/expense/gap, weather-line sentence) + health score dial bitmap; (2) category table (budgeted vs actual vs Δ, tabular figures, amber highlight only) + top-10 merchants; (3) trend chart bitmap (12-month trajectory) + recurring/EMI list + footer "Generated on-device by Kosha — data never left this phone." Charts render to bitmap at 2× for print sharpness. Vault data structurally absent (exporter has no code path to the vault module).

## G11. Widget & Shortcut Contents (Phase 12)

- **Dashboard widget (4×2):** weather line + Pulse number + top-2 budget rings; taps deep-link. Refresh: WorkManager 30-min window + immediate on app-driven data change
- **Quick-add widget (2×1) / QS tile / icon long-press shortcuts (×3: Add expense · Scan · Vault):** open the amount-first keypad directly (bypass Home)
- Widgets respect privacy mode: masked amounts (`₹ ••••`) when enabled

## G12. Remaining Small Clarifications

- **Source glyphs (C3):** SMS = ⌁ message glyph · photo = ▣ frame glyph · manual = ✎ · recurring = ⟳ — final glyphs from the icon set in Phase 1, but reserve the four slots now
- **Review-queue notification:** at most ONE daily digest ("3 transactions waiting"), 8 PM local, only if queue non-empty — never per-item pings
- **Historical import + dedup:** inbox import runs through the same pipeline; a re-import is idempotent (UTR/reference + amount/timestamp matching makes it safe to re-run)
- **Warranty reminders:** notification at 30 days and 7 days pre-expiry (needs only `POST_NOTIFICATIONS`)
- **Constitution `machineCheck` JSON:** same filter grammar as `SavedQuery` + a comparator (e.g., `{filter: {category: Dining, period: day}, assert: {sum_lte: 2000}}`) — checked at pipeline commit time; free-text rules surface at period close for manual self-review
- **Emergency-fund months target:** default 3, user-adjustable 3–12; Advisor (C5.6) and EmergencyCover (G4) both read this setting
- **`isSystem` categories in budgets:** Transfers and Cash Withdrawal are excluded from budget selection and from all spend analytics denominators

---

*End of specification v1.2. All referenced formulas, seeds, palettes, permissions, and algorithms are now defined; phase-ordering dependencies (health score/advisor vs. Goals&Debt) and the DebtAccount/AssetLiability boundary are resolved. Next artifacts on request: root `CLAUDE.md`, Phase-0 repo scaffold, SMS pattern JSON schema + first 5 bank templates.*
