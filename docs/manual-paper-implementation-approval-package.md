# Manual Paper implementation approval package (Phase 2.u)

> **APPROVAL PACKAGE ONLY — NO IMPLEMENTATION.** Creating, reviewing, or signing this document does not itself enable execution. The current app cannot submit orders.

This package converts the [Phase 2.t design](manual-paper-execution-design.md) and [Phase 2.s safety freeze](paper-execution-safety-freeze.md) into a precise human approval checklist, anticipated Phase 2.v diff, forbidden-change list, one-shot rules, test plan, runtime plan, kill switch, and rollback plan.

## A. Current status

As of Phase 2.u:

- The current app cannot submit orders.
- REAL remains locked by default and production never calls `AppState.unlockRealMode()`.
- `PaperTradingExecutionGuard.canExecuteOrders == false`.
- `AlpacaHttpClient` exposes exactly one method, `executeGet`.
- `AlpacaPaperTradingEndpoint` allows only read-only Paper account, clock, and positions GET URLs.
- `PaperDisabledOrderExecutor` always returns `EXECUTION_DISABLED`.
- Preflight, dry-run audit, local draft, payload preview/review queue, and disabled readiness exist.
- No submit client, confirmation token, feature gate, submit audit table, Submit UI, or mutation endpoint exists.
- No production `POST /v2/orders` exists.

Phase 2.u does not change any item above.

## B. HUMAN APPROVAL REQUIRED BEFORE IMPLEMENTATION

No developer or AI agent may implement Paper submission until **Juan explicitly approves it**.

Valid approval must:

1. be written in `docs/phase-1-progress.md` before implementation begins;
2. name the exact approved phase: **Phase 2.v — Manual Paper submit implementation, Paper-only, one-shot, user-confirmed**;
3. explicitly confirm the scope is **Paper-only**;
4. explicitly confirm the flow is **manual-only and user-confirmed**;
5. explicitly accept or amend the open policy values listed in the checklist below;
6. state whether approval covers coding/tests only or also a separately controlled one-order Paper runtime attempt.

An instruction such as “continue,” “proceed,” “implement the next phase,” or approval of this package without naming Phase 2.v and its scope is insufficient. Silence is not approval. Approval to code is not approval to send a Paper order.

### Required approval text

The project log must contain an entry substantively equivalent to:

```text
APPROVAL GRANTED BY JUAN:
Phase 2.v — Manual Paper submit implementation, Paper-only, one-shot, user-confirmed.
Scope: coding and tests only. No Paper order may be sent until a separate runtime approval.
Accepted policies: [account/clock freshness], [confirmation lifetime],
[market-hours rule], [initial order vocabulary], [kill-switch owner].
```

If Juan separately authorizes one controlled Paper runtime attempt, that authorization must be a second written entry naming the build, Paper account context without exposing its identifier, exact allowed attempt count, and stop conditions.

### Human approval checklist

- [ ] Juan has named Phase 2.v exactly.
- [ ] Juan has confirmed Paper-only.
- [ ] Juan has confirmed manual-only, user-confirmed, one-shot behavior.
- [ ] Juan has accepted MARKET/LIMIT, BUY/SELL, DAY as the complete initial vocabulary or documented a narrower set.
- [ ] Juan has accepted the initial market-hours rule: market must be open; no override.
- [ ] Juan has accepted account and clock maximum age (proposed: 15 seconds).
- [ ] Juan has accepted confirmation-token maximum lifetime (proposed: 30 seconds).
- [ ] Juan has accepted the two-stage review plus typed contextual confirmation.
- [ ] Juan has named the runtime kill-switch owner and fail-closed policy source.
- [ ] Juan has accepted append-only audit and no automatic retry for ambiguous outcomes.
- [ ] Juan has stated whether runtime submission is excluded or separately authorized.
- [ ] The approval entry is present in `docs/phase-1-progress.md`.

Until every applicable item is checked, Phase 2.v is NO-GO.

## C. Proposed future phase name

**Phase 2.v — Manual Paper submit implementation, Paper-only, one-shot, user-confirmed**

The name is part of the approval boundary. Renaming, splitting, or broadening it requires a new written approval entry.

## D. Exact files expected to change in Phase 2.v

This is the proposed diff contract. A future implementation must stop for reapproval before adding an unlisted production file or changing a production file marked “must remain unchanged.” Test filenames may be split mechanically when coverage remains equivalent, but the production surface must not silently expand.

### D1. New submit boundary files

