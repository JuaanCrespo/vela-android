# Phase 2.y.4 — Read-only position reconciliation integration

Implementation baseline: `febe18c55eb18dda6dd4f57bd8e31aec3df554b7`.
This phase integrates the published 2.y.2 domain and 2.y.3 evidence infrastructure.
The existing R1 real v7→v8 migration checkpoint is preserved, not repeated here.
**2.y.4 runtime has not been validated. Phase 2.y.5 is not started.**

## Actual route and composition

- Existing navigation: **Más → Posiciones Paper**.
- Destination: `VelaDestination.POSITIONS`, route `posiciones-paper`.
- Screen: `PositionReconciliationRoute` / `PositionReconciliationScreen`.
- ViewModel: `PositionReconciliationViewModel`.
- Data boundary: `PositionReconciliationStore`, implemented by
  `CanonicalPositionReconciliationStore`.
- `VelaLabApplication` supplies an inert lazy canonical evidence graph.
  `MainActivity` accesses its dedicated lazy ViewModel only inside the positions
  content lambda. The existing bottom bar still has exactly five destinations.
- Dashboard integration is limited to a composable slot. The position screen
  neither receives nor reads legacy dashboard account, positions or risk state.
  The existing 2.x order-history viewer is unchanged and is not duplicated.

## Offline first and explicit manual network

ViewModel initialization and section re-entry call `loadOffline` only. A single
Room transaction reads the last attempt, latest complete snapshot, all anchor
states and latest report with its original capture timestamp. No engine produces
a report while loading, composing, navigating, resuming or reconstructing UI.

Empty storage displays `NO BROKER SNAPSHOT YET`. Last attempt and latest complete
snapshot are separate cards; a failed attempt remains visible after recreation.

Only **Refresh positions** calls the existing
`PaperPositionEvidenceCaptureCoordinator.captureManually`:

1. At most one `GET /v2/account` and one `GET /v2/positions` via the unchanged
   strict transport and parser. An account failure skips positions.
2. The coordinator persists the snapshot/attempt in Room.
3. The integration reloads that identity from the snapshot repository.
4. Only a COMPLETE result may invoke the existing report repository, which
   transactionally reads full canonical history and active anchors, runs
   `LocalPositionExpectationEngine` and `PositionReconciliationEngine`, and
   persists the frozen inputs and report.
5. The ViewModel reloads the durable overview before publishing success.

The UI exposes IDLE, REFRESHING, SUCCESS, FAILED and BLOCKED. A synchronous busy
gate disables duplicate refresh and conflicting local operations. Coordinator
BUSY is explicit. Exception and cancellation paths release the spinner; exception
messages and unsafe response bodies are never displayed.

There are no new endpoints, URL allowlists, HTTP parsing in the ViewModel,
retries, redirects, polling, services, WorkManager jobs or network timers. The
existing transport keeps retries=0 and redirects disabled.

## Durable authority and failure semantics

Snapshot, failure attempt, anchor and report become authoritative only after
repository persistence and reload. No response object or prior ViewModel instance
is the source of truth. Fresh ViewModels reconstruct from durable records alone.

- A failed capture never produces a new reconciliation report or clears last-good
  evidence, anchors or historical reports.
- `CAPTURE_PERSISTENCE_FAILED` does not fabricate a saved attempt.
- `RECONCILIATION_PERSISTENCE_FAILED` preserves the new COMPLETE snapshot but does
  not claim a new durable report. An older report is explicitly labelled historical
  and remains bound to its own snapshot/account.
- A Room read failure fails closed and does not publish the operation as success.
- Reports are ordered by their source snapshot sequence, not by mutable wall-clock
  assumptions. Anchor loading retains INVALIDATED and SUPERSEDED history.

## Quantities, uncertainty and age

Each row exposes Symbol, Broker observed, VELA known delta, delta completeness,
Baseline, VELA expected, Difference, State and diagnostics/provenance.
Authoritative quantities use `DecimalQuantity.toString`; no conversion to Double
or floating-point formatting is added.

For broker=5 and known delta=2 with no baseline, a domain UNANCHORED result displays
expected=UNKNOWN and difference=NOT COMPARABLE, never expected=2 or difference=3.
Known delta remains a subtotal when completeness/precision is uncertain.

All eight domain states remain explicit: MATCH, MISMATCH, UNANCHORED, UNKNOWN,
STALE, INCONSISTENT_LOCAL_HISTORY, ANCHOR_INVALID and BROKER_READ_FAILED. Scoped
symbol errors are not flattened into a portfolio-wide MISMATCH. MISMATCH wording
is neutral: “Diferencia de posición sin explicación. Causa: UNKNOWN. Revisar
baseline.” There is no attribution to external trades, splits or software bugs.

`PositionObservationPolicy` defaults to a 60,000 ms maximum observation age and is
injectable with the ViewModel/store clock. Negative/regressing time is UNKNOWN.
Visual freshness is evaluated on section entry and explicit actions, labelled as
such; there is no timer. The row projection uses the report's **original broker
capture time**, not report creation time or a newer snapshot's timestamp. Local
staleness projection does not overwrite a durable report or perform a GET.

## Account scope and legacy limitations

