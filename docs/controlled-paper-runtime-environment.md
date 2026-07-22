# Controlled Paper runtime — environment preflight (Phase 2.v.2)

This note captures the operational preconditions that must hold on the host + Android emulator BEFORE any controlled Phase 2.v manual Paper submit runtime attempt. It is not a code change and does not authorize a submit; it complements — never weakens — the app-level Phase 2.v.1 gate.

Related artifacts:

- [`paper-execution-safety-freeze.md`](paper-execution-safety-freeze.md) — the frozen app-level safety boundary.
- [`manual-paper-execution-design.md`](manual-paper-execution-design.md) — the manual submit design.
- [`../android/scripts/Check-EmulatorClock.ps1`](../android/scripts/Check-EmulatorClock.ps1) — read-only clock skew preflight (added in Phase 2.v.2).
- [`../android/scripts/safety-scan.ps1`](../android/scripts/safety-scan.ps1) — source-level dangerous-surface scan.

## Why this note exists

The 2026-07-07 controlled runtime retry (see the corresponding Phase 2.v.1 report in [`phase-1-progress.md`](phase-1-progress.md)) reached the last gate before the network boundary with every application-level condition green:

- Alpaca Paper account `ACTIVE`, unblocked, buying power sufficient
- Alpaca Paper clock `marketOpen=true`
- Real IEX SPY stream connected, subscribed, quotes flowing
- Preflight `ALLOWED_DRY_RUN`, price source `LIVE_QUOTE_MID`, freshness `FRESH`, age 41 ms
- Draft `READY_LOCAL`, Payload preview `READY_PREVIEW`
- Readiness `READY_BUT_EXECUTION_DISABLED`
- Drift 0.11 % under the 0.25 % threshold
- Session armed, submit endpoint locked to `POST https://paper-api.alpaca.markets/v2/orders`

The submit gate still blocked with `PRICE_NOT_FRESH`. Root cause: the Android Studio `Pixel_10_Pro_XL` emulator's system clock had drifted **≈ 166 s behind real UTC**. The IEX quote timestamps therefore appeared to be in the future relative to the emulator, so `device_now − quote_timestamp` evaluated to `-65 876 ms → -65 887 ms`. The Phase 2.v.1 policy correctly rejects a non-monotonic (negative) effective age.

**This is not an app bug.** The negative age is exactly the class of state the freshness policy was hardened to reject. It is an environmental precondition that must be verified BEFORE the controlled runtime flow is started.

## Preflight rules

The following operational rules apply to every future Phase 2.v manual Paper submit runtime attempt.

### 1. Verify emulator clock sanity BEFORE enabling `MANUAL_PAPER_SUBMIT_COMPILED`

- Run [`Check-EmulatorClock.ps1`](../android/scripts/Check-EmulatorClock.ps1) with the target emulator serial (defaults to `emulator-5554`).
- The script reads emulator UTC via `adb shell date -u +%s`, reads host UTC, and prints both plus the absolute skew in seconds.
- Interpretation:
  - `|skew| ≤ 2 s` → **PASS**. Safe to proceed with the controlled runtime preflight.
  - `2 s < |skew| ≤ 5 s` → **WARN**. Tight against the 10 s Phase 2.v.1 final-age cap; prefer to restart the emulator before proceeding.
  - `|skew| > 5 s` → **BLOCK**. Do not enable `MANUAL_PAPER_SUBMIT_COMPILED` and do not start the controlled runtime flow.
- The script is read-only. It never sets the emulator clock, never touches `local.properties`, never launches the app, never opens a network connection, never reads app data or credentials.

### 2. Negative quote age is a valid blocker, not a bug (unless it lies within the Phase 2.v.3 tolerance)

- The Phase 2.v.1 / 2.v.3 policy requires the *effective* quote age to be non-negative and within the source-specific freshness policy AND within the 10 s Phase 2.v.1 hard cap. It also requires the *raw* quote age to satisfy `rawAge >= -MAX_FUTURE_PRICE_SKEW_MS` where the default tolerance is `2 000 ms` (Phase 2.v.3).
- A raw age that is only slightly negative (a few tens or hundreds of ms) is a normal condition on Android emulators whose kernel clock trails host UTC even after NTP has settled: IEX exchange timestamps arrive with millisecond-precision server-side stamps and can land in the near future relative to the device. Phase 2.v.3 accepts that specific class by clamping the effective age to `0 ms` and marking `futureSkewToleranceApplied = true` in the evaluation and UI. This is not a bypass — it is an explicit, testable, and diagnosed relaxation with a hard boundary.
- A raw age that is more negative than `-MAX_FUTURE_PRICE_SKEW_MS`, or a raw age above the 10 s cap, remains a correct fail-closed `PRICE_NOT_FRESH`. Never treat that as a bug in the app. Fix the environment, not the gate.
- Do not extend the Phase 2.v.3 tolerance to hide larger clock drift. If the emulator regularly produces raw ages below `-2 000 ms`, the correct response is still to cold-boot the emulator or move to a physical device — not to widen the tolerance.

### 3. Do NOT adjust the emulator clock during an armed submit flow

