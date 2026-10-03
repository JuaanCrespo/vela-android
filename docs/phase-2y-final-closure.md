# Phase 2.y — Final closure and Phase 3 handoff

Status: documentation checkpoint. Published implementation baseline:
`bfa1a5a327baff66489591bf5f00c5c6f3d5e028` (`feat: add legacy bootstrap position coverage`).
This document records facts already present in published source and audit artifacts.
It introduces no runtime, source, schema, test, network or trading change.

Verdict for the phase exit criteria: see section 13.

## 1. Scope and boundaries

Phase 2.y covers Paper position reconciliation: a read-only broker position capture,
a deterministic domain, durable evidence, a read-only UI integration, and an explicit
human-controlled baseline (anchor) mechanism.

Phase 2.y does not include: automatic capture, automatic reconciliation, corrective
trading, account activities, corporate actions, position netting, portfolio
analytics, ML, LIVE or REAL trading, or Auto Paper.

## 2. Milestone history

Published commits (verified in `git log origin/main`):

- 2.y.2 deterministic reconciliation domain: `2855ed1`
  (`feat: add deterministic position reconciliation domain`).
- 2.y.3 durable Paper position evidence, Room v8: `febe18c`
  (`feat: add durable Paper position evidence`).
- 2.y.4 read-only reconciliation integration into the app: `a46b5ea`
  (`feat: integrate read-only Paper position reconciliation`).
- 2.y.5-C legacy bootstrap coverage, Room v9, including C.1 exact-decimal
  integrity fix and C.2 publication payload: `bfa1a5a`
  (`feat: add legacy bootstrap position coverage`). C.1 has no separate commit; it
  is part of the single C.2 publication, as recorded in the C.2 publication receipt.

Milestones without a published commit:

- 2.y.1 architecture. No separate source commit or doc exists in the repository.
  The architecture defined expected position as an accepted broker baseline plus
  validated fills after that baseline (as recorded in `docs/phase-2y5c-legacy-bootstrap-coverage.md`).
- 2.y.5-R1, R1F, R2A, B, D, E, F: runtime or audit phases; no commits.

Runtime and audit chronology (UTC):

- 2.y.5-R1 (2026-09-20). Runtime start 04:57:14.526Z; human-refresh gate 04:59:25.752Z
  with eBPF UID counters 0 rx/tx. Two COMPLETE captures: `bd0b84bf…` (started 05:02:08Z)
  and `3d456114…` (started 05:05:23Z). Both produced reports with no anchor. Verdict
  recorded in the session: `PASS_2Y5_R1_REAL_UNANCHORED_CAPTURE_VALIDATED`.
- 2.y.5-R1F (same day). Capture-origin forensic audit. Classification
  `CASE_B_CONFIRMED_SECOND_HUMAN_REFRESH`: functional validity PASS; procedural purity
  NOT_FULLY_PROVEN (no touch-event log). No auto-capture path, no duplicate persistence.
- 2.y.5-R2A (2026-09-20). Attempt to create a first anchor under the then-published
  policy (`a46b5ea`, `EXACT_CURSORS_V1` only). Verdict:
  `BLOCKED_2Y5_R2A_NO_ELIGIBLE_SYMBOL_AFTER_FRESH_CAPTURE`; SPY, QQQ and BTCUSD all
  returned `INSUFFICIENT_LOCAL_EVIDENCE`. Root cause: the account-wide scope gate
  requires decimal-evidence rows for every historical order; the legacy A/B orders
  have none. Procedural deviation: more refresh retries than the single refresh that
  was authorized (see section 11).
- 2.y.5-B (architecture/policy mismatch audit). Verdict recorded in the 2.y.5-C doc:
  `ARCHITECTURE_POLICY_MISMATCH`. No separate artifact directory exists.