The canonical account reference is opaque. UI abbreviates only a valid
`paper-v1:<64 lowercase hex>` value, otherwise displays UNKNOWN. It never shows
raw Alpaca account IDs, auth headers, keys, secrets or unsafe error bodies.
Diagnostics are enum categories; unexpected stored diagnostic text is replaced
with `INVALID_DURABLE_DIAGNOSTICS` rather than displayed verbatim.

Full history is read without a LIMIT and includes partial realized fills. However,
“read every local row” does not prove that all rows belong to today's account.
Integration confirms scope only when the consistent evidence reader accepts the
account and every historical order has explicit durable decimal/account binding
(or the history is empty). It never infers the legacy binding from credentials,
order IDs, SPY fills, prices or current positions.

Consequently the real legacy A/B histories can remain visible as known evidence
but do not become newly exact or account-bound in this phase. UNKNOWN /
INCOMPLETE_HISTORY and disabled baseline creation are valid fail-closed outcomes.
No decimal/account backfill is implemented. Future runtime must not weaken those
gates to obtain a MATCH.

## Human-controlled baselines

**Establish baseline** is per symbol and requires a deliberate two-step flow:
first open the summary dialog, then explicitly confirm. The summary shows symbol,
abbreviated Paper account reference, snapshot ID/time, exact broker baseline,
history checkpoint/coverage, cursor count, known delta and the warning:

> Aceptar este baseline NO verifica ni reconstruye actividad anterior de la cuenta.

Symbol selection is a separate local form control so the user can explicitly
choose a relevant symbol, including one absent from a COMPLETE snapshot. An absent
symbol can have exact baseline 0 only if all the same eligibility gates pass;
there is no automatic baseline creation for historical symbols.

Eligibility belongs to the repository/domain, not the UI:

- COMPLETE, anchorEligible snapshot with known account reference;
- fresh observation and stable matching local checkpoint;
- exact baseline provenance and matching cut;
- full scoped local history, sufficient exact cursors and ANCHORED domain coverage;
- no conflicting ACTIVE anchor for the symbol/account.

The added read-only `PaperPositionAnchorRepository.prepareAnchor` creates a
proposal and reuses the same validation routine as creation. It preserves broker
quantity provenance rather than relabelling an imprecise value as exact. All gates
are checked again in the creation transaction at final confirmation, including
changed snapshot, account, history and active conflicts.

Creation persists through `createAnchor` and reloads Room. It does not issue a GET
or create a new reconciliation report from the baseline's own snapshot. UI marks
the changed coverage as requiring a future manual refresh.

**Invalidate baseline** is available only for ACTIVE anchors and also requires a
confirmation dialog showing symbol, quantity, anchor ID and the supported MANUAL
reason. It writes the repository's append-only event and reloads; no broker call,
order, history reset or new report occurs. MISMATCH never silently invalidates an
anchor. The minimal policy is invalidate, then explicitly establish a new baseline;
supersession is not added to the UI and historical baselines are never overwritten.

When a saved report references an anchor that is now invalidated/superseded, its
current visual projection is ANCHOR_INVALID with no comparable difference. The
saved report itself is unchanged. A subsequent manual refresh uses only active
anchors and cannot reuse an invalid baseline as expected quantity.

## Final action surface

- Refresh positions.
- Inspect/hide diagnostics (local presentation).
- Select a baseline symbol (local form selection; required for explicit zero baseline).
- Establish baseline, with final human confirmation.
- Invalidate baseline, with final human confirmation.
- Dismiss a local confirmation dialog.

Trading actions=0. There are no corrective buy/sell/close/sync buttons. The
integration and dedicated UI/ViewModel packages have no dependency on the execution
package, manual submit executor, token, arm, readiness or order mutation clients.

## Storage and boundary freeze

Room remains **v8**. Entity definitions, exported schemas and migrations are
unchanged; no Migration8To9. Two read-only DAO queries were added for all anchor
states and latest report identity. They require no new persistence structures.

Submit/status endpoints, clients, guards, executors, tokens, phrases and protected
manual UI expressions are unchanged. `PaperTradingExecutionGuard.canExecuteOrders`
remains false; Release manual submit compiled=false; REAL locked=true, LIVE=false,
Auto Paper=false. No local.properties, SDK or Gradle configuration changes.

## Verification scope

New host tests exercise the real coordinator, parser, repositories and domain
engines with fake transport and an in-memory evidence DAO; they do not contact
Alpaca. SQL contracts additionally execute the exact new DAO queries on host
SQLite initialized from the unchanged exported v8 schema. ViewModel tests cover
durable reload order, concurrent clicks, cancellation, failures, confirmations,
staleness and fresh-instance recovery. Presentation/source contracts cover all
states, exact decimals, opaque references, route wiring and forbidden dependencies.

These are not Android instrumentation, visual-device or broker-runtime tests.
No emulator/app interaction, APK installation, real GET/POST, IEX use, real anchor
creation or real reconciliation is performed in 2.y.4. No commit or push is part
of this task; staging remains empty.

Fresh full Debug/Release unit tests and lint results are recorded in the final
verification report after execution. The only allowed known lint exception is the
untouched pre-existing `Symbols.kt:40` NewApi finding; no suppressions are added.
