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
- **2026-08-14 · Onboarding asks for the account's last 4 digits, and the
  Accounts screen can edit them** · treating the tail as a cosmetic detail ·
  It was never collected during onboarding, so the one account every install
  starts with had `last4 = null` and tail matching could not work at all —
  which is why every captured message looked like it belonged to the same
  account. The tail is what multi-account attribution runs on, so it is now
  asked for (still optional) and correctable afterwards.
- **2026-08-14 · A lone account with no tail on file ADOPTS the first tail it
  sees, in review** · creating a second account for it · Given the above,
  existing installs would otherwise treat every message as a new bank and
  turn the whole ledger into a review queue. One account, no tail recorded,
  a tail arriving — that is that account, near enough to propose and cheap to
  correct. Adoption happens once; afterwards ordinary matching applies and a
  genuinely different bank gets its own account.

## Post-install feedback pass (2026-08-14)

- **2026-08-14 · Keyword merchant→category rules bootstrap categorization** ·
  Uncategorized until the G7 3-of-4 rule has history · On a real install every
  captured rupee sat in Uncategorized, because the learned rule cannot
  bootstrap. That is not just cosmetic: Flow, Shape, What-if, Opportunity cost
  and the radar all key off category MIX, so with one category they each
  degenerate to a single 100% slice and the Insights tab says nothing. Keyword
  rules now provide a first guess, ranked strictly BELOW the learned rule, so
  a recategorization still wins permanently. Keywords match on token
  boundaries — substring matching filed LICIOUS under Insurance via "lic".
- **2026-08-14 · "from X" is only a counterparty on a CREDIT** · treating it as
  the payee on any direction · On a debit, "debited from HDFC Bank XX0773"
  names the user's own account. Capturing it produced ledger rows titled after
  the user's own bank, and a leak report recommending they watch their spending
  at "A C NO". The noise filter also only rejected candidates that were
  ENTIRELY one noise word, so "a/c no" passed; it now rejects any candidate
  containing account or bank vocabulary or a masked identifier.
- **2026-08-14 · Charts must not fill with the card's own background colour** ·
  `CharcoalRaised` as a treemap/heatmap tone · `CharcoalRaised` IS the card
  surface, so every even-index treemap slice was invisible and a
  single-category treemap rendered as an empty box — which is exactly what a
  fresh install produces. Zero-spend heatmap cells had the same problem, so the
  month grid had holes and stopped reading as a calendar. Both ramps now start
  above the card surface, the treemap outlines each slice, and the heatmap has
  a weekday strip.
- **2026-08-14 · Tapping a ledger row shows the message behind it** · swipe-only
  row actions · A row that reads "a/c no" is untrustworthy AND unfixable
  without the source text — you cannot tell whether Kosha misread the message
  or the bank phrased it oddly. The sheet shows the parsed fields plus the
  verbatim SMS. When raw retention was off at capture time it says so and
  offers the toggle, while being explicit that it only affects future scans:
  a discarded message cannot be recovered.
- **2026-08-14 · Anomaly rows name what they are about** · a fixed "Bigger than
  usual" label on every row · Three identical rows cannot be acted on. The
  flag now carries the merchant, or the category name for category-scope
  flags.

## Refinement pass (2026-08-14)

- **2026-08-14 · The savings gap navigates to the ledger; the breakdown is
  always visible** · tap-to-expand a breakdown · The gap is a question —
  "where did it go?" — and the tap answered it with two more numbers and then
  nothing. Income/spent now show unconditionally (they fit), and the tap goes
  to the transactions. "See transactions" and "Budgets" sit directly under the
  ring, because reading the gap and acting on it are the same moment.
- **2026-08-14 · The ledger has a money-in / money-out filter** · scrolling and
  reading the sign · Credits and debits interleave, so answering "what came in
  this month?" meant scanning the whole list. The empty state distinguishes
  "nothing recorded" from "the filter hides everything", which otherwise reads
  as a bug.
- **2026-08-14 · Day headers are readable and carry the day's total** ·
  `OffWhiteFaint` day labels · The date is how you navigate a ledger, and it
  was drawn in the hint/disabled tone — the dimmest thing on screen. Today and
  Yesterday now get full contrast, older days one step down, each day is
  separated by a rule, and the day's net sits on the right.
