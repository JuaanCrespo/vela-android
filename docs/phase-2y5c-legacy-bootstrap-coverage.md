# Phase 2.y.5-C — Legacy bootstrap coverage / Room v9

## Current audit/publication status — 2026-10-01

`READY_2Y5C2_TO_PUBLISH`. Independent final offline re-audit complete; this is the
approved single-commit publication payload. Final commit/remote verification is
recorded in the C.2 publication receipt and final user report described below.

The subsequent final publication audit reproduced a terminal exact-fill
contradiction that the bootstrap path incorrectly accepted. Its original blocker
was `BLOCKED_2Y5C_PUBLICATION_INTEGRITY_GAP`; the correction and permanent
regressions are recorded at the end of the same audit trail below. The earlier
implementation verdict and 2,070-test results below are historical implementation
evidence, **not publication clearance**. No staging, commit or push was performed
during C.1 (`PASS_2Y5C1_EXACT_DECIMAL_INTEGRITY_FIXED`). C.2 clearance is recorded
at the end of this document. Runtime remains untouched and unvalidated.

Baseline: `a46b5ea2723f26609e8f6313e390d96c4709063f`.
Implementation started 2026-09-21 and resumed 2026-09-28.
Original C/C.1 scope: implementation and OFFLINE verification only. C.2 adds
audited publication, not runtime, installation, real-anchor or R2 validation.

## Policy correction, not historical repair

2.y.1 defined expected position as an accepted broker baseline plus validated fills
AFTER that baseline. The v8 integration instead required every canonical history
to have decimal/account evidence, plus exact historical cursors. Adding another
exact order cannot satisfy that universal condition while A/B remain legacy.
Verdict from the preceding audit: `ARCHITECTURE_POLICY_MISMATCH`.

Two durable modes now have separate branches:

- `EXACT_CURSORS_V1`: existing v8 arithmetic, cursor validation, completeness and
  precision requirements. It remains the default for old anchors and the first
  preparation policy attempted. The exact repository method is not weakened.
- `LEGACY_BOOTSTRAP_V1`: a separate proposal, explicitly disclosed in selection
  and confirmation. Historical unknowns are absorbed into a user-accepted broker
  baseline, not made exact, reconstructed or attributed to today's account.

For bootstrap, `preAnchorHistoryAssurance=LEGACY_UNKNOWN`; independently,
`postAnchorCoverageAssurance` must be `CONFIRMED` to compare. The original history
is not relabelled COMPLETE. `historicalKnownVelaDelta` is informational and
`postAnchorExactDelta` is the only delta used in expected quantity.

| Baseline | Historical legacy delta | Trustworthy post-cut delta | Expected |
| --- | --- | --- | --- |
| 8 | +2 | 0 | 8 |
| 8 | +2 | BUY 1 | 9 |
| 8 | +2 | SELL 2 | 6 |

The empty post-cut sum is exact zero; it is NOT a fictitious per-order cursor.

## Durable cut and deterministic manifest

`BootstrapCutManifest` V1 contains the mode, snapshot ID/sequence, opaque account,
symbol, exact baseline with provenance, original local-history digest, durable
submit-audit high-water, lifecycle/order limits, capture/acceptance times and a
frozen inventory of canonical histories and classifications.

The submit boundary is the maximum of canonical order sequence, audit-start ID
and audit-result ID over the FULL history. The canonical repository enumerates
all attempts, retaining the latest start/result audit references. No latest-N
window or wall-clock inference establishes this boundary.

Relevant histories are classified as terminal/absorbed or demonstrably BLOCKED
without a broker order/lifecycle; attributable unrelated symbols remain explicitly
identified. The manifest preserves original legacy projections and fingerprints,
not fabricated raw decimals. Exact requested/terminal quantities at the cut are
recorded only when genuine validated sidecars already exist.

`BootstrapEvidenceCodec` uses a typed positional JSON-array schema, fixed field
order and identity-sorted inventory. SHA-256 covers its UTF-8 canonical encoding.
Decode verifies digest, version, types and canonical re-encoding. It does not hash
arbitrary object `toString()` output or unordered maps. Legacy number rendering
in the nested historical codec remains explicitly legacy.

The old V1 checkpoint encoder is preserved: adding manifest/report V2 metadata
does not change the historical checkpoint's bytes or meaning.

## Eligibility and symbol scope

Broker gates remain COMPLETE, strict parser success, received=validated=row count,
exact quantities, no duplicate symbol, valid opaque accountRef, persisted eligible
capture, stable matching local checkpoint, matching current configuration and no
active conflicting anchor. Freshness remains **60,000 ms**, with clock regression
UNKNOWN. Bootstrap does not relax broker evidence quality.

Legacy terminal SPY can be absorbed for SPY and does not by precision alone block
QQQ. A symbol with no local orders may accept exact zero only when absent from a
valid COMPLETE snapshot. Neither path creates an anchor automatically.

Unknown symbols, duplicate identities and source/mapping identity ambiguity
propagate beyond a symbol. An attributed quantity/history problem remains scoped
where it can safely be isolated. Relevant terminality and source integrity are
required; unresolved, open, partial, in-flight or possibly sent orders block the
bootstrap. LOCAL_SUBMIT_AUDIT zero is never proof of broker zero fill.

Preparation is not authority. Final confirmation rechecks current snapshot,
configuration, freshness, checkpoint, active conflicts, manifest and eligibility
in the same Room transaction as insertion. Acceptance time/digest are finalized
before persistence, never rewritten afterward. Snapshot/config/history changes
and conflicts have explicit sanitized UI block reasons.

## Crossing-cut evidence and subsequent arithmetic

A post-cut observation ID alone does not make an order post-cut. A contributing
order must have durable ATTEMPT_STARTED strictly beyond the submit high-water,
order and lifecycle evidence beyond their cuts, unambiguous identity, proven
account association, valid side/lifecycle, and exact source decimals. This reads
existing audit evidence; it never invokes or modifies the executor.

Post-cut cumulative observations are not summed as independent fills: repeated
0.4, then 0.7, then terminal 1 contribute 1. Open/uncertain post-cut exposure or
missing exact evidence prevents authoritative expected/comparison.

For absorbed pre-cut orders, the original prefix and identity must remain intact.
A genuinely repeated canonical observation with identical payload adds nothing.
Changed fill, terminal contradiction, missing history or ambiguous new evidence
invalidates coverage; it is never counted as a new post-cut fill.

Additional precision is also information: a later raw decimal is a provable raw
duplicate only if matching exact source quantities were preserved at the cut.
An old legacy projection alone cannot prove that equality through floating-point
rounding. Such a later exact observation fails closed rather than being silently
treated as either a new fill or an exact duplicate.

Expected is baseline + exact post-cut delta only. Broker minus expected yields
exact MATCH/MISMATCH without tolerance, only under all coverage/account/freshness
gates. Historical legacy diagnostics stay visible but are not global comparison
blockers by themselves. Real ambiguity and contradictions still block.
MISMATCH cause remains UNKNOWN and carries the existing review/invalidation
diagnostic. No engine mutates an anchor or produces trading actions.

## Future exact lifecycle evidence; unchanged network contract

`STATUS_EVIDENCE_PLUMBING_CHANGED=YES`.
`NETWORK_CONTRACT_CHANGED=NO`.

