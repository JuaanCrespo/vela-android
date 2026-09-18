# Phase 2.y.3 — Durable Paper position evidence + manual GET capture + Room v8

Baseline: `2855ed17c94ecdd6bf86ccbd7c3f4a5e03af0520` (HEAD = origin/main at phase
start). Room version at start: v7. Room version after 2.y.3: **v8**, additive
only (`MIGRATION_7_8`). No commit or push is authorized by this phase; changes
land only in the working tree.

## Purpose

Phase 2.y.3 delivers the durable evidence layer for later manual reconciliation.
It does **not** deliver UI, automatic capture, or trading actions. Explicit
non-goals:

- no viewer / dashboard / portfolio card;
- no anchor UI, no reset UI, no "refresh" button wired to any screen;
- no navigation-triggered / startup / background / periodic refresh;
- no positions polling, no `WorkManager`/service/scheduler;
- no reconciliation-driven trading, corrective orders, or account activities;
- no ML features/labels/models;
- no LIVE endpoint, no LIVE host, no REAL trading;
- `MANUAL_PAPER_SUBMIT_COMPILED=false` release-side is preserved.

The capability to perform a **manual** capture is coded, but no capture is
executed during this phase. Real runtime is deferred to 2.y.5.

## Source-of-truth model

The canonical route for reconciliation evidence is **separate** from the
existing dashboard/account/positions readers. The dashboard's permissive JSON
parser is not authoritative for reconciliation and is not reused as such.

Structure introduced:

- `StrictPaperCaptureParser` — canonical read of `/v2/account` and
  `/v2/positions` responses, with fine-grained `CaptureDiagnostic` outcomes.
- `PaperPositionEvidenceHttpTransport` — narrower GET-only transport whose
  method signature does not accept a URL, only an internal enum (`ACCOUNT` /
  `POSITIONS`). Redirects and retries are disabled at construction.
- `PaperPositionEvidenceCaptureCoordinator` — the single explicit entry point
  for a "manual refresh". Serialised by a global mutex; the second concurrent
  call is `Busy`.
- `PaperBrokerPositionSnapshotRepository` — transactional writer of
  captures. A `FAILED` attempt cannot be published as `COMPLETE`. A previous
  `COMPLETE` snapshot is never mutated or replaced by a subsequent `FAILED`
  attempt.
- `PaperPositionAnchorRepository` / `PaperPositionReconciliationReportRepository`
  — durable anchors, cursor lists, append-only anchor events and immutable
  reconciliation reports.

## Strict parser rules

`StrictPaperCaptureParser` returns one `EvidenceParseResult<T>` with a specific
`CaptureDiagnostic`. There is no silent row drop; a single bad row invalidates
the whole `positions` snapshot.

Behavioural contract:

- `null` body → `EMPTY_BODY_INVALID`.
- Whitespace-only body → `EMPTY_BODY_INVALID`.
- Body that fails RFC-8259 shape → `MALFORMED_JSON`.
- `positions` root that is not a JSON array → `UNEXPECTED_TYPE`.
- `account` root that is not a JSON object → `UNEXPECTED_TYPE`.
- Non-object element in `positions` array → `INVALID_ROW` (fails the snapshot).
- Missing / blank / non-canonical symbol → `INVALID_SYMBOL`.
- Missing `qty` field → `INVALID_QTY`.
- `qty` present but not a string lexeme → `UNEXPECTED_TYPE`.
- `qty` string is `NaN`/`Infinity`/`-Infinity`/`+Infinity` → `NONFINITE_NUMBER`.
- Duplicate symbol in the same array (case-insensitive after uppercasing) →
  `DUPLICATE_SYMBOL` — never summed, never picked "first" or "last".
- Signed `qty` that disagrees with `side` → `SIDE_CONTRADICTION`. Sign is never
  reconstructed from `abs(qty)` or inferred from `side`.

## `[]` vs empty body

- `HTTP 200` with body `[]` on `/v2/positions` → `SUCCESS_COMPLETE` with an
  empty portfolio. The zero-row snapshot is a legitimate `COMPLETE` capture.
- `HTTP 200` with an empty / whitespace / null / malformed body →
  `EMPTY_BODY_INVALID` or `MALFORMED_JSON`. The snapshot is `FAILED`.

## Decimal storage contract

New authoritative quantities are stored as **TEXT decimal**. Two columns are
kept on every stored position row:

- `qtyRawDecimal` — the exact text received (e.g. `"1.000"`).
- `qtyCanonicalDecimal` — the value passed through
  `DecimalQuantity.parse(...).toString()` (e.g. `"1"`).

The transformation goes through `DecimalQuantity` directly; converting
`String → Double → BigDecimal` is forbidden. Market value and P&L remain
observational, and the observed account JSON only accepts the four allow-listed
numeric fields (`cash`, `equity`, `buyingPower`, `portfolioValue`), each stored
as canonical decimal text.

The exported schema is Room v8 (`app/schemas/…/VelaDatabase/8.json`).

## `accountRef` policy

Broker account identity is never persisted or logged raw. When the
`/v2/account` payload contains a stable, canonical UUID `id`:

```
accountRef = "paper-v1:" + sha256("vela-paper-account-ref-v1|" + id.lowercase())
```

Persisted and surfaced only. Same broker id (regardless of case) yields the
same `accountRef`; different ids yield different refs.

If `id` is missing or malformed, `accountRef = null` and the diagnostic is
`ACCOUNT_REF_UNKNOWN`. Such captures can be **stored for diagnostics**, but
they can never satisfy the `COMPLETE` gate and are not anchor-eligible. The
public `ObservedPaperAccount` domain type only exposes the derived `accountRef`
plus the four observed decimal fields; the raw `id` never crosses that
boundary.

## Manual GET boundary

Manual refresh performs exactly **two** GETs, in this order:

1. `GET https://paper-api.alpaca.markets/v2/account`
2. `GET https://paper-api.alpaca.markets/v2/positions`

Both are subject to `AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet`.
There is no LIVE host, no additional GETs, and no clock GET required for this
capability. The internal enum `PaperCaptureEndpoint` allows only those two
values so the URL is never caller-supplied.

The dedicated OkHttp client sets:

- `retryOnConnectionFailure = false`
- `followRedirects = false`
- `followSslRedirects = false`
- `authenticator = Authenticator.NONE`
- `proxyAuthenticator = Authenticator.NONE`

and, as belt-and-braces, a network interceptor that fails on the second
exchange within a single call, so any implicit follow-up is rejected. The
response `toString()` redacts the body. A `PaperCaptureSession` does not
include credentials in its `toString()`.

## Snapshot semantics

A snapshot is a **VELA capture window**, not a broker-atomic snapshot:

- `startedAtEpochMillis` — window opened locally.
- `accountCompletedAtEpochMillis` / `positionsCompletedAtEpochMillis` — end of
  each individual GET.
- `completedAtEpochMillis` — window closed locally.

Local persistence itself IS transactional. To publish a snapshot as `COMPLETE`
the coordinator requires every one of:

- account GET valid;
- stable `accountRef` known;
- positions GET valid;
- valid JSON body;
- every row valid;
- no duplicate symbols;
- exact decimal `qty`;
- no configuration/account identity change detected during the window;
- persistence transaction success.

Otherwise the metadata row is stored as `FAILED` with the diagnostic set, no
position rows are inserted, and the previous `COMPLETE` snapshot is
**untouched**.

## Config generation

Every snapshot records:

- `configRef` — an opaque `<sessionRef>:<generation>` string bumped whenever
  observed credentials change during the app's lifetime. It is **not** derived
  from any secret.
- `sessionRef` — the current app-session UUID.
- `parserVersion = POSITION_CAPTURE_PARSER_V1`.

The coordinator re-reads the config after the account GET; a mid-capture
change surfaces as diagnostic `CONFIG_CHANGED` and cancels the positions GET.

## Anchors / cursors / events

- Creating an anchor requires a `COMPLETE`, anchor-eligible broker snapshot,
  a matching `accountRef`, `CutAssurance.CONFIRMED` scope, an exact-decimal
  baseline that matches the broker's row for the symbol, and `ANCHORED`
  coverage evaluated by `LocalPositionExpectationEngine`.
- Only one `ACTIVE` anchor per `(symbol, accountRef)` — enforced by a unique
  `activeKey` index and a runtime check.
- `invalidateAnchor` and `supersedeAnchor` are compare-and-set operations on
  the anchor status. They never delete the previous row.
- Every anchor state change writes a new `paper_position_anchor_event` row
  (`CREATED`, `INVALIDATED`, `SUPERSEDED`). Events are append-only, keyed on
  `(anchorId, version)`.

## Report durability and replay

- Each `createReport` writes an immutable
  `paper_position_reconciliation_report` with the engine and policy versions,
  the exact broker snapshot reference, and the frozen `inputsJson` that
  produced the row set (plus its SHA-256 digest). Row-level results are stored
  as canonical decimal text.
- Recomputing an old report today uses a **new** `reportId`; the historical
  result is never rewritten.
- `verifyReplay(reportId)` re-runs the engine on the frozen inputs and asserts
  bit-for-bit equality with the stored report. It refuses replay when engine
  or policy versions no longer match. It tolerates the anchor being
  subsequently invalidated: the baseline / cut / cursor evidence must not have
  changed, but the status projection is allowed to have advanced.

## Order decimal evidence

The permissive lifecycle parser is not modified in this phase. To conserve
exact decimals for **future** lifecycle observations, a sidecar table
`paper_order_decimal_evidence` is introduced. Rows attach to an existing
lifecycle observation by `observationId` (foreign key) and store the raw and
canonical decimal for the `qty` and `filled_qty` fields, plus provenance and
parser version. `PaperOrderDecimalEvidenceRepository`:

- refuses attachment to observations `<= lifecycleHighWater` captured before
  the adapter was in place;
- refuses attachment when the referenced observation is not
  `ALPACA_PAPER_ORDER_GET` with HTTP `200`;
- refuses attachment when the raw text does not round-trip through
  `DecimalQuantity` to the canonical text.