- **2026-08-14 · The review queue offers every category, scrollable** · the
  first three · The right category was usually not among the first three, so
  approving a row left it uncategorized anyway — the queue was creating the
  work it was meant to remove.
- **2026-08-14 · The detail sheet says WHY there is no message** · a single
  generic line · A manual entry, a recurring rule and a discarded SMS are
  three different situations, and only one of them is worth acting on.
- **2026-08-14 · What-if and Opportunity cost prompt for a category** · a bare
  chip row · Both cards rendered as a title and three chips with no indication
  they did anything until one was tapped, which read as unfinished.

## Second refinement pass (2026-08-14)

- **2026-08-14 · The original message is read back from the INBOX, not from a
  stored copy** · the spec-B4 retention toggle · Showing the message only
  worked if the user had switched on a debug setting BEFORE the message
  arrived, which is exactly backwards from when they need it — so in practice
  the detail sheet always said "not kept". The transaction already stores the
  receipt time, so the message can be found again on demand via
  `OriginalMessageSource`. Nothing extra is written, B4's promise is intact,
  and it works for every SMS row rather than only future ones. The interface
  lives in `:core:database`, the implementation in `:feature:ingest:sms` (which
  owns the permission), bound in `:app`; the lite build degrades to null.
- **2026-08-14 · Ledger totals are net CHANGE, not spend-positive** ·
  debits − credits · With the "Money in" filter on, a screen showing +₹15,000
  and +₹6,386 was headed "−₹21,386" — the header contradicted every row under
  it. Credits positive / debits negative matches the sign the rows already
  use, so the header agrees under every filter.
- **2026-08-14 · Date headers get their own type token and full contrast** ·
  `Label` at 12sp in a muted tone · Dimming older days was still dimming the
  thing you scan a ledger by. `KoshaType.SectionHeader` (15sp semibold), full
  `OffWhite`, plus a teal tick — the first attempt only lifted the tone and
  the user reported it still did not read.
- **2026-08-14 · Month / account / category filters live in a sheet** · more
  inline chip rows · Three scrolling rows above the list would push the
  transactions off screen, which defeats the point of filtering them. The
  direction filter stays inline because it is flipped constantly; the rest sit
  behind one chip that shows how many are active.
- **2026-08-14 · Month-by-month bars with a budget line lead the Insights tab** ·
  opening with the Sankey · Sankey, treemap and radar all answer "how is this
  month divided?", which needs categories to mean anything and says nothing on
  a fresh install. "Am I spending more than usual, and more than I meant to?"
  needs no categories at all, so it goes first.
- **2026-08-14 · One place decides how an account is written** ·
  `name + last4` at each call site · Discovered accounts are already named
  "•• 1234", so appending the tail printed "•• 5272 ·· 5272". `displayName()`
  appends only when the name does not already carry the tail.
- **2026-08-14 · The accounts screen shows the total and flags untailed
  accounts** · a bare list of balances · The total is what people open the
  screen for, and an account with no digits silently cannot be matched to any
  bank message — worth saying where it is fixable.

## Making the existing data usable (2026-08-14)

- **2026-08-14 · Categorization can be re-applied to rows already captured
  (`RetroCategorizer`)** · telling the user to re-scan · Categorization runs at
  commit time, so improving the rules only helps messages that arrive
  afterwards — the user's history, which is the part they care about, keeps
  whatever it got on the day. Asking them to re-scan pushes the cost of our
  late rule onto them. The pass walks committed rows with no category and
  applies the same two signals in the same order as the committer: the user's
  own history for that merchant first, keyword rules second. Decisions are made
  once per merchant, and rows that already have a real category are never
  touched, so it is idempotent.
- **2026-08-14 · The uncategorized bucket is split into the merchants inside
  it** · one "Uncategorized" slice · Every category-shaped visual divides
  `spendByCategoryName`, so one bucket holding most of the spend collapses all
  of them to a single slice and the Insights tab says nothing on real data. The
  merchant names ARE known even when the category is not, so the bucket is
  broken into its top merchants — turning "₹90,921 Uncategorized" into a
  readable ranking using data already on the device. Categorized spend keeps
  its category name, so this quietly stops mattering as categories fill in.
- **2026-08-14 · The review queue groups by reason and approves in bulk** ·
  one row at a time · A hundred-item queue reviewed row by row is a queue
  nobody finishes — and everything in it is excluded from every total until
  cleared, so an unread queue quietly makes the rest of the app wrong. Rows
  waiting for the same reason are one decision. Each group carries its count
  and net so approving is informed, and possible duplicates are never offered
  for bulk approval because merge-or-keep is a judgement about two specific
  rows. Balances are recomputed once per affected account, not once per row.