- 2.y.5-C (2026-09-21 to 2026-10-02). Implementation of `LEGACY_BOOTSTRAP_V1` as a
  separate durable mode, plus Room v9 with `Migration8To9`. C.1 corrected an exact-decimal
  integrity gap (red regression: 4 failures; green: 16/16). C.2 publication receipt:
  commit `bfa1a5a`, normal push from `a46b5ea`, `PASS_PHASE_2Y5C2_PUBLISHED_CLEAN`.
- 2.y.5-D (2026-10-02 blocked; 2026-10-03 PASS). The first real v8→v9 attempt was
  `BLOCKED_2Y5D_REAL_PRESTATE_CHANGED`: the expected inventory was 11 snapshots and
  11 reports; the observed inventory was 12 and 12. The block was honored, no APK was
  installed, and VELA was not opened. The 12/12 inventory was then explicitly accepted
  as the authoritative baseline, and the second run (`PASS_2Y5D_REAL_V8_TO_V9_MIGRATION_VALIDATED`)
  migrated the device database with two isolated cold starts.
- 2.y.5-E (2026-10-03, 18:39–18:40Z). Pre-anchor refresh produced snapshot
  `41415453-f17c-4fe7-92d7-e8b2316341ad` (sequence 13, started 18:39:54.510Z, completed
  18:39:57.627Z) and report `fbed3849-6d54-4833-898c-03614a9fc1a4`
  (18:39:57.690Z). The SPY `LEGACY_BOOTSTRAP_V1` anchor `7609740e-4ca2-4a6d-b3bc-630eea59d647`
  was created at 18:40:34.265Z with baseline 8. The E technical audit passed. Its own
  report (`REPORT-2Y5E.md`) did NOT emit the unconditional success code, because the
  number of human taps could not be physically attested (see section 11).
- 2.y.5-F (2026-10-03, 19:12–19:29Z). Post-anchor refresh: snapshot
  `280a76f2-e50e-4ff4-b68f-1b1af2b71c85` (manualRefreshId
  `7919d841-1cc0-4258-b535-f6d445f3d8a3`, started 19:24:49.083Z, completed 19:24:52.095Z)
  and report `1796198e-b343-4943-8812-bf17842d2e0a` (19:24:52.148Z). Result for SPY:
  MATCH. Offline restart PASS.

## 3. Architectural invariants

Three truths stay distinct:

1. BrokerObservedQty: the exact broker quantity from a COMPLETE capture.
2. Historical/local fill evidence: VELA-known fills, with provenance.
3. ExpectedAbsoluteQty: a quantity that exists only with an anchor.

Without an anchor there is no absolute expectation. The result is UNKNOWN or UNANCHORED
with expected null and difference NOT COMPARABLE. No MATCH or MISMATCH is inferred.

With `LEGACY_BOOTSTRAP_V1`, expected = baseline + trustworthy exact post-cut delta.
It is never baseline + legacy historical delta. The historical legacy evidence is
informational only. The empty post-cut sum is exact zero, not a fictitious cursor.

## 4. Real SPY proof

Demonstrated in the 2.y.5-F report `1796198e-b343-4943-8812-bf17842d2e0a`:

- historical legacy VELA delta: +2 (informational, `knownDeltaComplete=false`);
- bootstrap baseline: 8 (anchor `7609740e-…`, `EXACT_DECIMAL`);
- post-anchor exact delta: 0 (no new order after the cut);
- expected: 8;
- broker observed: 8;
- difference: 0;
- state: MATCH.

8 + historical 2 was NOT calculated as 10. The legacy delta was not added to the baseline.

## 5. Exact decimal guarantee

Authoritative quantities are compared as exact decimal values. Two values that share a
Double projection must remain distinct:

- `0.100000000000000001`
- `0.100000000000000002`

A lossy Double equality cannot certify position truth. C.1 regression tests reproduce
this contradiction. A terminal contradiction yields coverage UNKNOWN, expected null, and
no MATCH or MISMATCH. Replay agrees with the persisted result.

Average fill price from legacy storage is non-authoritative. Exact price certification
is not claimed.