The existing status GET response is inspected before the legacy display parser
converts quantities. Only original string decimal fields from strict JSON are
accepted. The separate allow-listed raw-evidence value redacts its diagnostic
representation. Numeric JSON converted to floating point is not reconstructed as
original decimal text. The display parser's existing acceptance behavior remains.

The existing authorized account/positions capture establishes an ephemeral
accountRef/configuration binding. It is cleared on credential change, at the next
capture attempt and on process restart; successful complete capture establishes
it again. The status request checks the same binding before/after its existing GET.
No new account GET, retry, redirect, endpoint or background request is introduced.

If binding is unknown, status behavior remains available, but no account identity
is invented and no exact sidecar is attributed. Coverage then fails closed. After
restart, a separately authorized manual capture is necessary to re-establish that
in-memory proof; the application does not perform it automatically.

`FutureLifecycleDecimalEvidenceWriter` wraps future lifecycle insertion, projection
and sidecar attachment in the same `Room.withTransaction` boundary. The sidecar
uses the actual newly inserted observation ID and canonical payload fingerprint.
The existing high-water barrier prevents legacy observation backfill. Raw text
`0.100000000000000001` remains exactly that text and canonical decimal, not `0.1`.

Only the bootstrap derivation path allows original exact quantities to be checked
against their lossy legacy storage projection. Arithmetic still uses the exact
source. The default V1 deriver and exact-cursor path retain their prior behavior.

## Additive Room v9 and immutable report versions

`MIGRATION_8_9` adds only:

| Table | Addition |
| --- | --- |
| paper_position_anchor | coverageMode NOT NULL DEFAULT EXACT_CURSORS_V1 |
| paper_position_anchor | nullable bootstrapCutJson |
| paper_position_anchor | nullable bootstrapCutDigest |
| paper_position_reconciliation_row | nullable coverageJson |

No table, old column, A/B record, reset, observation, snapshot, report, cursor,
event or decimal sidecar is deleted or rewritten. Old anchors are exact-cursor
anchors, never fake bootstrap anchors. Old report rows have null new metadata.
Room exports `9.json`; there is no Migration9To10. Destructive fallback is removed;
unsupported pre-audit schemas now fail rather than discard data.

New bootstrap reports use `POSITION_ENGINE_2Y5_BOOTSTRAP_V2`,
`POSITION_POLICY_LEGACY_BOOTSTRAP_V1` and input codec V2. Row-level coverage JSON
V1 freezes the two assurances, the two deltas and manifest reference/digest.
Reports without bootstrap retain existing V1 versions and input semantics.

Loading reports returns their stored results, not today's recalculation. Replay
dispatches by compatible frozen inputs/versions and rejects unsupported versions.
Invalidating an anchor only changes its allowed status projection and appends an
event. Baseline, snapshot, mode, manifest and digest remain immutable, including
for historical replay. A new calculation requires a new report ID.

## Minimal UI change

Selection and confirmation show the proposed coverage mode, symbol, abbreviated
opaque account, snapshot and exact baseline. Bootstrap warning:

> Aceptás la cantidad observada por el broker como punto de partida para este símbolo.
> VELA no certifica ni reconstruye la actividad anterior a este snapshot.
> Esta acción no envía órdenes ni modifica posiciones del broker.

Bootstrap rows separate historical known delta/provenance, baseline, post-anchor
exact delta/assurance and expected. Old report rows preserve their old layout.
Corrupt durable manifests cannot leave an authoritative cached comparison visible;
local loading fails closed, clears comparable rows and exposes a sanitized error.
Selection, confirmation, invalidation and offline re-entry make no network calls.

## Verification and limits

Tests include A/B legacy preservation; 8/9/6 arithmetic; symbol isolation; open,
synthetic-zero and ambiguous exposure; late/duplicate evidence; original decimal
precision and atomic write failure; configuration/account binding; stale/changed
confirmation; manifest tampering/immutability; V1/V2 replay; UI/ViewModel; and
actual host SQLite execution of the production migration SQL.

The populated host migration fixture includes legacy A/B, another order with
genuine fixture decimal sidecars, COMPLETE/FAILED captures, position rows, report
history and an existing anchor/cursor/event. Every original column is compared
before/after, with foreign-key and integrity checks. Empty v8 is tested separately.
Instrumentation migration tests are compiled, not executed on a device in this
phase. These host proofs do NOT claim migration of the currently installed DB.

The reported eleven existing runtime snapshots/reports remain untouched. Tests
use explicit fixture IDs/sequences and before/after comparisons, not an assumed
runtime total. No repeated real Refresh was performed during this implementation.

Final fresh Debug/Release counts, instrumentation compilation, lint, safety and
scope checks are recorded below. Verification completed 2026-09-28.

## Implementation-stage worktree and execution boundary (historical)

No commit, push or staging. Tracked phase changes remain unstaged; new phase files
remain untracked until a separately authorized publication step. The three
pre-existing untracked `ui/showcase/` files are user-owned and are neither edited
nor included in this phase. Their SHA-256 values are checked independently.

Submit HTTP/client/executor, token/arm/phrase, preflight guards and manual trading
UI are unchanged. `canExecuteOrders=false`; release manual submit compiled=false.
REAL remains locked, LIVE and Auto Paper disabled. Broker GET=0, POST=0, real
anchors=0, emulator interaction=0, APK install=0, IEX=NO. No runtime R2 validation.

## Final verification results — 2026-09-28

Verdict: `PASS_PHASE_2Y5C_LEGACY_BOOTSTRAP_IMPLEMENTED`.
This is an implementation/offline verdict, NOT a runtime, publication or R2 verdict.

| Check | Observed result |
| --- | --- |
| HEAD and local origin/main | Both `a46b5ea2723f26609e8f6313e390d96c4709063f` |
| Fresh Debug, `--rerun-tasks` | PASS: 2,070 tests; 0 failures, 0 errors, 0 skipped; 26 tasks executed |
| Fresh Release, after Debug, `--rerun-tasks` | PASS: 2,070 tests; 0 failures, 0 errors, 0 skipped; 27 tasks executed |
| New test invocations per variant | 63: bootstrap 49, future decimal evidence 8, host migration 3, ViewModel 3 |
| Instrumentation compilation | `:app:compileDebugAndroidTestKotlin` PASS; two new migration tests compiled; no device execution |
| Room / migration / export | Version 9; `MIGRATION_8_9` registered; `9.json` exported; no Migration9To10 |
| Migration additive / v8 preservation | YES / PASS in host SQLite and schema contracts; exactly four new columns |
| Existing v8 anchors | Default `EXACT_CURSORS_V1`; null bootstrap manifest/digest; no invented cursors or decimals |
| Safety scanner | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| Lint Debug and Release | Both fail only on the permitted `Symbols.kt:40 NewApi` error; detail below |
| Phase-file lint findings | 0; no new lint suppressions |
| Whitespace / diff checks | PASS for tracked diff and new phase files |
| Submit/execution boundary diff | 0; submit package, preflight, manual dashboard, status endpoint/HTTP transport unchanged |
| Historical schema and build settings | `8.json`, `Symbols.kt`, `build.gradle.kts` unchanged |
| Generated manual-submit flag | `MANUAL_PAPER_SUBMIT_COMPILED=false` in both Debug and Release |
| Status evidence / network contract | `STATUS_EVIDENCE_PLUMBING_CHANGED=YES`; `NETWORK_CONTRACT_CHANGED=NO` |
| Runtime actions in this phase | Broker GET=0, POST=0, Refresh=0, real anchor=0, APK install=0, emulator interaction=0, IEX=NO |
| Execution safety | REAL locked=true; LIVE=false; Auto Paper=false; `canExecuteOrders=false`; no runtime settings changed |
| Publication | Staging empty; commit=NO; push=NO |
| User showcase | Three pre-existing untracked files; hashes unchanged; excluded from phase scope |