- **2026-08-14 · Transactions are editable: amount, direction, date, name,
  note, category** · recategorize-or-delete · Everything in the ledger came
  from a parser and no parser is right every time; without editing, a row with
  a wrong amount had to be deleted and retyped, losing the link to the message
  it came from. Renaming a merchant renormalizes it, or categorization and
  dedup keep matching the old name. Changing the day keeps the time of day so
  dedup windows are not silently shifted.
- **2026-08-14 · Recategorizing offers to apply to the whole merchant** ·
  per-row only · Categorizing one row of a merchant you have twenty of is not
  really a per-row decision.

## Wrong-row swipe and self-transfers (2026-08-14)

- **2026-08-14 · Ledger rows carry stable keys and `rememberUpdatedState`
  callbacks** · a keyless `items(count)` with lambdas captured at first
  composition · `rememberSwipeToDismissBoxState` keeps the callback it was
  first given. A LazyColumn reuses a row's slot for whatever scrolls into it,
  so the retained callback went on referring to the row that used to be there:
  swiping ₹15,000 opened the action sheet for a ₹5,000 row — with Delete in
  it. This was a data-loss bug, not a display glitch. Fixed at both ends:
  stable `key = txn.id` so the slot is not reused across rows, and
  `rememberUpdatedState` so the retained lambda reads the current handlers.
- **2026-08-14 · Credit card bill payments and self transfers are Transfers,
  not income** · reading the direction verb alone · The card issuer texts "we
  have received a payment towards your card", which reads exactly like income.
  Counting it that way inflates income by the payment AND the original card
  spending is already counted — the same rupees land in the totals twice, with
  the wrong sign. The paying side says "payment towards credit card", a spend
  that never happened. Both legs are filed under Transfers, which the analytics
  queries already exclude, so balances stay right and the totals stop
  double-counting. The detection anchors on "payment TOWARDS a card" rather
  than the word "card", so an ordinary card spend is untouched.
- **2026-08-14 · The Edit sheet can mark anything as "between my own
  accounts"** · relying on detection alone · No parser can know that the
  account at the other end is also yours — a transfer to your own account at
  another bank is indistinguishable from paying a person. Detection covers the
  phrasings that are decidable; this covers the rest, and is one tap.

## Numbers that agree with each other (2026-08-14)

- **2026-08-14 · Ledger month and day totals honour the same exclusions as
  everything else** · a raw signed sum of the visible rows · Home said
  "₹84,199 spent" for August while the ledger header said "−₹77,812" for the
  same August. The difference was exactly one credit-card bill payment: the
  savings gap, budgets and charts all exclude transfers, and this header alone
  did not. Two numbers for the same month that disagree make BOTH
  untrustworthy — a user cannot tell which one is lying. The header now uses
  the same basis, and states the transfer volume it left out so the difference
  is never a mystery.
- **2026-08-14 · The month-by-month chart was reversed twice** · — ·
  `InsightsRepository` already returns the trend oldest → newest; the screen
  reversed it again, so the axis read Aug, Jul, Jun … Sept, the "current"
  month was actually the oldest, and the comparison sentence named the wrong
  month. Reported as "charts are not so accurate", and they were.
- **2026-08-14 · Chart slices open the transactions behind them** · charts as
  read-only pictures · A slice is a claim — "₹16,173 on EMI & Loans" — and the
  only way to check a claim is to see the rows that make it. Tapping a
  category line or a month bar lands on exactly those rows, pre-filtered,
  which also makes a wrong-looking number diagnosable instead of merely
  annoying. The month key travels on the bar rather than being looked up by
  index, because the bar list is a filtered view of the trend.
- **2026-08-14 · Home states its period's actual dates, and hands them to the
  ledger** · both screens saying "August" and meaning different windows · A
  period is anchored on the user's salary day, so Home's "August" can be
  5 Aug – 4 Sep while the ledger's "August 2026" is the calendar month. Same
  word, different windows, nothing on screen to tell them apart — which is a
  standing generator of "these two totals disagree" reports whatever else is
  fixed. Home now spells out the range whenever it is not a calendar month,
  and "See transactions" (and the gap ring) carry that exact window into the
  ledger, so the figure can be checked against the rows that produced it.
  Recorded because the FIRST diagnosis of the reported mismatch — a transfer
  counted in one place and not the other — was inference from a coincidence
  and turned out to rest on a false premise; the user had already deleted the
  transaction in question.