| Action | Expected path | Purpose |
| --- | --- | --- |
| New | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperOrderSubmitClient.kt` | One narrow submit interface; no URL/verb/header/credential arguments and no retry API. |
| New | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/OkHttpPaperOrderSubmitClient.kt` | Fixed Paper-only implementation; one request per invocation, redirects fail closed, no logging interceptor. |
| New | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperOrderSubmitModels.kt` | Typed immutable request/result models with whitelisted fields and sanitized failures. |
| New | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitEndpointGuard.kt` | Allows exactly the approved POST method/URL pair and rejects everything else. |
| New | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualExecutionFeatureGate.kt` | Compile-time flag + runtime gate + emergency kill switch; default/fail closed. |
| New | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualConfirmationToken.kt` | In-memory, snapshot-bound, expiring, atomically single-use token. |
| New | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitReadinessChecker.kt` | Fresh account/clock/price, preview parity, warning, REAL-lock, review-row, and feature-gate checks. |
| New | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitExecutor.kt` | Rechecks gates, consumes token, persists start audit, invokes client once, persists sanitized terminal event. |
| Modified | `android/app/build.gradle.kts` | Adds a manual-Paper compile-time flag that is `false` by default and forced `false` for release until separately approved. No dependency is expected. |
| Modified | `android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt` | Wires the new components behind disabled-by-default gates. Existing read-only clients remain intact. |

### D2. Endpoint guard changes

| Action | Expected path | Constraint |
| --- | --- | --- |
| New | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitEndpointGuard.kt` | The only new mutation allowlist. It owns exactly `POST https://paper-api.alpaca.markets/v2/orders`. |
| Must remain unchanged | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/AlpacaPaperTradingEndpoint.kt` | Remains the three-URL GET-only guard. |
| Must remain unchanged | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/AlpacaHttpClient.kt` | Remains exactly `executeGet`; the submit client is separate. |
| Modified | `android/scripts/safety-scan.ps1` | Replaces blanket POST detection with an exact named-file/method/URL allowlist; all other mutations stay suspicious/forbidden. |
| Modified | `android/app/src/test/kotlin/com/vela/android/lab/safety/PaperExecutionSafetyFreezeTest.kt` | Replaces blanket absence assertions with an exact submit-surface allowlist while retaining REAL, LIVE, Auto Paper, credential, background, and non-submit mutation protections. |

Neither the existing GET endpoint guard nor the existing GET client may gain POST/DELETE/PATCH methods.

### D3. UI confirmation changes

| Action | Expected path | Purpose |
| --- | --- | --- |
| New | `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitUiState.kt` | Typed foreground-only state; no credentials, endpoint editing, or reusable token persistence. |
| New | `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt` | Drives review, refresh, warning acknowledgement, typed confirmation, and one-attempt executor call. |
| Modified | `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt` | Adds the two-stage review/confirmation flow only when both gates allow it; no list-row or one-tap submit. |
| Modified | `android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt` | Creates/wires the submit ViewModel through the application graph. No lifecycle callback may submit. |

`PaperOrderPreflightViewModel.kt`, its UI state, and payload-preview builders should remain non-executable. The submit ViewModel consumes their immutable outputs through explicit repository/state linkage rather than teaching them to send.

### D4. Audit persistence changes