## 6. Real migration proof (2.y.5-D)

- v8 PRE: 12 snapshots, 12 reports, 36 position rows, 36 report rows.
- v9 after the first start: same 12/12 identity sets, same fingerprints.
- v9 after the second start: unchanged.
- Anchors, cursors, events and decimal evidence: 0 throughout the migration.
- Legacy submit audit, lifecycle and order reconciliation: 5 / 4 / 3, preserved.
- No fabricated bootstrap anchor. No decimal backfill. No Migration9To10.

## 7. Real anchor proof (2.y.5-E)

- Anchor `7609740e-4ca2-4a6d-b3bc-630eea59d647`: symbol SPY, status ACTIVE, version 1,
  coverage `LEGACY_BOOTSTRAP_V1`, baseline 8, `EXACT_DECIMAL`.
- Manifest digest `aaf41946f68d5788d8ac0dc541c16a3de61fef2f60eb51d48fe836131ab90819`
  recomputed independently as SHA-256 over the stored canonical JSON; it matches.
- Events: CREATED only (version 1). No INVALIDATED or SUPERSEDED.
- Cursors: 0. Decimal evidence: 0. No fake legacy cursor.
- It survived an offline restart in E and again in F, with unchanged identity and digest.

## 8. Report proof

- Pre-anchor report `fbed3849-…` (engine `POSITION_ENGINE_2Y2_V1`) carries
  `INCOMPLETE_HISTORY`. It is historical and immutable.
- Post-anchor report `1796198e-…`: engine `POSITION_ENGINE_2Y5_BOOTSTRAP_V2`, policy
  `POSITION_POLICY_LEGACY_BOOTSTRAP_V1`, freshness FRESH, localEvidenceAlignment CONFIRMED.
- Rows: SPY MATCH (anchor `7609740e-…`); BTCUSD and QQQ UNKNOWN, with no fabricated
  absolute comparison.
- Pre-anchor reports are not reinterpreted. The set of 13 earlier reports was verified
  unchanged by identity, not by order.

## 9. Final durable counts (from existing artifacts)

Measured by the observer at 19:24:55Z after the F refresh (artifact
`phase-2y5f-20261003/human-observation/state.json`, outside the repository) and
re-measured after the F offline restart with the same values:

- Room user_version 9; integrity_check ok; foreign_key_check 0 violations.
- paper_broker_snapshot: 14.
- paper_broker_position_snapshot: 42 (14 × 3 symbols).
- paper_position_reconciliation_report: 14.
- paper_position_reconciliation_row: 42.
- paper_position_anchor: 1; active anchors: 1.
- paper_position_anchor_event: 1 (CREATED).
- paper_position_anchor_cursor: 0.
- paper_order_decimal_evidence: 0.
- Legacy: paper_order_submit_audit 5; paper_order_lifecycle_observation 4;
  paper_order_reconciliation 3.
- paper_order_dry_run_audits 14; paper_order_payload_previews 9 (unchanged by Phase 2.y).

## 10. Network provenance

Stated precisely, with no endpoint-level overclaim:

- Migration (2.y.5-D): UID 10192 traffic 0 bytes and 0 packets across both isolated
  starts and the close, per the D resume report.
- E anchor creation: the E report records local creation with GET 0 and POST 0 inside
  its window. It also records 208 bytes RX and 328 bytes TX outside that window, at
  18:45:58Z. These are compatible with TCP close but were not packet-classified. They
  are not presented as HTTP requests. E also records a VELA reopening whose origin is
  not attributed.
- F offline restart: UID 26958/52/6212/58 before and after an offline launch. Zero
  network bytes.
- F post-anchor capture: UID counters show aggregate traffic over an observation window
  (rx +13272 bytes / 22 packets, tx +2842 bytes / 23 packets, 19:10:45Z to 19:25:06Z).
  This is not endpoint-specific attribution. The durable capture record proves the
  contract: one account request (HTTP 200) and one positions request (HTTP 200) were
  completed for snapshot `280a76f2`. The capture coordinator issues at most those two
  GETs, with retries 0 and redirects disabled.