## Design pass and the remaining gaps (2026-08-14)

- **2026-08-14 · Categories get identity colours; the account palette stays
  desaturated** · monochrome everything (spec A2) · Thirty ledger rows with the
  same grey disc give the eye nothing to group on, and a treemap of one hue is
  a wash. Category colour is derived from the category NAME, so the same
  category is the same colour in the ledger, the charts and the budgets without
  a schema change. The one hard rule holds: no red anywhere — the warm end of
  the palette stops at orange. Recorded as a deliberate widening of spec A2 at
  the product owner's request.
- **2026-08-14 · A selected chip is a filled accent pill** · one step of grey ·
  Selected and unselected differed by a single tone on border and fill, so the
  active filter was invisible — and an invisible active filter is
  indistinguishable from missing data, which is a bug report waiting to happen.
- **2026-08-14 · Screen titles get their own type token** · one `Title` for
  screens and card headings alike · Identical weight for "Ledger" and a card
  heading flattens the hierarchy; a screen should announce itself.

## Phases A–D (2026-08-14)

- **2026-08-14 · Undo is a captured snapshot re-inserted at its original id** ·
  a soft-delete column and a migration · Room only auto-generates an id when it
  is 0, so re-inserting a captured row with its id restores it exactly and
  anything still referencing it stays valid — no schema change, and the schema
  is frozen after Phase 2. Time-boxed on purpose: it covers the slip, not the
  change of mind.
- **2026-08-14 · Search filters as you type, alongside the NLU** · NLU only ·
  The template NLU needs a full known merchant name inside the phrase, so
  "swig" matched nothing and the search bar read as broken to anyone who tried
  it. Substring matching over name, category, account, note and reference runs
  live; the NLU still runs on submit for "dining last month".
- **2026-08-14 · An account with history is deactivated, not deleted** ·
  refusing removal, or cascading · The transactions FK is RESTRICT, so deleting
  an account with rows would fail — but Kosha CREATES accounts on its own from
  message tails, so a wrong one must be removable. Deactivating hides it
  everywhere while its rows stay attributable; only a genuinely empty account
  is deleted, which is the common case for a mis-parsed tail.
- **2026-08-14 · The account statement shows the balance as arithmetic** · a
  bare list of transactions · Several balances looked wrong and there was no
  way to find out why — a number you distrust and cannot audit is the worst
  combination. The statement states `opening + in − out = now` with real
  figures, drawn from the same committed-parents set the stored balance is
  computed from, so the two reconcile by construction rather than by
  coincidence.
- **2026-08-14 · Every chart slice is a link** · the bar chart only · A slice
  is a claim about part of the ledger. Heatmap day, treemap slice, month bar,
  category line, leak and anomaly rows all open the transactions behind them.
  Treemap hit-testing records the rectangles during the draw pass rather than
  recomputing the layout, so taps cannot drift from what is on screen.
- **2026-08-14 · Treemap slices wear the category colour** · a grey ramp ·
  Same colour as the ledger icon for that category, so the eye can carry a
  category between screens without re-reading labels.
- **2026-08-14 · A queued row can be fixed BEFORE it is approved** ·
  approve-then-edit · Correcting a misread amount meant putting a number you
  know is wrong into the ledger and trusting yourself to come back for it. The
  queue also sorts now — oldest first by default, since those are the ones
  going stale.
- **2026-08-14 · The Ledger has its own add button** · the Add tab only ·
  Noticing a missing entry happens while looking at the ledger, so adding one
  should not start with finding another tab.

## Quality pass findings (2026-08-14)

Three defects found by reading the phase diffs rather than by a failing test —
all in the new undo path, which is exactly where a silent defect is worst:

- **Undo of a split transaction dropped its children.** `deleteWithChildren`
  removes the split lines, but the capture only took the parent, so undo would
  restore a transaction whose category breakdown had vanished. Children are
  captured and re-inserted after their parents, so the foreign key has
  something to point at.
- **Two deletes in a row shared one countdown.** The dismiss timer was keyed on
  the message text, which is identical for consecutive deletes, so the effect
  never restarted and the second undo inherited whatever was left of the first
  one's window. Keyed on the action's identity now.