| Action | Expected path | Purpose |
| --- | --- | --- |
| New | `android/app/src/main/kotlin/com/vela/android/lab/db/room/entities/PaperOrderSubmitAuditEntity.kt` | Append-only whitelisted attempt events; no credential/header/account-id/raw-body fields. |
| New | `android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/PaperOrderSubmitAuditDao.kt` | Insert and read methods only; no update/delete/clear. |
| New | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperOrderSubmitAuditRepository.kt` | Sanitized entity mapping and durable start/terminal event API. |
| New | `android/app/src/main/kotlin/com/vela/android/lab/db/room/migrations/Migration4To5.kt` | Explicit additive migration creating the audit table without destructive reset. |
| Modified | `android/app/src/main/kotlin/com/vela/android/lab/db/room/VelaDatabase.kt` | Registers entity/DAO, version 5, and the explicit 4→5 migration. |
| New | `android/app/schemas/com.vela.android.lab.db.room.VelaDatabase/5.json` | Exported Room schema. |
| Modified | `android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/PaperOrderPayloadPreviewDao.kt` | Adds one read-only exact-`previewId` lookup; no mutation. |
| Modified | `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPayloadPreviewRepository.kt` | Exposes the read-only exact preview lookup used for parity. |

If Room cannot persist the pre-network `ATTEMPT_STARTED` event, the executor must send zero requests. Existing dry-run and preview rows must survive migration.

### D5. Expected tests

New tests expected under `android/app/src/test/kotlin/com/vela/android/lab/`:

- `data/paper/submit/PaperManualSubmitEndpointGuardTest.kt`
- `data/paper/submit/PaperOrderSubmitClientContractTest.kt`
- `data/paper/submit/OkHttpPaperOrderSubmitClientTest.kt`
- `data/paper/submit/PaperManualExecutionFeatureGateTest.kt`
- `data/paper/submit/PaperManualConfirmationTokenTest.kt`
- `data/paper/submit/PaperManualSubmitReadinessCheckerTest.kt`
- `data/paper/submit/PaperManualSubmitExecutorTest.kt`
- `data/paper/submit/PaperOrderSubmitAuditRepositoryTest.kt`
- `ui/dashboard/PaperManualSubmitViewModelTest.kt`
- `safety/PaperManualSubmitSafetyFreezeTest.kt`

New instrumentation/migration tests expected under `android/app/src/androidTest/kotlin/com/vela/android/lab/`:

- `db/room/VelaDatabaseMigration4To5Test.kt`
- `ui/dashboard/PaperManualSubmitLifecycleTest.kt`

Existing tests expected to be modified deliberately:

- `safety/PaperExecutionSafetyFreezeTest.kt`
- `safety/ManualPaperExecutionDesignContractTest.kt`
- `data/paper/AlpacaPaperTradingEndpointTest.kt` only if needed to reassert the GET boundary is unchanged; it must not accept POST.
- UI/application wiring tests affected by constructor signatures.

### D6. Expected documentation changes

- `docs/paper-execution-safety-freeze.md` — revision history and exact new controlled surface; retained safety invariants.
- `docs/manual-paper-execution-design.md` — resolved policy values and implementation references.
- `docs/manual-paper-implementation-approval-package.md` — approval entry/reference and checklist results.
- `docs/phase-1-progress.md` — approval, implementation evidence, tests, runtime authorization status, and final decision.

### D7. Files that must remain unchanged in Phase 2.v

- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/AlpacaHttpClient.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/AlpacaPaperTradingEndpoint.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperTradingExecutionGuard.kt` (`canExecuteOrders` remains `false`)
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperDisabledOrderExecutor.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperExecutionReadinessChecker.kt` (remains the disabled-readiness checker)
- existing draft/payload preview models and builders, including their `executionEnabled=false`, `DISABLED`, and `POST_DISABLED` constructor guards;
- `android/app/src/main/AndroidManifest.xml` (no service/receiver/permission expansion expected).

## E. Exact forbidden changes

Phase 2.v approval, if granted, would still forbid:

- any LIVE host, especially `api.alpaca.markets`;
- REAL unlock or weakening `ModeGuard`/`AppState`;
- Auto Paper, signal-to-order, strategy-to-order, timer, worker, receiver, startup, lifecycle, or background submit;
- foreground service or related permission;
- order cancellation;
- order replacement;
- close-position calls;
- bracket, OCO, trailing-stop, or compound orders;
- DELETE, PATCH, PUT, cancel, replace, close-position, or account-configuration mutation;
- any account mutation beyond one explicitly confirmed Paper order submit;
- caller-supplied or UI-editable endpoint, method, headers, raw JSON, or redirect target;
- automatic retry after timeout, transport failure, provider rejection, ambiguous response, rotation, or relaunch;
- credential/header/account-id/raw-body logging, rendering, or persistence;
- hard-coded credentials or secrets in BuildConfig/source/tests/docs;
- storing a reusable confirmation token;
- broad weakening/deletion of the Phase 2.s freeze scanner/tests;
- adding an Alpaca SDK, Retrofit/Ktor, ML dependency, or unrelated network stack without separate approval.

Any forbidden change stops Phase 2.v and returns it to human review.

## F. One-shot manual submit rules

A future one-shot executor must enforce all of these as one atomic policy:

1. The flow begins only from an explicit foreground user action on a current preview.
2. The final review shows symbol, side, quantity, type, time-in-force, optional limit price, notional, buying-power/allocation impact, signal, price source/freshness/age, market state, warnings, exact Paper endpoint, and POST method.
3. Every current warning requires acknowledgement; `BLOCKED` preflight can never submit.
4. The user types the exact contextual confirmation and presses a clearly labeled one-order button.
5. A confirmation token is in-memory, snapshot-bound, expiring, and atomically single-use.
6. `previewId` exists in the review queue and its typed fields match the canonical request exactly.
7. Account and clock are freshly read through the existing GET-only client.
8. Price freshness is rechecked with `MarketPriceFreshnessPolicy` immediately before the attempt.
9. REAL is still locked; feature flag, runtime gate, and emergency kill switch all allow the attempt.
10. Any stale/changed screen, preview, account, clock, price, warning, gate, or lifecycle state invalidates confirmation.
11. The start audit event is durable before network I/O.
12. The token is consumed before client invocation; double tap/concurrent calls produce at most one request.
13. Rotation, background/foreground, process recreation, force-stop, or app restart never auto-submit or replay.
14. Timeout or ambiguous provider outcome never retries automatically. A new attempt requires fresh data, a new id, a new token, and new human confirmation after checking the Paper dashboard.

## G. Kill-switch rules

- Compile-time feature flag defaults OFF in every build type; release remains forced OFF until separately approved.
- Runtime execution gate defaults disabled and fails closed when missing, stale, unreadable, or exceptional.
- The emergency kill switch overrides all other gates and has a named human owner before implementation begins.
- The combined gate is checked at flow entry, before final confirmation, before token consumption, and immediately before client invocation.
- Disabling any gate invalidates unconsumed tokens and routes the flow to `PaperDisabledOrderExecutor`/`EXECUTION_DISABLED`.
- `PaperDisabledOrderExecutor` remains compiled, wired, and independently testable.
- No UI control can enable the compile-time flag or emergency kill switch.
- No persisted state can restore an enabled gate or confirmation token after restart.
- Tests must prove compile flag OFF, runtime gate OFF/unknown, emergency disable, and mid-flow disable each produce zero submit-client calls and zero HTTP requests.

### Rollback plan

Before Phase 2.v edits, record baseline file hashes, schema version 4, test count, scanner result, and APK build result. Then:

1. Activate emergency disable and verify zero new submit calls.
2. Force the compile-time flag OFF and build a disabled release candidate.
3. Remove submit UI entry/wiring, submit DI bindings, and submit client/guard while keeping the disabled executor and read-only GET path.
4. Preserve sanitized append-only audit evidence; do not destructively downgrade the database. A reviewed forward migration may leave the inert table in place.
5. Restore the Phase 2.s blanket no-submit scanner/reflection rules if the submit surface is removed.
6. Re-run debug/release tests, Room migration tests, safety scanner, assembly, disabled emulator flow, lifecycle/double-tap checks, and credential-leak checks.
7. Record rollback evidence and the new NO-GO status in `docs/phase-1-progress.md`.

## H. Test plan for future Phase 2.v

### H1. Endpoint and HTTP boundary

- Guard allows exactly POST `https://paper-api.alpaca.markets/v2/orders`.
- Guard rejects LIVE, all other schemes/hosts/ports, query/path variants, redirects, and caller-supplied URLs.
- Guard rejects DELETE/PATCH/PUT and cancel/replace/close/account endpoints.
- Existing `AlpacaHttpClient` reflection surface remains exactly `{executeGet}`.
- Existing `AlpacaPaperTradingEndpoint.ALLOWED_READ_ONLY_URLS` remains exactly account/clock/positions.
- Submit client cannot be constructed with a LIVE/arbitrary endpoint and exposes exactly one submit action.
- One invocation generates exactly one request; the client has no retry route.