- POST: 0. Supported by the execution boundary (no POST path in the status, capture or
  reconciliation transports), and by the unchanged submit audit (5 rows).

No HTTP request ledger or packet capture exists. Endpoint counts are therefore not
independently instrumented.

## 11. Provenance caveats (human origin)

Human origin of the refresh taps is:

    HUMAN_REFRESH_ORIGIN = CONSISTENT_WITH_AUTHORIZED_ACTION_NOT_INDEPENDENTLY_TACTILE_PROVEN

Evidence for each capture:

- F refresh (snapshot `280a76f2`): the observer (PID 17948, passive, no input) recorded
  13→14 at 19:24:55Z. `humanRefreshObserved` was false in its gate file. A
  Computer Use control attempt at 19:23:09Z failed with a sandbox error, with
  `inputActions=0` and `refreshSent=false`. No `adb input` or automation was logged.
  The durable record proves exactly one new pair; the tap itself has no touch-event log.
- E pre-anchor capture (`41415453-…`): `REPORT-2Y5E.md` states that the records prove a
  single persisted refresh and a single creation, but do not physically record each tap.
  The E formal success code was therefore not emitted.
- R1 captures: R1F concluded CASE_B with procedural purity NOT_FULLY_PROVEN.
- R2A: more refresh retries than the single authorized refresh. Eleven captures were
  observed in that session (snapshots 3–11, the first being the authorized one). This
  was a procedural deviation. No durable harm: no anchor was created and no duplicate
  persistence was found.
- Snapshot `609bd321…` (manualRefreshId, started 2026-09-20 06:03:58.867Z): it occurred
  about 37 seconds after the last R2A observation (snapshot 11, completed 06:03:21.394Z).
  The D report accepts it as durable history but does not attribute its origin. It is
  recorded as UNATTRIBUTED.

Durable evidence does not depend on this caveat. The reconciliation semantic result is
not downgraded by the provenance gap.

## 12. Safety boundary

- REAL locked: true (UI label "REAL locked").
- LIVE: false. "No LIVE endpoint" is displayed.
- Auto Paper: false. "Auto Paper disabled" is displayed.
- `PaperTradingExecutionGuard.canExecuteOrders`: hardcoded false.
- Release `MANUAL_PAPER_SUBMIT_COMPILED`: false (`build.gradle.kts` release branch,
  `local.properties` false).
- Reconciliation is informational. No corrective trading. No corrective buttons.
- MISMATCH cause remains UNKNOWN. No external-trade, bug, split or manual-trade
  attribution is made without evidence.
- No automatic anchor invalidation. No automatic anchor creation. No automatic refresh.

## 13. Phase 2.y exit criteria

- A. Broker positions captured durably: PASS.
- B. Exact quantity domain: PASS (DecimalQuantity; C.1 regression).
- C. Historical and local fill semantics: PASS.
- D. Absolute expectation requires an anchor: PASS.
- E. Legacy bootstrap available as a separate, human-confirmed mode: PASS.
- F. Real v8→v9 migration: PASS (2.y.5-D runtime).
- G. Real bootstrap anchor: PASS for the technical and durable criteria (2.y.5-E audit
  PASS; anchor ACTIVE, digest valid, CREATED only, survived restart). Provenance caveat:
  the E formal closure code was withheld in its own report pending attestation of the
  human tap count (section 11). This caveat does not change the semantic result.
- H. Real post-anchor absolute reconciliation: PASS (2.y.5-F MATCH).
- I. Offline persistence: PASS (E and F restarts, network 0).
- J. No corrective trading: PASS.
- K. REAL and LIVE remain locked: PASS.
- L. Exact-decimal contradiction fail-closed: PASS (C.1 regression; C.2 receipt).

PHASE_2Y_EXIT_CRITERIA = PASS, with the G provenance caveat recorded above.