- **The account statement never cancelled its previous collector.** Switching
  accounts left both running and writing the same state. Held as a Job and
  cancelled on load.

Pinned by `UndoRestoreTest` (`:core:database` androidTest — needs a device).
An undo that ALMOST restores is worse than no undo: the user taps it, believes
they are whole, and has silently lost data.

- **2026-08-14 · Only the card must be the TARGET of a payment for it to count
  as a transfer** · anchoring on the word "card" near a payment verb · The
  previous rule allowed a bare `to`/`for` with a 40-character gap before the
  word "card", so "Paid Rs.500 to SWIGGY using your HDFC Bank Card ending 4321"
  read as a credit-card bill payment: a genuine expense force-filed into
  Transfers and therefore dropped from the month total, the savings gap and
  every chart, while the balance still fell. A total that is quietly too small
  is the worst failure this app has. Now only `towards`/`toward` may span a
  gap; after a plain `to`/`for` at most two words may stand before the card,
  which is room for a bank name and nothing else. Pinned by
  `SelfTransferScopeTest`, whose negative cases (money that actually left) are
  the point — including the plain bank-to-bank and UPI-to-a-person messages,
  which are NOT transfers and must keep counting as spend.
- **2026-08-14 · The ledger's exclusion caption names cash withdrawals too** ·
  keep the transfers-only wording · Cash withdrawals are excluded from the same
  totals, so a month whose only exclusion was an ATM visit was labelled "moved
  between your accounts" — a caption that explains the wrong thing is worse
  than none.

## Seven-phase pass (2026-08-14)

- **2026-08-14 · Backups need no passphrase, and go to one folder the user
  picks once** · keep the passphrase requirement · Backup did nothing at all:
  both buttons stayed disabled until a passphrase was typed twice at eight
  characters or more, and `performBackup` returned early on a blank one. A
  feature that silently no-ops is worse than an absent one. Files stay
  encrypted under a key stretched from a secret compiled into Kosha — which is
  extractable from a downloadable APK, and the UI copy says so rather than
  implying more. A passphrase is still available for real strength, and a
  header byte records which key a file needs so restore can distinguish "needs
  your passphrase" from "damaged". The Android Keystore would be stronger and
  is deliberately NOT used: a Keystore key dies with the install, so backups
  would be unrestorable after exactly the events people take backups for.
  Destination is a persisted SAF tree — no storage permission, works on every
  supported version, and survives uninstalling Kosha.
- **2026-08-14 · The statement is assembled from chosen sections** · one fixed
  three-page layout · Charts are why people export a statement rather than a
  spreadsheet, and there was no way to ask for one. Pages are emitted only for
  the sections requested and numbered as emitted, since a page 3 with no page 2
  reads as a printing failure. Charts are drawn as vector rather than handed in
  as a screen-resolution bitmap, and the pie ramps LIGHTNESS rather than hue so
  it survives the black-and-white printer statements usually meet.
- **2026-08-14 · Numeric CSV cells skip the formula guard** · guard every cell ·
  The guard prefixes anything starting with `-`, so every debit exported as
  TEXT and the Amount column would not sum — the single thing a CSV export
  exists for. Numeric cells are generated from Long paise and never carry user
  text, so exempting them adds no injection surface.
- **2026-08-14 · Amount and name orderings drop the date sections** · keep the
  month/day grouping always · The list groups by month and day, so an amount
  sort only ordered rows within a day while the sections stayed chronological:
  "Largest first" left the largest transaction wherever its date fell. Flat
  orderings print the date on the row instead.
- **2026-08-14 · Active filters are named in a bar, each removable** · the
  count-behind-a-sheet chip · "Filters · 3" says something is hiding rows
  without saying what, and a ledger quietly showing a third of itself is how a
  total comes to look wrong for no visible reason.
- **2026-08-14 · Rows excluded from the totals are dimmed and labelled** ·
  leave them looking like every other row · They belong in the list because
  they happened, but a row counted differently while looking identical is
  exactly what makes a month header look wrong to someone adding up the rows.
- **2026-08-14 · A drill-down is a typed LedgerTarget, built by a factory that
  knows what the slice is** · three loose nullable arguments · Every chart had
  to squeeze itself into category/month/search. A heatmap day became its whole
  month, and treemap slices always went out as categories even when their
  labels were merchant names lifted from the uncategorized pile — matching no
  category, so the small slices appeared to do nothing. Slices now carry their
  kind, and the bad shapes are no longer expressible.
