# Exit gates that need a physical device

Every phase gate in the spec that can be verified by code is verified by code
and runs in CI. The gates below cannot be — they need real hardware, real bank
SMS, or your own screenshots. They are listed here so nothing is quietly
assumed to have passed.

Run instrumented tests with `./gradlew connectedDebugAndroidTest` on a
connected device.

## Phase 0 — spikes and baselines

| Gate | Why a device is needed | How to run it |
|---|---|---|
| SMS receiver fires reliably under doze | OEM battery managers differ; emulators do not reproduce them | Install on ≥2 devices including one aggressive-OEM (Xiaomi/Vivo class). Send yourself bank-format SMS, leave the phone idle overnight, confirm entries appear. The 12-hourly `SmsReconcileWorker` is the safety net if the receiver is killed. |
| OCR extracts the amount on ≥8/10 samples | Needs your real PhonePe/GPay screenshots and paper bills | Scan 5 UPI screenshots + 5 paper bills through the Scan tab; count how many need no edit. |
| APK size baseline | Sets the Phase-12 budget (baseline + 8 MB) | CI uploads `kosha-debug-apk` on every run — record the number from the first release build in `DECISIONS.md`. |
| Cold-start baseline incl. SQLCipher open | Sets the Phase-12 perf budget (baseline + 30%) | `adb shell am start -W -n dev.kosha.app/.MainActivity` on the mid-range test device. |

## Phase 2 — SMS accuracy

Two weeks of live self-testing: zero duplicate commits, zero OTP/promo
false-positives, unrecognized formats landing in review rather than being
dropped. The corpus covers this logically (`TransactionClassifierTest`,
`SmsParserTest`, `RealWorldSmsTest`, `DedupEngineTest`), but only real inbox
traffic proves the sender gate and the extractors against your banks.

Detection is bank-agnostic: a message is a transaction because it has an
amount and a direction verb, not because someone wrote a regex for that bank.
So a missed message is a **classifier** gap — add the case to
`TransactionClassifierTest` and widen the verb or extractor patterns. Adding a
curated pattern to
`engines/engine/src/main/resources/kosha/patterns/sms-patterns-v1.json` is
still worth doing for a bank you use often: it raises confidence and names the
bank, but it is no longer what makes capture work.

Multi-account attribution is pinned by `MultiAccountAttributionTest`
(`:core:database` androidTest, needs a device): an SMS account tail that
matches nothing must create its own account and wait for review, never land on
the account you did add.

## Phase 4 — OCR accuracy

≥90% no-edit rate on your own screenshots. The dedup half of this gate (a photo
of an SMS-captured transaction never duplicating) IS covered by tests.

## Phase 8 — vault crypto

`VaultCrypto` uses a Keystore key with `setUserAuthenticationRequired(true)`,
which cannot be exercised without a device that has a lock screen enrolled.
Verify by hand:

1. Reveal a field → biometric prompt appears before any value renders.
2. Wait 20s → the field re-masks itself.
3. Copy a field → paste works; wait 30s → clipboard is empty.
4. Try to screenshot the vault → blocked by `FLAG_SECURE`.
5. Remove the device lock screen, reopen the vault → entries report as
   unrecoverable rather than crashing. **This is by design** (spec B4).

## Phase 10 — export and backup

- PDF renders correctly in 3 viewers (test on device, then open the shared file
  in Drive/Adobe/a desktop viewer).
- backup → wipe app data → restore is lossless, and vault entries are absent
  unless you opted in. The vault-exclusion logic is unit-visible
  (`BackupManager.snapshotDatabase` deletes from the snapshot), but the
  round-trip needs a device.

## Phase 12 — regression and performance

- Full regression on 3 device classes.
- Cold start ≤ Phase-0 baseline + 30% (hold the 1.5s line if the baseline was
  ≤1.2s).
- APK ≤ Phase-0 baseline + 8 MB.
- Accessibility: TalkBack over the Pulse and budget rings. Amounts already
  announce with currency and sign (`AmountText`), and every chart carries a
  `contentDescription` summary — confirm the reading order makes sense.