No v7 backfill is invented. Historic canonical order history keeps its
`LEGACY_DOUBLE_DERIVED` provenance.

## Migration `MIGRATION_7_8`

Purely additive. All eight new tables are declared with
`CREATE TABLE IF NOT EXISTS` and their indexes with
`CREATE INDEX IF NOT EXISTS`. There is no `DROP`, no `ALTER`, no destructive
fallback for v7 (Room's `fallbackToDestructiveMigrationFrom(1, 2, 3)` is
unchanged and continues to protect every version from v4 upward). New rows are
empty after migration; nothing is backfilled.

## Manual coordinator invariants

`PaperPositionEvidenceCaptureCoordinator.captureManually()`:

- serialises via a static mutex; a concurrent call returns `Busy` without
  issuing any GET.
- issues **at most two** GETs (`ACCOUNT` then `POSITIONS`) per invocation.
- never retries.
- never follows redirects.
- never issues an order-list, order-status, close-position, cancel, or replace
  call.
- assigns a fresh `manualRefreshId` and `snapshotId` per invocation.
- persists every attempt (`COMPLETE` or `FAILED`) transactionally. When the
  transaction throws, the coordinator returns `PersistenceFailure` and no
  partial row is left behind.
- when credentials are absent, records `AUTH_FAILURE` and issues no GET.

There is no `LaunchedEffect`, timer, `WorkManager`, service, alarm, or
`onNavigate` wiring that calls the coordinator. The only trigger will be a
manual UI action added later in 2.y.4.

## Test coverage (host-only, no emulator)

- `StrictPaperCaptureParserTest` — every parser diagnostic path, positions
  `[]` vs empty body, malformed JSON, invalid row, duplicate symbol,
  `NaN`/`Infinity`, blank/whitespace symbols, decimal exact round-trip
  (`1`, `1.00`, `0.1`, `0.000001`), side/sign contradictions, account
  parser (missing / null / non-UUID id, same id → same ref, different id →
  different ref, raw id never leaks).
- `PaperPositionEvidenceCaptureCoordinatorTest` — account/positions success
  publishes `COMPLETE`; account failure blocks positions; positions failure
  keeps snapshot `FAILED`; `[]` is a valid empty portfolio; invalid row is
  `FAILED`; duplicate symbol is `FAILED`; missing account id is
  `ACCOUNT_REF_UNKNOWN`; mid-capture credential change is `CONFIG_CHANGED`;
  persistence failure surfaces `PersistenceFailure`; concurrent second refresh
  is `Busy`; sequential refreshes both persist with distinct
  `manualRefreshId`s; failed refresh never replaces `latestComplete`;
  only the two allowed endpoints are ever touched.
- `PaperCaptureEvidenceSecurityTest` — endpoint set is exactly
  `{ACCOUNT_URL, POSITIONS_URL}`; every URL passes the Paper allowlist and
  contains no LIVE host / mutation fragments; OkHttp client disables retries,
  redirects, and proxy authentication; transport rejects a no-credentials
  session without touching the network; HTTP 401 / 403 map to `AUTH_FAILURE`
  with no body; 5xx map to `HTTP_FAILURE` with no body; `IOException` maps to
  `NETWORK_FAILURE`; `toString()` never leaks credentials or body content.
- `PaperPositionEvidenceRepositoryTest` — COMPLETE persistence, FAILED
  persistence, invariant refusals (rows-on-FAILED, empty-diagnostics-on-FAILED,
  missing accountRef on COMPLETE, duplicate `manualRefreshId`, duplicate
  captured symbol), latest-complete determinism, raw / canonical decimal
  round-trip, anchor create + duplicate-active refusal, invalidate +
  append-only event chain, supersede + history preservation, report create
  and re-read without running today's engine, report immutability across two
  createReport calls, `verifyReplay` reproduces bit-for-bit even after the
  anchor is invalidated, snapshot history ordering by sequence, position rows
  ordering by rowIndex.
- `PaperPositionEvidenceMigration7To8Test` (instrumented, compile-only in
  this phase) — populated v7 → v8 preserves the legacy
  `paper_order_submit_audit` row; every new v8 table exists and is empty; a
  clean v7 → v8 migration also produces empty tables; SQLite
  `integrity_check` and `foreign_key_check` are clean after migration.

## Execution / runtime attestation

- Runtime GET issued during 2.y.3: **0**.
- Runtime POST: **0**.
- IEX: **NO**.
- Emulator: **NO**.
- APK install: **NO**.
- Real anchor created: **NO**.
- Real reconciliation report generated: **NO**.
- REAL locked: **`true`**.
- LIVE: **`false`**.
- Auto Paper: **`false`**.
- Submit boundary diff: **0**.

Runtime validation of the manual GET capability is deferred to 2.y.5.

## Non-goals reminder

- 2.y.4: UI wiring of manual refresh, anchor establishment button, viewer.
- 2.y.5: real broker runtime validation.
- 2.y.6+: additional coverage as needed.
- 3.x: features / labels / outcomes / ML.
- 2.y (later sub-phase): position reconciliation actions, corrective trading.