- **2026-08-14 · The bottom bar drops saveState/restoreState** · keep scroll
  restoration · Opening the ledger from a chart put it on top of Insights;
  tapping Insights popped that stack, saved it, and restored it — landing back
  on the filtered ledger. A tab that will not go to its own tab is broken, and
  scroll position is not worth that.
- **2026-08-14 · Seeded categories added after release are backfilled by name**
  · ship a migration, or only seed fresh installs · Adding a row is not a
  schema change, so the freeze does not apply; but seeding only on an empty
  table means an existing user never sees a new category, with nothing to
  explain the difference. Backfill is inserts-only and leaves renamed or
  reordered categories alone.

- **2026-08-15 · Account tails capture up to eight digits and keep the last
  four** · the `\d{3,4}` capture · Canara sends "Acct XXXXX07683" — five
  trailing digits — which matched nothing, and the two consequences both
  looked like something other than their cause. The transaction could not be
  attributed, so it sat in the review queue and read as "never captured"; and
  because the scan did not stop at the user's own account it carried on to the
  next tail in the message, which in "from a/c XXXXX07683 to a/c XXXXX1234" is
  the PAYEE's. Filing a transaction against somebody else's account number is
  the quieter and worse of the two failures. Pinned by `CanaraBankTest`.
- **2026-08-15 · The pattern library's merchant answers to the same name check
  as the classifier's** · trust curated captures · The library's `merchant`
  group went straight into the ledger while the classifier's went through
  `isNotAName`, so a bank pattern whose group landed on "a/c" titled the row
  "a/c" — with no way for the user to tell why. A rejected capture now falls
  back to the other source rather than taking the row's name down with it.
- **2026-08-15 · A payer's name may be ended by a separator, not just by the
  word "on"** · the single `from … on` pattern · "Cr. INR 5,000.00 on 12/08/26
  from RAMESH K; UPI: …" left money received from a person unnamed.

- **2026-08-15 · A photo Kosha cannot parse opens the preview EMPTY rather
  than being discarded** · keep returning null and showing "unreadable" ·
  `preview()` returned null both when the image could not be read at all and
  when text came off cleanly but held no amount. The second is the common case
  — a receipt in an unusual layout — and throwing the capture away meant the
  Scan and Import tabs returned to their own screen with nothing to show,
  which is indistinguishable from the button not working. The preview screen
  exists precisely so the user can correct a bad read, and its Confirm is
  already gated on a parseable amount, so opening it empty is safe. Null is
  now reserved for "nothing readable in this image at all".
- **2026-08-15 · Camera capture failures are reported** · `onError = Unit` ·
  A failed shutter produced no photo, no error and no end to the spinner. It
  is a separate state from "unreadable": the photo could not be TAKEN, which
  needs different advice from the photo not being understood.
- **2026-08-15 · The Import tab has a failure state at all** · rely on the
  preview opening · It rendered only its intro text and the picker chip, so a
  rejected image put the user back on an unchanged screen with no message.

- **2026-08-15 · Receipt photos are shown, not merely stored** · keep them as
  invisible evidence · The photo was already attached to the transaction, but
  nothing in the app ever displayed it, so capturing one was an act of faith.
  The ledger row carries a thumbnail (via a correlated subquery on the row, not
  a query per row) and the detail sheet shows it at readable size.
- **2026-08-15 · A hand-rolled local image loader instead of an image library**
  · Coil/Glide · Every mainstream loader is built around fetching over HTTP,
  and Kosha has no INTERNET permission plus a CI check that keeps it that way.
  The only images that exist are receipts in app-private storage, so a
  downsampling `BitmapFactory` decode is the whole requirement. Downsampling is
  the part that matters: a 12-megapixel photo decoded at full size, fifty rows
  deep in a scrolling list, is how a ledger runs out of memory.
- **2026-08-15 · Expense / Income / None is one three-way choice** · a
  direction pair plus a separate transfer toggle · The third state — counts as
  neither — was a toggle further down the sheet that you had to know to look
  for. Three mutually exclusive options state the whole truth about what a row
  does to the totals in one place.