Both unit-test runs regenerated their XML results on 2026-09-28. Results were
counted from those fresh XML files, not inferred from an earlier run. The final
production/test sources were unchanged between Debug, Release and lint.

Commands ran from `G:\vela-android\android`, using the JBR under
`C:\Android\Android Studio 2026.1.2\jbr`, SDK `C:\Android\Sdk` and Gradle cache
`C:\Android\gradle-home`:

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --rerun-tasks --console=plain --max-workers=2
.\gradlew.bat :app:testReleaseUnitTest --offline --rerun-tasks --console=plain --max-workers=2
.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:lintDebug :app:lintRelease --offline --continue --console=plain --max-workers=2
.\gradlew.bat :app:compileDebugAndroidTestKotlin --offline --console=plain --max-workers=2
& .\scripts\safety-scan.ps1
```

The combined compilation/lint command exited 1 because BOTH lint tasks correctly
reported the known NewApi error. Instrumentation compilation itself completed;
the standalone follow-up confirmed `BUILD SUCCESSFUL` with the compiled classes
up to date. No instrumentation test was run.

Each lint report contains 1 error, 14 warnings and 1 informational finding.
The error is the unchanged `Symbols.kt:40` URLDecoder API requirement. Thirteen
warnings and the informational finding match the earlier Debug lint baseline;
the remaining warning is `ModifierParameter` at
`ui/showcase/VelaShowcaseComponents.kt:161`, in the explicitly protected,
pre-existing user file. None of the findings targets a phase-modified/new file.
No lint baseline or suppression was added, and the protected showcase was not fixed.
Thus lint is **not globally green**; the phase has no introduced lint finding.

Fresh unit XML and final lint XML copies are retained outside the repository at:

```text
C:\Android\audits\phase-2y5c-20260928\debug-unit-results\
C:\Android\audits\phase-2y5c-20260928\release-unit-results\
C:\Android\audits\phase-2y5c-20260928\lint-results-debug.xml
C:\Android\audits\phase-2y5c-20260928\lint-results-release.xml
```

## Original implementation Git scope manifest (historical)

There are **24 tracked, unstaged modifications** (21 production + 3 JVM tests)
and **10 new, untracked phase files** (4 production + 3 JVM tests + 1
instrumentation test + 1 schema + this document). Total phase scope: 34 files,
including 25 production Kotlin files and 7 test files. No staging was performed.
The worktree is intentionally NOT clean.

Production paths below are relative to
`android/app/src/main/kotlin/com/vela/android/lab/`.

Tracked production modifications:

```text
VelaLabApplication.kt
data/paper/reconciliation/domain/DecimalQuantity.kt
data/paper/reconciliation/domain/LocalPositionExpectationEngine.kt
data/paper/reconciliation/domain/OrderRealizedFillDeriver.kt
data/paper/reconciliation/domain/PositionDomainModels.kt
data/paper/reconciliation/domain/PositionReconciliationEngine.kt
data/paper/reconciliation/evidence/PaperPositionAnchorRepository.kt
data/paper/reconciliation/evidence/PaperPositionEvidenceCaptureCoordinator.kt
data/paper/reconciliation/evidence/PaperPositionEvidenceHttpTransport.kt
data/paper/reconciliation/evidence/PaperPositionEvidenceModels.kt
data/paper/reconciliation/evidence/PaperPositionReconciliationReportRepository.kt
data/paper/reconciliation/evidence/PositionEvidenceCodec.kt
data/paper/reconciliation/integration/PositionReconciliationStore.kt
data/paper/status/AlpacaPaperOrderStatusReadOnlyClient.kt
data/paper/status/PaperOrderStatusModels.kt
data/paper/status/PaperOrderStatusTrackerRepository.kt
db/room/VelaDatabase.kt
db/room/entities/PaperPositionEvidenceEntities.kt
ui/positions/PositionReconciliationScreen.kt
ui/positions/PositionReconciliationUiState.kt
ui/positions/PositionReconciliationViewModel.kt
```

New production files:

```text
data/paper/reconciliation/domain/BootstrapCoverage.kt
data/paper/reconciliation/evidence/BootstrapEvidenceCodec.kt
data/paper/reconciliation/evidence/FutureLifecycleDecimalEvidence.kt
db/room/migrations/Migration8To9.kt
```

JVM test paths below are relative to
`android/app/src/test/kotlin/com/vela/android/lab/`.

Tracked test modifications:

```text
data/paper/reconciliation/domain/PositionDomainIsolationTest.kt
data/paper/reconciliation/integration/PositionReconciliationIntegrationTest.kt
ui/positions/PositionReconciliationViewModelTest.kt
```

New JVM tests:

```text
data/paper/reconciliation/evidence/BootstrapMigrationSqlTest.kt
data/paper/reconciliation/evidence/FutureLifecycleDecimalEvidenceTest.kt
data/paper/reconciliation/integration/LegacyBootstrapCoverageTest.kt
```

Other new phase files, relative to the repository:

```text
android/app/src/androidTest/kotlin/com/vela/android/lab/db/room/LegacyBootstrapMigration8To9Test.kt
android/app/schemas/com.vela.android.lab.db.room.VelaDatabase/9.json
docs/phase-2y5c-legacy-bootstrap-coverage.md
```

Separately, the user-owned pre-existing untracked showcase files remain:

| File under `android/app/src/main/kotlin/com/vela/android/lab/ui/showcase/` | Unchanged SHA-256 |
| --- | --- |
| VelaShowcaseComponents.kt | `A36707DB0FAF4E98FCC704DDCF917D5966E37E691AA4D2EA5C510AB5BA13B4FD` |
| VelaShowcaseFixtures.kt | `825D97E44019A0D5E13569C01F5307E5E807BCFD17141D3DEADEB265DFB449E6` |
| VelaShowcaseScreen.kt | `67102DED38C760E05B1436945FE789CF406276D931CAC1CF0CD9A05D3DA3070F` |

Remaining boundary: runtime migration of the installed v8 database, real bootstrap
acceptance, broker comparison and publication all require a separately authorized
phase. None was attempted here; this report does not claim R2 succeeded.

## Final publication audit — original integrity blocker (historical)

Audit started 2026-09-28 and resumed 2026-09-29. Required baseline matched after
`git fetch origin`: HEAD and origin/main were both
`a46b5ea2723f26609e8f6313e390d96c4709063f`, branch main, staging empty.
Git independently enumerated 24 modified + 10 new phase paths. The 3 user-owned
untracked showcase files matched the SHA-256 values above. No production or test
source was modified during this final audit; this report was updated with the
finding. A standalone diagnostic was created outside the repository.

### P1 — exact terminal fill can change without invalidating bootstrap coverage

`OrderRealizedFillDeriver.kt:65-70` checks terminal immutability using the legacy
`Double` observation quantities. The new `exactSourceProjection=true` branch
allows genuine source decimals with precision beyond that projection. However,
terminal immutability is not also checked using those exact quantities.
The pre-existing canonical history integrity check likewise compares the legacy
projection, so it does not catch this case on behalf of the new branch.

Reproduction: a demonstrably post-cut BUY order, requested quantity 1, has two
terminal `canceled` observations with original filled quantities:

```text
first:  0.100000000000000001
later:  0.100000000000000002
```

Both project to the same Double `0.1`, but the second reports a different
terminal fill. With exact sidecars and a bootstrap baseline of 8, the compiled
production classes produced:

```text
sameLegacyProjection=true
derivedIntegrity=RELIABLE; completeness=TERMINAL
derivedDiagnostics=[]
postAssurance=CONFIRMED
expected=8.100000000000000002
comparison=MATCH
```

Required result: terminal contradiction detected, post coverage not CONFIRMED,
no authoritative expected quantity or MATCH/MISMATCH. The standalone diagnostic
assertion fails with exit 1. This blocks publication despite the existing suites
passing. It is a bootstrap integrity gap, not a migration or network-contract
failure, and does not authorize a corrective trade.

The probe invokes the actual compiled `OrderRealizedFillDeriver`,
`LocalPositionExpectationEngine`, `BootstrapEvidenceCodec` and
`PositionReconciliationEngine`; it uses the existing repository test fixture
factory for inputs rather than reimplementing their algorithms. It computes a
real manifest digest. No HTTP, emulator, Room runtime or broker is involved.
Three control cases pass: an identical exact terminal reread remains reliable;
a terminal change visible in Double is inconsistent; a same-Double exact decrease
is inconsistent. The undetected case is a same-Double exact increase.

Reproducible source and freshly counted unit XML are retained at:

```text
C:\Android\audits\phase-2y5c-publication-20260929\TerminalPrecisionProbe.java
C:\Android\audits\phase-2y5c-publication-20260929\debug-unit-results\
C:\Android\audits\phase-2y5c-publication-20260929\release-unit-results\
```

### Fresh verification performed in this audit

`AUDIT_TEST_START_UTC=2026-09-28T16:20:17.6690973Z`.

The environment rejected the requested manual removal of generated test-result
directories; that command made no changes. Both variants were nevertheless run
with `--rerun-tasks`, sequentially. Every suite timestamp and XML modification
time was checked against the recorded audit start, with **0 stale XML files**.

| Check | Observed result |
| --- | --- |
| Debug rerun | Exit 0; 2,070 tests; 0 failures/errors/skipped; 109 fresh XML suites; 26 tasks executed |
| Release rerun, after Debug | Exit 0; 2,070 tests; 0 failures/errors/skipped; 109 fresh XML suites; 27 tasks executed |
| Additional terminal-precision probe | FAIL, exit 1; authoritative MATCH incorrectly produced |
| Safety rerun | 11 allowed / 0 suspicious / 0 forbidden |
| Publication gate | FAIL; no staging, commit or push |
| Runtime / broker | No emulator interaction, install, Refresh or real anchor; GET=0, POST=0, IEX=NO |

Instrumentation compilation and lint results earlier in this document belong to
the implementation verification. They were not rerun as final-publication gates
after the blocking semantic finding. No claim of completed publication audit or
`PASS_PHASE_2Y5C_PUBLISHED_CLEAN` is made. The installed database was not opened or
migrated; its reported v8 runtime remains outside this audit.

Follow-up requested when that audit stopped: enforce exact terminal-fill
immutability in the bootstrap derivation path; add regression coverage for
same-Double/different-exact terminal fills and preserve valid duplicates and V1
semantics; then repeat the entire publication gate before staging anything.

### Resolution — Phase 2.y.5-C.1 exact-decimal integrity correction

Only one production path changes relative to the unpublished 2.y.5-C worktree:
`data/paper/reconciliation/domain/OrderRealizedFillDeriver.kt`.
The new permanent test file is
`data/paper/reconciliation/integration/ExactDecimalIntegrityRegressionTest.kt`
under `android/app/src/test/kotlin/com/vela/android/lab/`. This document is the
only other C.1 edit. Original 2.y.5-C work is preserved, not reset or restaged.

The root cause was not loss of the raw source sidecar: both exact values were
preserved. The precision loss occurred in the parallel legacy lifecycle model,
and the terminal stability check consulted that model alone. The existing
bootstrap source-projection adapter allowed the exact sidecar to survive, but
had not upgraded terminal stability or repeated-observation classification.

The correction checks the selected exact quantity against the selected exact
quantity of the **first terminal observation**, using `DecimalQuantity` value
equality, before deriving a realized fill. A mismatch adds `TERMINAL_CHANGED`,
sets derived integrity/completeness to INCONSISTENT and clears realized quantity.
Bootstrap then rejects confirmed post-cut coverage, clears expected quantity and
cannot produce MATCH/MISMATCH. The report remains observational; it does not
automatically invalidate a durable anchor or issue a trade.

The related fingerprint collision is also handled: a shared legacy fingerprint
does not add `REPEATED_OBSERVATION` when consecutive exact source quantities differ.
Genuinely identical exact rereads remain valid. Neither correction changes a
canonical fingerprint or rewrites historical evidence.

Both precision-sensitive additions are confined to `exactSourceProjection=true`,
the existing bootstrap source-exact derivation path. Default/V1 derivation retains
its old strict projection contract and its original diagnostics, including for
already-rejected inputs. This preserves historical V1 replay semantics rather
than silently changing the diagnostics of old inconsistent reports. There is no
new tolerance, rounding, exact-from-legacy conversion or general history bypass.

#### Authority trace

| Stage | Representation and authority |
| --- | --- |
| Existing status GET response | Original string `qty` / `filled_qty` captured by `captureFutureOrderDecimals` before the display parser runs; no additional request |
| Strict raw capture and sidecar writer | Original text parsed as `DecimalQuantity`; account/attempt/order/observation identity validated; original and canonical text persisted together with provenance |
| Lifecycle/status/canonical history | Existing nullable/non-null Double projections retained for compatibility; these are not proof of source precision |
| `ConsistentPositionHistoryReader` | Verifies raw/canonical equality, account, order identity, observation ID and fingerprint; constructs exact decimal evidence without a binary roundtrip |
| `OrderRealizedFillDeriver` | Selects validated exact sidecar values; original raw decimals govern bootstrap bounds, progression and terminal stability; missing sidecars remain legacy/unknown |
| `LocalPositionExpectationEngine` / bootstrap policy | Requires valid post-cut exact evidence; baseline plus post-cut exact delta only; historical legacy remains informational |
| `PositionReconciliationEngine` / reports | Exact broker, expected and difference quantities use decimal value semantics; reports freeze those values/provenance and compatible inputs for replay |

#### Permanent red/green regression

The named parameterized regression is:
`distinct exact terminal fills that collapse to same Double are contradictory end to end`.
It uses exactly `0.100000000000000001` and `0.100000000000000002`, proves they differ
as `DecimalQuantity`, and separately documents their equal Double projections.
The assertions for coverage, expected quantity and reconciliation never depend
on Double equality as authority.

It covers CANCELED, EXPIRED and FILLED terminal observations. CANCELED/EXPIRED
expose the original false-confirmed-coverage defect without a separate requested
quantity violation. FILLED also verifies the terminal-change diagnostic even
when the exact requested-quantity bound already independently rejects the row.
Inputs deliberately share the legacy fingerprint while retaining distinct exact
sidecars, so a fingerprint collision cannot mask the defect.

Before the production correction, the initial 15-case class ran with exit 1:
4 failures, 0 errors, 0 skipped. CANCELED and EXPIRED failed explicitly because
post coverage was still CONFIRMED; FILLED lacked `TERMINAL_CHANGED`; distinct
partial quantities were incorrectly labelled repeated. The other controls
passed. After correction, all 15 passed. An additional V1-diagnostic compatibility
test brought the final permanent class to **16 passing cases**.

The first full Debug attempt also caught the isolation scanner matching a new
comment's `Double` token. The comment was corrected; the isolation test was NOT
weakened or suppressed. Final tests below are reruns after that correction and
the additional V1 compatibility guard/test, not reuse of the earlier attempt.

#### Collateral precision audit and remaining floating-point occurrences

Classification: A = legacy/display/non-authoritative representation; B = legacy
compatibility check whose result cannot certify exact position truth because
provenance and independent exact gates govern authority; C = precision-sensitive
quantity bug. The audit covers all production matches in reconciliation domain,
evidence, status/lifecycle plumbing, anchor/cut/report code, plus the canonical
history boundary they consume. No unrelated legacy UI was refactored.

| Occurrence / comparison | Class after correction | Reason |
| --- | --- | --- |
| `DecimalQuantity.kt`: `QuantityEvidence.legacy(Double?)`, finite check and decimal rendering | A | Always LEGACY_DOUBLE_DERIVED; never creates EXACT_DECIMAL |
| `DecimalQuantity.kt`: `legacyProjectionMatches`, exact text converted to Double for storage compatibility | B | Checks only the lossy projection; arithmetic and all source-exact quantity comparisons retain the original `DecimalQuantity` |
| `OrderRealizedFillDeriver`: legacy terminal quantity comparison and synthetic submit zero | B | Source-exact terminal equality is now separately mandatory; default V1 already rejects precision incompatible with its projection contract; synthetic zero is not broker fill evidence |
| `OrderRealizedFillDeriver`: quantity fingerprint collision | C fixed | Source-exact values must agree before a shared legacy fingerprint is labelled a repeat |
| `OrderRealizedFillDeriver`: average-price equality/finite/positive checks | A | Legacy price plausibility only; no exact price quantity exists in the durable input contract; see price limitation below |
| `PositionEvidenceCodec`: legacy requested/fill/limit-price/average-price text rendering and `toDouble()` on legacy history decode | A | Preserves the historical V1 representation/hash; exact sidecars, cursor quantities and coverage values have separate canonical decimal encodings |
| `PaperOrderDecimalEvidenceRepository`: raw requested/fill `toDouble()` equality with one identified lifecycle projection | B | Coherence check only; original raw strings and exact canonical values are persisted, never reconstructed from the projection |
| Same repository: raw average-price projection comparison | A | Transient price coherence check, not durable exact price certification |
| `PaperOrderStatusJsonParser`: numeric/string conversions and finite/positive checks | A | Existing legacy model parser; original text is captured before these conversions |
| Status parser/model/client: requested identity, fill bounds, FILLED equality and lookup-target Double checks | B | Legacy prefilters, not exact authority; strict raw capture and domain exact bounds/progression/terminal checks independently gate position truth; missing exact evidence blocks coverage |
| `PaperOrderStatusTrackerRepository`: Double properties and legacy lifecycle persistence | A | Existing projection retained, with exact evidence persisted separately and atomically |
| Same tracker: audit identity equality, terminal comparison, cumulative decrease, snapshot coherence | B | Read-only legacy checks do not confer exact position assurance; the position domain revalidates the sidecars independently |
| `PaperOrderHistoryRepository`: canonical terminal/progression checks and projection consistency | B | Preserved legacy contract, not the final exact-integrity authority |
| Canonical fingerprints and bootstrap canonical-record/prefix equality | A/B | Fingerprints identify the legacy payload; bootstrap also checks exact at-cut evidence, exact duplicates and source-exact derivation; no exact equality is inferred from a fingerprint alone |
| SHA-256 hexadecimal `.format` | A | Digest-byte formatting, not quantity rounding or comparison |

**C remaining = 0 for authoritative position quantities.** No authoritative
quantity arithmetic uses Double/Float, epsilon, tolerance or format-before-compare.
Some legacy checks can still conservatively reject an input; they cannot promote
an inexact input to an authoritative expected quantity. Numeric JSON or missing
exact sidecars likewise do not become exact by conversion.

| Required edge | Evidence / result |
| --- | --- |
| Distinct exact terminal fills | Permanent downstream regression rejects confirmed coverage, expected quantity, MATCH and MISMATCH; frozen report replay agrees |
| Identical exact terminal rereads | CANCELED/EXPIRED/FILLED controls stay reliable; baseline 8 + historical legacy 2 + exact post fill A = `8.100000000000000001`; replay preserved |
| Exact decrease B → A hidden by Double | Existing decimal progression check detects DECREASING_FILL; new regression verifies downstream fail-closed behavior |
| Exact overfill, requested A / filled B | Existing decimal bound detects EXCESS_FILL; new regression verifies downstream fail-closed behavior |
| Missing all/first/last exact sidecars | Three controls retain legacy provenance and prevent confirmed coverage and authoritative comparison |
| Cursor subtraction | Direct B − A is `0.000000000000000001`; real V1 cursor path also preserves a 1e-18 delta for projection-compatible inputs |
| V1 precision outside its existing contract | A/B beyond its strict projection gate still fail closed, with unchanged historical diagnostic set; no new V1 precision acceptance is claimed |
| Broker B versus expected A | Exact MISMATCH difference `0.000000000000000001`, cause UNKNOWN, survives stored report replay |
| Manifest exact quantity A versus B | Exact at-cut text and SHA-256 digest differ; canonical round-trip preserves the source precision |
| Historical bootstrap +2, baseline 8, no post fill | Existing bootstrap suite remains part of full verification; expected remains 8, never 10 |

#### Average-price limitation — no scope expansion

Raw `filled_avg_price` text is present transiently in the allow-listed response
and prepared evidence, but v9 durable decimal sidecars and domain decimal inputs
cover **qty and filled_qty only**. Average price remains a legacy Double field;
its finite/positive/presence and terminal consistency checks do not certify exact
price precision. No position arithmetic or difference uses that price. Exact
cross-observation price certification would require extending the durable evidence
contract beyond this quantity fix; it was not silently added, backfilled or
claimed. No schema/network/execution change was made for it.

#### C.1 final verification

`C1_FINAL_TEST_START_UTC=2026-09-29T22:45:01.9448137Z`.
All final production/test/schema hashes were unchanged between the final targeted
run and both full suites. XML suite timestamps and modification times were checked
against this start; no result below comes from the earlier failed/full attempt.

| Check | Observed result |
| --- | --- |
| Permanent regression before fix | 15 tests; 4 failures demonstrating the terminal/fingerprint defects; 0 errors/skipped |
| Final targeted regression | 16 tests; 0 failures/errors/skipped; Gradle exit 0 |
| `REGRESSION_DISTINCT_EXACT_TERMINAL_FILL` | PASS, permanent suite, not ad-hoc evidence alone |
| Fresh full Debug `--rerun-tasks` | 2,086 tests; 0 failures/errors/skipped; 110 XML suites; 0 stale; exit 0; 26 tasks executed |
| Fresh full Release after Debug `--rerun-tasks` | 2,086 tests; 0 failures/errors/skipped; 110 XML suites; 0 stale; exit 0; 27 tasks executed |
| Original standalone audit probe rerun | Exit 0; INCONSISTENT / TERMINAL_CHANGED / post UNKNOWN / expected null / ANCHOR_INVALID |
| Exact-mode / bootstrap compatibility | PASS; full prior suite plus explicit V1 diagnostic preservation and bootstrap regressions |
| Authoritative quantity precision audit | C remaining=0; A/B occurrences classified above |
| Room/schema | Room 9; same `Migration8To9`; no Migration9To10; schema 9 and migration file hashes unchanged from C.1 start |
| Migration instrumentation | Compiled again; NOT EXECUTED on a device |
| Safety | 11 allowed / 0 suspicious / 0 forbidden |
| Lint Debug/Release | KNOWN_PREEXISTING_LINT_FAILURE only: Symbols.kt:40 NewApi; 0 new findings; 0 findings in phase paths |
| Diff check / staging | PASS for tracked and new phase files; staging empty |
| Network contract / execution | NETWORK_CONTRACT_CHANGED=NO; submit boundary diff=0 |
| Safety flags | `canExecuteOrders=false`; generated Debug/Release manual submit=false; REAL locked=true, LIVE=false, Auto Paper=false |
| Runtime | GET=0; POST=0; IEX=NO; emulator/install/Refresh/real anchor/installed-DB changes=0 |

Final verdict: `PASS_2Y5C1_EXACT_DECIMAL_INTEGRITY_FIXED`.
The original publication blocker is corrected; this verdict does **not** publish
2.y.5-C, validate an installed v8-to-v9 migration or claim R2 success.

Both lint analyses executed against the corrected sources. Each variant reports
the same 1 error, 14 warnings and 1 informational finding as before C.1, including
the unchanged user-showcase warning. Gradle reused the byte-identical final lint
report output (`lintReport* UP-TO-DATE`) after rerunning analysis; no fresh report
mtime is claimed. The sole error remains the explicitly permitted
`Symbols.kt:40 NewApi`, with Symbols.kt diff=0. The combined command therefore
exited 1 for lint, not for instrumentation compilation. No suppressions, lint
baselines, schema changes or test-gate relaxations were introduced by C.1.

Final commands, using Android tooling and caches on C:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ExactDecimalIntegrityRegressionTest*' --offline --console=plain --max-workers=2
.\gradlew.bat :app:testDebugUnitTest --tests '*ExactDecimalIntegrityRegressionTest*' --tests '*PositionDomainIsolationTest*' --offline --console=plain --max-workers=2
.\gradlew.bat :app:testDebugUnitTest --offline --rerun-tasks --console=plain --max-workers=2
.\gradlew.bat :app:testReleaseUnitTest --offline --rerun-tasks --console=plain --max-workers=2
.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:lintDebug :app:lintRelease --offline --continue --console=plain --max-workers=2
& .\scripts\safety-scan.ps1
```