### H2. Authorization, one-shot, and safety data

- Submit requires a valid manual confirmation token.
- Token is bound to the exact preview/request/account/clock/price/warning/gate snapshot and is single-use under concurrency.
- Compile-time feature flag OFF blocks submission.
- Runtime gate disabled/unknown/stale and emergency disable each block submission.
- REAL remains locked; unlocked/unknown rejects submission and never unlocks it.
- `BLOCKED` preflight rejects; `WARNING_ONLY` rejects until every current warning is acknowledged.
- Stale/missing price rejects under `MarketPriceFreshnessPolicy`.
- Stale/missing account or clock rejects.
- Market closed/unknown rejects in the approved initial design; any override needs new approval.
- Missing/mismatched review row or `previewId`/payload parity rejects.
- Stale screen, navigation, refresh, rotation, relaunch, and process recreation invalidate confirmation.
- Double tap and concurrent invocation produce at most one request.
- No Auto Paper, background worker/service/receiver/timer, signal observer, or lifecycle submit method exists.

### H3. Audit and privacy

- Durable `ATTEMPT_STARTED` precedes network I/O; audit failure produces zero requests.
- Success appends a sanitized terminal success event with returned Paper order id when present.
- Provider rejection, transport failure, parse failure, timeout, and ambiguous outcome append sanitized failure/unknown events.
- No failure path retries automatically.
- Audit entity/DAO/repository expose no credential, API header, bearer, raw body, reusable token, or unmasked account-id shape.
- Logs/exceptions/Compose semantics/Room never contain credential or API-header values.
- Credential inputs remain blank after save and secret input remains password-masked.