- **2026-08-15 · The budget sheet puts amount and Save above the category
  grid** · title, grid, then the controls · The keyboard opened over both the
  field and the button with nothing scrollable to reach them. The two controls
  you must reach are now nearest the top, and the keyboard covers the category
  grid instead — which is optional and now scrolls.

- **2026-08-15 · An unmarked standalone number can be the amount** · require a
  currency marker · Every amount pattern demanded a clean `₹`, `Rs` or `INR`,
  and ML Kit drops or mangles the rupee glyph constantly — it returns nothing,
  a stray letter, or a symbol. A receipt whose amount recognised as plain "175"
  produced NO amount and the whole capture failed, which put the entire feature
  one bad glyph away from useless. The fallback runs only when nothing
  currency-marked exists anywhere, and only on a line that is JUST a number
  once a leading marker is stripped — so a number inside a sentence, a
  reference, a phone number and a card mask are all still excluded.
- **2026-08-15 · The heatmap prints its day numbers** · a bare intensity grid ·
  Without them the chart says "some day in the third week was heavy" and stops.
  Counting squares to work out which day is not reading a calendar. The number
  brightens on dark cells and dims on pale ones so it stays legible at both
  ends of the ramp instead of being tuned for the middle.

- **2026-08-15 · An amount read WITHOUT a currency marker is marked uncertain**
  · trust it like any other reading · The rupee glyph is frequently recognised
  as a leading DIGIT — a real ₹50 receipt came back as "750" — and nothing in
  the text distinguishes that from a genuine ₹750. The unmarked fallback is
  still worth having, because the alternative is reading nothing at all, but it
  was inheriting the template's confidence and so arrived looking certain. It
  now scores below the review threshold, which flags the field in the preview
  and keeps the row out of an unexamined commit. A figure that can be an order
  of magnitude out, shown as fact, is worse than one shown as a question.
  Kosha cannot recover the true value here, and does not pretend to.
- **2026-08-15 · The avatar monogram is not the payee** · take the first
  non-chrome line after the label · Payment apps draw a two- or three-letter
  initials circle beside the name, and it lands on the line ABOVE it, so
  "Mani Gopalgowda" arrived in the ledger as "MG". Candidates in the window are
  now collected and a monogram skipped when something fuller follows — so a
  genuinely short payee ("KFC") is still kept.

- **2026-08-15 · The ledger flow is unbounded** · `LIMIT 500` · The cap reached
  far beyond the list it was written for: the account statement's
  `opening + in − out = now`, the natural-language query and the Settings
  tracked/hidden counts all read the same flow. Past 500 rows the statement
  stopped reconciling and the list simply ended with nothing on screen to say
  anything was missing. A quietly incomplete number is worse than an obviously
  absent one, and a year of captured messages crosses 500 easily.
- **2026-08-15 · With no accounts on file, the first one is created and the row
  goes to review** · dropping the transaction · A correctly-read message was
  discarded outright — no ledger row, no review entry, no message — so anyone
  who granted SMS before adding an account lost everything until they happened
  to add one. This does not breach "never attributed to an account the user did
  not confirm": a Discovered resolution forces PENDING_REVIEW, so the row waits
  for confirmation exactly like any other new-account attribution.
- **2026-08-15 · Moving the tracking date prompts for opening balances** ·
  change it silently · Balances are stored as `opening + tracked transactions`,
  so moving the boundary changes what the opening figure MEANS without changing
  the figure. Every balance is then wrong, and nothing else in the app would
  ever say so.

- **2026-08-15 · Restore deletes the WAL journals and forces a restart** ·
  overwrite the database file and tell the user to reopen the app · SQLite in
  WAL mode keeps recent writes in side files that live next to the database, so
  overwriting only the main file left the next open replaying the OLD journal
  over the freshly restored data — a silent partial restore, which is the worst
  possible outcome for the one feature whose entire job is getting your data
  back. Restore also left every injected copy of the database closed, so the
  advisory "reopen Kosha" was really a hard requirement; it is now a blocking
  card with a button that relaunches the process.
- **2026-08-15 · CI runs the instrumented tests on an emulator** · leave them
  unrun · Undo/restore, multi-account attribution, the migration harness and
  the vault-exclusion guarantee had never once executed, and a test that has
  never run is not evidence of anything. Kept as a separate job because an
  emulator is the slowest and flakiest part of a pipeline, so a green build
  still means "compiles, unit tests pass" without waiting on a device to boot.