Evidence is retained in `C:\Android\audits\phase-2y5c1-20260929\`:
`regression-red.xml`, `regression-green.xml` (initial 15-case green),
`regression-final-green.xml` (16 cases), and complete fresh `debug-unit-results\`
and `release-unit-results\` directories. `lint-final-debug.xml` and
`lint-final-release.xml` retain the final unchanged findings; comparison uses the
saved 2.y.5-C reports, not a newly added lint suppression/baseline.

Current complete phase scope is **35 paths**: 24 tracked modifications + 11 new
phase files. The earlier 34-path manifest remains the original 2.y.5-C scope;
C.1 adds only `ExactDecimalIntegrityRegressionTest.kt` to that list. Only the
deriver, the new test file and this document changed in C.1. The three pre-existing
untracked showcase files remain outside the scope. The worktree is intentionally
not clean, staging is empty, and there has been no commit/push. The corrected
worktree is not authorized for publication in C.1; a separate final publication
audit must precede any future staging or publication. Runtime remains deferred.

## Phase 2.y.5-C.2 — independent final re-audit / publication payload

Re-audited on 2026-10-01 (America/Buenos_Aires). The interrupted 2026-09-29
attempt did not complete Debug and is NOT counted as validation. On resumption,
all 35 phase files and the three protected showcase files matched the previously
recorded SHA-256 values. A new fetch again confirmed branch `main`, empty staging
and HEAD = origin/main = `a46b5ea2723f26609e8f6313e390d96c4709063f`.

`AUDIT_2Y5C2_START_UTC=2026-10-02T01:25:10.5297998Z`.
The UTC date is October 2; the local audit date is October 1. This audit reads the
production diff and its consumers, not just the earlier C.1 verdict. No production,
test or schema change was needed in C.2; only this audit trail is updated.

### Scope independently enumerated

**35 phase paths: 24 modified + 11 new. Unrelated tracked changes (O) = 0.**
There are 25 production Kotlin files, seven JVM test files, one instrumentation
test file, one exported schema and this document. The following classification
applies to the exact publication paths, not an indiscriminate working-directory add.

Roots: `main/` = `android/app/src/main/kotlin/com/vela/android/lab/`;
`test/` = `android/app/src/test/kotlin/com/vela/android/lab/`;
`androidTest/` = `android/app/src/androidTest/kotlin/com/vela/android/lab/`.
Other paths are repository-relative. Categories follow the C.2 request:
A domain, B manifest/cut, C expectation, D reconciliation, E anchors,
F Room/schema/migration, G report/replay, H decimal plumbing, I lifecycle,
J UI/integration, K tests, L migration tests, M documentation, N C.1 fix.

| Path | Git change | Audit category |
| --- | --- | --- |
| main/VelaLabApplication.kt | Modified | J/H |
| main/data/paper/reconciliation/domain/DecimalQuantity.kt | Modified | A/H |
| main/data/paper/reconciliation/domain/LocalPositionExpectationEngine.kt | Modified | C |
| main/data/paper/reconciliation/domain/OrderRealizedFillDeriver.kt | Modified | N/A |
| main/data/paper/reconciliation/domain/PositionDomainModels.kt | Modified | A |
| main/data/paper/reconciliation/domain/PositionReconciliationEngine.kt | Modified | D |
| main/data/paper/reconciliation/evidence/PaperPositionAnchorRepository.kt | Modified | E/B |
| main/data/paper/reconciliation/evidence/PaperPositionEvidenceCaptureCoordinator.kt | Modified | H/J |
| main/data/paper/reconciliation/evidence/PaperPositionEvidenceHttpTransport.kt | Modified | H |
| main/data/paper/reconciliation/evidence/PaperPositionEvidenceModels.kt | Modified | G |
| main/data/paper/reconciliation/evidence/PaperPositionReconciliationReportRepository.kt | Modified | G |
| main/data/paper/reconciliation/evidence/PositionEvidenceCodec.kt | Modified | G/B |
| main/data/paper/reconciliation/integration/PositionReconciliationStore.kt | Modified | J/E |
| main/data/paper/status/AlpacaPaperOrderStatusReadOnlyClient.kt | Modified | H/I |
| main/data/paper/status/PaperOrderStatusModels.kt | Modified | I/H |
| main/data/paper/status/PaperOrderStatusTrackerRepository.kt | Modified | I/H |
| main/db/room/VelaDatabase.kt | Modified | F |
| main/db/room/entities/PaperPositionEvidenceEntities.kt | Modified | F |
| main/ui/positions/PositionReconciliationScreen.kt | Modified | J |
| main/ui/positions/PositionReconciliationUiState.kt | Modified | J |
| main/ui/positions/PositionReconciliationViewModel.kt | Modified | J |
| test/data/paper/reconciliation/domain/PositionDomainIsolationTest.kt | Modified | K |
| test/data/paper/reconciliation/integration/PositionReconciliationIntegrationTest.kt | Modified | K |
| test/ui/positions/PositionReconciliationViewModelTest.kt | Modified | K |
| main/data/paper/reconciliation/domain/BootstrapCoverage.kt | New | A/B |
| main/data/paper/reconciliation/evidence/BootstrapEvidenceCodec.kt | New | B/G |
| main/data/paper/reconciliation/evidence/FutureLifecycleDecimalEvidence.kt | New | H |
| main/db/room/migrations/Migration8To9.kt | New | F |
| test/data/paper/reconciliation/evidence/BootstrapMigrationSqlTest.kt | New | L |
| test/data/paper/reconciliation/evidence/FutureLifecycleDecimalEvidenceTest.kt | New | K |
| test/data/paper/reconciliation/integration/ExactDecimalIntegrityRegressionTest.kt | New | K/N |
| test/data/paper/reconciliation/integration/LegacyBootstrapCoverageTest.kt | New | K |
| androidTest/db/room/LegacyBootstrapMigration8To9Test.kt | New | L |
| android/app/schemas/com.vela.android.lab.db.room.VelaDatabase/9.json | New | F |
| docs/phase-2y5c-legacy-bootstrap-coverage.md | New | M |

`main/ui/showcase/VelaShowcaseComponents.kt`, `VelaShowcaseFixtures.kt` and
`VelaShowcaseScreen.kt` are **USER_PREEXISTING_UNTRACKED**, excluded from these
35 paths. Their hashes must continue to match the original table above. They are
not edited, formatted, moved, deleted, staged or added to ignore rules.

### Semantic re-audit (request sections 3–43)

`TEST_DRIVEN_PRODUCTION_SEMANTIC_WEAKENING=NO`.
The changes to existing tests describe the explicit new bootstrap policy and its
new observational fields/schema. They do not remove the strict exact-cursor gate
or bypass inconsistent post-cut evidence. C.1's exact comparisons remain confined
to the new source-exact bootstrap path; default V1 diagnostics are preserved.

| Property | Re-audited result / evidence |
| --- | --- |
| Explicit modes | PASS: EXACT_CURSORS_V1 and LEGACY_BOOTSTRAP_V1 are persisted/versioned. A failed exact preparation can offer an explicitly labelled bootstrap proposal, but never silently converts an existing anchor or calculation. Human confirmation is still mandatory. |
| Fundamental arithmetic | PASS: historical +2 / baseline 8 / post 0 gives 8, never 10; post BUY 1 gives 9; post SELL 2 gives 6. `BootstrapCoveragePolicy.evaluate` sums baseline and postDelta only. |
| Pre/post assurance | PASS: pre-history remains LEGACY_UNKNOWN and historical delta is informational; authoritative expected requires confirmed exact post coverage. No legacy-to-COMPLETE relabelling. |
| Per-symbol scope | PASS: legacy SPY alone does not block safe QQQ. Unknown symbols, duplicate identity and unscoped source/mapping ambiguity still block. |
| Durable cut | PASS: snapshot ID/sequence, opaque account, symbol, history digest, submit/order/lifecycle high-water marks and frozen order inventory; timestamps alone never classify an order as post-cut. |
| Manifest | PASS: explicit V1, typed fixed-order encoding, identity-sorted inventory, UTF-8 SHA-256, canonical re-encoding and digest verification. Immutable after acceptance. |
| Pre-cut legacy | PASS: original histories remain unchanged; no invented raw decimals, account ownership, cursors or timestamps. |
| Crossing cut | PASS: open, partial, submitted, in-flight, unresolved or possibly sent relevant orders cannot be absorbed. Synthetic LOCAL_SUBMIT_AUDIT zero is not proof of a broker zero fill. |
| Late pre-cut evidence | PASS: exact duplicate checks supplement the legacy prefix/fingerprint; a repeat contributes nothing, material change or new precision fails closed. Later observation ID alone cannot create post-cut delta. |
| Future post-cut evidence | PASS: causal submit start beyond high-water, matching identity/symbol/side/account, broker lifecycle and exact sidecars with valid progression are mandatory. Missing evidence prevents confirmed coverage. |
| C.1 root cause | PASS: legacy storage projection was lossy, not raw capture. First-terminal exact DecimalQuantity equality and exact-aware repeat classification now independently govern integrity. |
| C.1 downstream chain | PASS: A=`0.100000000000000001`, B=`0.100000000000000002` differ exactly despite the same Double projection. Distinct terminal fills yield inconsistent evidence, post UNKNOWN, expected null, no MATCH/MISMATCH; persisted replay agrees. |
| Identical terminal control | PASS for CANCELED/EXPIRED/FILLED: A/A remains valid and contributes exactly once. |
| Exact decrease / bound | PASS: B to A is decreasing; requested A / filled B is overfill, even where legacy projections coincide. |
| Cursor precision | PASS: B minus A is `0.000000000000000001`. Real V1 cursor arithmetic also retains 1e-18 for projection-compatible inputs. V1 still rejects A/B beyond its existing projection contract; this audit does not claim broader V1 acceptance. |
| Broker comparison | PASS: broker B / expected A is MISMATCH with difference 1e-18 and cause UNKNOWN; survives replay. |
| Raw capture / association | PASS: original string qty/filled_qty captured before display conversion; linked to attempt/order, actual inserted observation ID, field, account, fingerprint and provenance. No symbol/time matching or exact-from-Double reconstruction. |
| Persistence failure | PASS: actual Room adapter uses withTransaction; writer envelopes lifecycle/projection/sidecar together. Writer rollback and absent-sidecar controls pass; absent evidence cannot acquire exact provenance. |
| Average price | Limitation unchanged and documented: transient raw average price is not durable exact price evidence. Legacy price plausibility/terminal checks do not provide exact price certification; no position arithmetic uses price. No schema expansion for price. |
| Reports | PASS: V1 load returns stored results; V1 replay semantics retained. Bootstrap uses engine POSITION_ENGINE_2Y5_BOOTSTRAP_V2, policy POSITION_POLICY_LEGACY_BOOTSTRAP_V1, input codec V2 and coverage metadata V1. Unsupported replay versions explicitly fail. |
| Digest compatibility | PASS: prior encode body and new encodeV1 body compare identically after line-ending normalization. Historical checkpoint caller still uses V1; no redefinition under an old identifier. |
| UI / confirmation | PASS: historical delta, baseline, post delta, expected and difference are separate. Dialog discloses symbol, opaque account, snapshot, exact baseline and mode; it states that prior activity is not certified, no order is sent and broker positions are not changed. |
| Final confirmation | PASS: current snapshot/freshness/configuration/account, checkpoint/cut/manifest, relevant unresolved histories and active-anchor conflict revalidated within the insertion transaction. Prepared proposal alone has no authority. |
| No automatic actions | PASS: no auto-anchor, Refresh-triggered anchor, anchor rewrite, auto-invalidation or corrective buy/sell/close. MISMATCH cause remains UNKNOWN. |
| Network contract | NETWORK_CONTRACT_CHANGED=NO. Full changed-source review finds local/raw evidence plumbing only; status GET call shape unchanged, account/positions HTTP transport body identical. No new endpoint/GET, POST, retry, redirect or background polling. |
| Execution boundary | Submit/preflight/manual dashboard/status endpoint/status HTTP client/build settings diff=0. Token, arm, phrase, freshness, cancel/replace/close behavior unchanged. canExecuteOrders=false; generated Debug/Release submit flag=false; REAL locked=true, LIVE=false, Auto Paper=false. These are source/build assertions, not a new installed-runtime inspection. |

### Repeated precision occurrence audit

The full reconciliation domain/evidence, lifecycle/status and canonical history
boundaries were searched again for binary numeric types/conversions, arithmetic,
comparisons, formatting and tolerance/rounding. The C.1 classification table
above remains valid after reviewing the complete production diff and consumers.
**Authoritative position-quantity Double class C remaining = 0.**

Remaining A uses: legacy model/storage fields; the canonical history's historical
FILLED shortcut (not consumed by the position engines); explicit legacy quantity
rendering; legacy fields in V1 codecs; parser/display conversions; non-authoritative
average/limit prices; SHA-256 byte formatting. Remaining B uses: legacy projection
compatibility and source-to-observation binding, requested-identity prefilters,
legacy terminal/progression/coherence checks and fingerprint/prefix comparison.
These cannot certify source-exact equality or expected position without the
separate provenance, decimal bounds/progression/terminal and coverage checks.
`BigDecimal(text)` and DecimalQuantity comparisons/arithmetic are exact, not
binary-floating-point uses. No Float, BigDecimal(Double), epsilon, tolerance,
round-before-compare, allowLegacy, skipIncomplete or ignoreHistory bypass was found
in the reconciliation paths. The raw search inventory is preserved externally.

### Persistence and fresh validation

Room remains **9**, with registered additive **Migration8To9** and schema
**9.json**. No Migration9To10. The production SQL contains only four ALTER ADD
COLUMN statements. The host test compares every pre-existing column in populated
v8 SQLite before/after, including snapshots/positions, reports/rows, anchors,
cursors/events, legacy A/B histories and genuine fixture decimal evidence;
foreign-key and integrity checks pass. Empty v8 also receives no invented data.
Existing v8 anchors default to EXACT_CURSORS_V1 with null bootstrap metadata;
there is no fake bootstrap or decimal backfill. Migration/schema hashes still
match the C.1 values.

| Check | Fresh C.2 result |
| --- | --- |
| Debug --rerun-tasks | Exit 0; 2,086 tests; 0 failures/errors/skipped; 110 suites; 0 stale XML; 26 tasks executed |
| Release --rerun-tasks, after Debug | Exit 0; 2,086 tests; 0 failures/errors/skipped; 110 suites; 0 stale XML; 27 tasks executed |
| Separate exact precision suite | Exit 0; 16 tests; 0 failures/errors/skipped; 1 fresh suite |
| Separate host migration suite | Exit 0; 3 tests; 0 failures/errors/skipped; 1 fresh suite |
| Safety | 11 allowed / 0 suspicious / 0 forbidden |
| Tracked and new-file diff check | PASS |
| Instrumentation migration tests | COMPILED_NOT_EXECUTED: compilation reran successfully in the combined command; standalone confirmation exit 0 |
| Lint Debug / Release --rerun-tasks | Known preexisting Symbols.kt:40 NewApi only; each has 1 error, 14 warnings, 1 informational finding; 0 new findings and 0 findings in phase files |

The original named terminal regression is
`distinct exact terminal fills that collapse to same Double are contradictory end to end`.
The fresh full suites also include all 49 bootstrap cases and eight future-evidence
cases. Gradle regenerated results without manual deletion of any source or test
output directory. Full Debug/Release XML were archived before the targeted runs
replaced the active Debug result directory. Suite timestamps and filesystem mtimes
were checked against the respective run start; old C.1 XML are not reused.

Both lint reports are fresh (Debug UTC 01:33:56, Release UTC 01:34:35 on
2026-10-02) and every issue's complete XML matches the saved C.1 counterpart.
Symbols.kt and lint settings are unchanged, with no new suppression. Lint is
**not globally green**: the combined command exits 1 due only to lintDebug and
lintRelease's known NewApi failure; all 64 tasks executed. Instrumentation Kotlin
compilation completed in that command. The optional standalone confirmation first
hit an external PowerShell harness argument-splatting error before running a task;
after correcting the harness (not repository sources), it returned exit 0 with
the already compiled outputs up to date. Both invocation logs are retained.

Audit artifacts: `C:\Android\audits\phase-2y5c2-20261001\` contains the baseline
path/hash manifest, complete full and targeted XML, command logs, result JSON,
precision search inventory and lint comparison evidence. The earlier incomplete
attempt remains separately in `phase-2y5c2-20260929`; it is not a passing run.

### Publication boundary

Final pre-stage verdict: **READY_2Y5C2_TO_PUBLISH**. All semantic audits pass;
authoritative Double class C=0; fresh Debug/Release, precision and host migration
suites pass; instrumentation compiled; safety=11/0/0; diff check passes; lint has
only the accepted preexisting findings; scope is exactly the 35 audited paths;
network and submit behavior remain unchanged. The 34 production/test/schema
paths and three showcase files retain their pre-test hashes. No staging preceded
this verdict. The 35 explicit paths may now be staged for
one commit, `feat: add legacy bootstrap position coverage`. Origin must still
equal the original baseline immediately before a normal push. No pull, rebase,
merge, amend or force push is authorized by this phase.

This committed audit trail records the pre-stage evidence. The final commit hash,
remote comparison, publication verdict and final worktree status belong in the
external `publication-receipt.json` and the final user report, because a commit
cannot include its own hash or a future push outcome. No second documentation
commit or amendment is needed to record that receipt.

Throughout C.2: emulator interaction=0, APK install=0, Refresh=0, broker GET=0,
broker POST=0, real anchor creation=0, IEX=NO, installed DB modification=0.
The installed database was not inspected or migrated. **Runtime v9 NOT YET
VALIDATED; real bootstrap anchor NOT YET VALIDATED; no R2 claim.**