## 14. Verification references

- Published C.2 validation (`bfa1a5a`, receipt `phase-2y5c2-20261001/publication-receipt.json`):
  Debug 2086 tests, 0 failures, 110 suites; Release 2086 tests, 0 failures; precision
  suite 16 PASS; host SQLite migration tests 3 PASS; safety 11/0/0; lint: only the
  known pre-existing `Symbols.kt:40 NewApi` (`KNOWN_PREEXISTING_LINT_FAILURE`).
- The instrumented `LegacyBootstrapMigration8To9Test` was COMPILED but NOT executed on
  a device in C.2. The device migration proof is 2.y.5-D.
- This closure did not rerun any test suite. It references the published C.2 numbers.

## 15. Known limitations (intentional, within current scope)

- Legacy average fill price precision is non-authoritative.
- No automatic anchor bootstrap. Every anchor requires human confirmation.
- No crossing-cut unresolved-order support in bootstrap V1.
- No automatic corrective trades.
- No LIVE, no REAL, no Auto Paper.
- No Learning Core yet. No ML yet.
- Endpoint-level network attribution is not instrumented.
- Human origin of taps is not independently tactile-proven.
- Snapshot `609bd321…` origin is unattributed.

These are not defects unless they violate current scope.

## 16. Explicit non-claims

Phase 2.y does NOT validate:

- autonomous trading;
- profitability;
- ML;
- strategy;
- REAL trading;
- LIVE trading;
- Auto Paper;
- an exact account-wide history reconstruction.

## 17. Phase 3 handoff — Learning Core

Proposed macro-phase: PHASE 3 — LEARNING CORE.

- 3.a Market dataset
- 3.b Feature engineering
- 3.c Labels and outcomes
- 3.d Baseline models
- 3.e ML training
- 3.f Evaluation and calibration
- 3.g Autonomous retraining
- 3.h Model registry
- 3.i Strategy discovery
- 3.j Shadow trading
- 3.k Self-evaluation and adaptation

Safety principle: the deterministic Risk Core remains the final authority.

ML and AI may predict, rank, score, classify, estimate probabilities and propose
strategies. They may NOT bypass broker truth, position reconciliation, the Risk Core,
execution guards, the REAL lock or the LIVE lock.

No ML execution authorization exists in this handoff.

Dataset requirement for 3.a: auditable market data, versioned schema, known timestamps,
known symbol and source, defined missing-data semantics, deterministic transformations.
No training from mutable UI state. No label leakage. No use of reconciliation truth as a
trading signal without a separate design.

Order ground truth: the canonical order lifecycle, integrity status and expected
position delta are exposed and ready for later use. Market-state, feature and outcome
work belongs to Phase 3.

## 18. Documentation consistency notes

Count sequence as durable transitions, not as a single simplified story:

- 12 snapshots / 12 reports: the v8 migration PRE baseline (2.y.5-D).
- 13 snapshots / 13 reports: after the real bootstrap capture (2.y.5-E pre-anchor).
- 14 snapshots / 14 reports: final, after the post-anchor capture (2.y.5-F).

Within R2A, the count rose as high as 11 snapshots, and the 12th capture is
unattributed (section 11). The earlier 2.y.5-D blocked attempt (11 expected, 12 observed)
is historical and is superseded by the accepted 12/12 baseline.

## 19. Final state (last verified, 2026-10-03 after the F offline restart)

- Room: 9.
- VELA: STOPPED.
- Network: restored (airplane_mode 0, Wi-Fi 1, mobile data 1).
- Anchor: SPY `LEGACY_BOOTSTRAP_V1` ACTIVE, baseline 8.
- Repository: HEAD = origin/main = `bfa1a5a327baff66489591bf5f00c5c6f3d5e028` before this
  documentation commit. Showcase files remain untracked and unstaged.

This closure performed no device access. The final state above reflects the last
verification recorded during the 2.y.5-F session, not a fresh device read.