- Once the manual submit session is armed, or a confirmation token has been issued, do not run `adb shell date …`, do not change the emulator time zone, and do not toggle `settings put global auto_time …`. All of those are considered "forzar el gate" — the exact behavior the safety contract forbids.
- If the gate blocks with `PRICE_NOT_FRESH` at that point, the correct response is: disarm the session, force-stop the app, restore the debug flag OFF, rebuild the safe APK, and record the block.

### 4. Do NOT use clock correction to bypass any app gate

- Repeat: no environment change, kernel setting, host clock nudging, snapshot manipulation, wall-clock override, or emulator RTC drift may be used to convert a `BLOCKED` gate into a passing gate.
- The Phase 2.v.3 `MAX_FUTURE_PRICE_SKEW_MS = 2 000` tolerance is likewise not a bypass. It is inside the app under test and reviewed policy; it does not permit any environment change while a submit session is armed.
- The rule "if any gate blocks, stop and report the exact reason" applies to every gate, whether the root cause is app-level (drift %, freshness, market close, raw age below the future skew tolerance) or environmental (clock skew beyond the tolerance, network failure, missing credentials).

### 5. Detected skew ⇒ stop before enabling runtime submit

- If [`Check-EmulatorClock.ps1`](../android/scripts/Check-EmulatorClock.ps1) returns WARN or BLOCK, do not append `MANUAL_PAPER_SUBMIT_COMPILED=true` to `local.properties`, do not rebuild the controlled APK, and do not launch the app for a runtime attempt.
- Only retry after the emulator clock is sane — either by cold-booting the emulator with network time enabled, or by restarting it and re-verifying skew.

## Safe pre-runtime environment repair options

The following are legitimate operational actions that keep the emulator's clock in agreement with real UTC. All are performed BEFORE any controlled runtime attempt is started, and all leave the app-level gate unchanged.

### Option A — Cold-boot the emulator with network time enabled

1. Fully close the emulator (`adb -s emulator-5554 emu kill` or the AVD Manager UI).
2. Boot with `emulator -avd Pixel_10_Pro_XL -no-snapshot-load -no-snapshot-save -no-boot-anim -no-audio`.
3. After boot, verify emulator has `settings get global auto_time` = `1` and `settings get global auto_time_zone` = `1`.
4. Wait 10–30 s for NTP sync to settle.
5. Run `Check-EmulatorClock.ps1` and require PASS before continuing.

### Option B — Restart the emulator before runtime

- If skew is detected on a warm emulator, close and re-open the emulator (Option A). Do not run `adb shell date …` on the live emulator.

### Option C — Confirm Android auto-time settings, if applicable

- On some Android Studio versions, an AVD may boot with `auto_time=0` if the underlying image lacks a working `time_detector`. Toggle back to `auto_time=1` and reboot.

### What NOT to do

- **Do not** run `adb shell date -u <MMDDhhmmYYYY.ss>` to nudge the emulator clock into alignment while a submit session is armed. This has been observed to be silently rejected on Android 14+ non-rooted emulators, and even if it succeeded it would be a bypass of a live gate.
- **Do not** disable network time on the host while trying to reconcile the emulator to a stale time.
- **Do not** modify the emulator RTC via QEMU flags to compensate for observed skew.
- **Do not** relax the Phase 2.v.1 policy or introduce a "tolerate small negative age" flag. That is a source change, not an environment fix, and would require a separate approval.

## Phase 2.v.3 addition — small future-timestamp tolerance

- Constant: `PaperFinalPriceStabilityPolicy.DEFAULT_MAX_FUTURE_PRICE_SKEW_MILLIS = 2 000 L`.
- Evaluation of a final quote whose raw age satisfies `-2 000 ms ≤ rawAge < 0 ms` is treated as `effectiveAge = 0 ms` for the purpose of the freshness classifier and the 10 s hard cap; `futureSkewToleranceApplied = true` is surfaced on `PaperFinalPriceEvaluation` and on the UI (`Final price raw age (ms)`, `Future skew tolerance applied`, `Future skew tolerance (ms)`).
- The tolerance never converts a stale (`> 10 s`), price-drift (`> 0.25 %`), symbol-mismatch, non-positive-price, source-incompatible, or missing-price condition into `ALLOWED`.
- Runtime submit was NOT attempted in Phase 2.v.3. The tolerance is a policy refinement backed by unit tests; it does not authorize any new POST attempt on its own.

## Failure summary from the 2026-07-07 attempt (for reference)

| Field | Value |
| --- | --- |
| Host UTC at BLOCKED | `2026-07-07T16:27:06Z` |
| Emulator UTC at BLOCKED | `2026-07-07T16:24:20Z` |
| Absolute skew | ≈ 166 s (emulator behind host) |
| Final effective quote age | `-65 876 ms → -65 887 ms` (negative) |
| Preview price → Final price | `747.87 USD → 748.69 USD` |
| Drift at BLOCKED | `0.1103 %` (under 0.25 % threshold) |
| Final gate | `PRICE_NOT_FRESH` |
| Real Paper POST count | `0` |
| Runtime safety behavior | PASS — negative-age freshness gate failed closed |

The correct interpretation is: the app did the right thing, and the emulator environment needs to be sane before the retry is attempted.