### H4. Lifecycle, persistence, rollback, and regression

- Rotation/relaunch before confirmation sends zero requests.
- Rotation/relaunch during/after `SUBMITTING` never replays the request.
- Database 4→5 migration preserves existing bars, signals, dry-run audit, and preview queue while creating the append-only submit audit.
- Disabled executor remains `EXECUTION_DISABLED` and produces zero submit calls.
- Emergency-disable transition blocks a previously reviewed but unconsumed attempt.
- Full debug/release suites, assembly, scanner, manifest service/ML checks, and existing read-only runtime flows remain green.

Every submit test uses a fake/recording boundary or controlled local test server. No unit/instrumentation test may contact Alpaca.

## I. Runtime validation plan for future Phase 2.v

Runtime submission is separately approval-gated. With a specifically approved test build and controlled Paper account, execute exactly this sequence:

1. Prove default build flags are OFF and a disabled attempt returns `EXECUTION_DISABLED` with zero requests.
2. Record the separately approved build identifier and one-attempt authorization without exposing credentials/account id.
3. Enable only the reviewed compile/runtime Paper gates; keep LIVE and REAL unavailable.
4. Refresh Paper account through the GET-only boundary; verify buying power/account state and freshness.
5. Refresh Paper clock through the GET-only boundary; verify market is open and freshness passes.
6. Refresh the selected symbol price; verify source, timestamp, age, and `FRESH` classification.
7. Run preflight; require `ALLOWED_DRY_RUN` or explicitly acknowledged `WARNING_ONLY`, never `BLOCKED`.
8. Build the local draft and verify `executionEnabled=false`.
9. Build/persist the payload preview and verify `previewId`, `DISABLED`, `POST_DISABLED`, and payload parity.
10. Run the future manual-submit readiness checker and verify every gate from the final snapshot.
11. Display and review every confirmation field/warning; complete the typed human confirmation.
12. Perform exactly one Paper submit attempt.
13. Verify exactly one corresponding order appears in the Alpaca Paper dashboard.
14. Verify local append-only `ATTEMPT_STARTED` and terminal audit events match the attempt id and sanitized summary.
15. Rotate, background/foreground, force-stop, and relaunch; verify no duplicate or replay.
16. Verify network evidence contains the one allowed Paper endpoint only, no LIVE host, no DELETE/PATCH/cancel/replace/close/account mutation, and no retry.
17. Verify UI, logs, exceptions, audit, and Room contain no credential/header/raw-body value or unjustified account id.
18. Activate emergency disable and prove all subsequent attempts return disabled behavior with zero requests.
19. Stop. Do not run a second Paper order without a new written authorization.

Sanitized runtime evidence must include request count, endpoint/method classification, preview/attempt linkage, Paper dashboard correlation, audit correlation, lifecycle replay count, and leak-scan counts. It must never include secrets or unmasked account identifiers.

## J. Final GO / NO-GO table

| Decision | Current Phase 2.u status | Meaning |
| --- | --- | --- |
| GO to review implementation plan | **GO** | Juan may review this package and request changes. Review does not authorize code or network execution. |
| Start Phase 2.v coding | **NO-GO pending Juan's written approval** | Approval must name the exact phase and Paper-only/manual-only scope in the project log. |
| Implement execution in Phase 2.u | **NO-GO** | This phase is approval-package only. |
| Run `POST /v2/orders` in Phase 2.u | **NO-GO** | No mutation request is implemented or authorized. |
| Run a controlled Paper submit in future Phase 2.v | **NO-GO pending separate runtime approval** | Coding approval alone is insufficient. |
| LIVE, REAL unlock, Auto Paper, background submit, cancel/replace/close | **ABSOLUTE NO-GO** | Not authorized by this package or proposed Phase 2.v. |

## Final statement

**Phase 2.u is approval-package only. No execution was implemented. Current app still cannot submit orders. Human approval is required before any future Phase 2.v implementation.**

**GO to review implementation plan. NO-GO to implement execution in this phase. NO-GO to run `POST /v2/orders`.**

## Revision history

- 2026-06-20 — Phase 2.u approval package created. No implementation and no runtime submission.
