# Phase 2.y.2 — deterministic local position domain

Baseline: `f5d12d80d1235986968e92f9c509e548f44c2ab1`.
Scope: new pure domain and JVM tests only. Room remains v7. No network,
database access, runtime wiring, UI, real baseline establishment or execution.

## Evidence and arithmetic

`DecimalQuantity` uses BigDecimal text construction, value-normalized equality,
canonical zero and exact signed arithmetic. No tolerance or rounding. Bounded
input length/scale rejects unreasonable inputs without modifying accepted values.

`QuantityEvidence` distinguishes EXACT_DECIMAL, LEGACY_DOUBLE_DERIVED, UNKNOWN
and INVALID. Canonical v7 quantities are Double: their explicit decimal rendering
can contribute to a known subtotal, but never recovers original decimal precision.
Legacy-dependent histories cannot produce an exact absolute expectation.

`OrderDecimalEvidence` is optional, detached, caller-supplied source evidence.
It binds exact requested/fill quantities to canonical order identity, observation
IDs and payload fingerprints; conflicting bindings fail closed. This phase does
not acquire such evidence or label legacy values exact. Tests supply original
text fixtures. A future adapter must establish provenance, not just relabel a
Double. The canonical source-of-truth contract is unchanged.

## Fills and expectations

The deriver traverses durable ingestion IDs, not timestamps. Accumulated fills
0.4 → 0.7 → 1 contribute 1, not 2.1. Repeated payloads do not add fills. Canceled
or expired orders retain demonstrated partial fills. Synthetic LOCAL_SUBMIT_AUDIT
zero is not broker zero. Missing final quantities do not certify a terminal total.
Contradictions, ambiguous identity and impossible quantities are not repaired.

Known VELA delta is NOT an absolute position. BUY adds realized quantity; SELL
subtracts it. Open partials contribute to the known delta but block an absolute
comparison. Subtotals expose deltaComplete and open/unknown exposure separately.
COMPLETE history is an explicit caller assertion: never pass a latest-N window.

Anchors are abstract per-symbol, account-scoped values. Expected quantity is
baseline plus current realized fills minus the cumulative quantities already
included by each order cursor. Older orders require identity-bound cursors at the
declared cut; newer orders must follow both ingestion boundaries. Clock ordering
does not establish causality. Unknown cuts, changed evidence, missing cursors,
account changes and inactive anchors prevent comparison. The domain can express
a certified partial-fill cursor; it does not certify or establish real anchors.
The quiescent/manual establishment policy remains future adapter work.

## Reconciliation

Abstract snapshots distinguish COMPLETE, PARTIAL, INVALID and FAILED. Only a
validated COMPLETE snapshot permits absent symbol = zero. Duplicate symbols,
invalid quantities or inconsistent signed side invalidate the snapshot. No old
snapshot is substituted or merged. Freshness is explicit input, with no built-in
TTL or global-clock access. Broker/local evidence alignment is a separate explicit
input, default UNKNOWN, and must be CONFIRMED before comparison. A fresh timestamp
does not prove alignment; this pure domain cannot certify it for its caller.

MATCH/MISMATCH require an active compatible anchor, reliable complete local
expectation, exact decimals and a fresh complete broker snapshot. Difference is
broker minus expected. UNANCHORED, UNKNOWN, STALE, INCONSISTENT_LOCAL_HISTORY,
ANCHOR_INVALID and BROKER_READ_FAILED have null difference. STALE may retain the
independently valid local expectation but cannot publish an authoritative delta.
BROKER_ONLY/LOCAL_ONLY are presence attributes, not error states.

MISMATCH reports UNEXPLAINED_POSITION_DIFFERENCE, cause UNKNOWN and a declarative
ANCHOR_SHOULD_INVALIDATE diagnostic. It never mutates the anchor or returns a
trading command. No corrective trading, callbacks or executor dependencies exist.
Cash/equity/prices/P&L are outside this quantity-only domain.

Global diagnostics survive even for an empty portfolio. Unknown symbol scope
blocks potentially affected comparisons; symbol-attributable inconsistency stays
isolated. Summary buckets are disjoint; unknownCount includes all noncomparable
states other than UNANCHORED.

## Verification

JVM tests cover decimals/provenance, accumulated fills, signed netting, cursor
boundaries, scoped integrity, abstract snapshots and fail-closed comparisons.
Source/reflection contracts restrict imports and report fields, prohibit runtime
wiring and preserve Room v7. The only excluded compiler field is Compose's static
integer `$stable` marker; actual report fields remain explicitly allow-listed.

Final validation on 2026-09-17:

| Check | Result |
| --- | --- |
| Debug JVM tests, rerun | 1834 passed; 0 failures, errors or skipped |
| Release JVM tests, rerun | 1834 passed; 0 failures, errors or skipped |
| New domain cases per variant | 96: decimal 25, fills 24, local/anchors 20, reconciliation 23, isolation 4 |
| Safety scan | 11 allowed / 0 suspicious / 0 forbidden |
| Whitespace | `git diff --check` plus explicit untracked-file checks passed |
| Debug/release lint | Only error: unchanged `Symbols.kt:40` NewApi; 13 warnings and 1 informational item each; no new domain findings |
| Frozen tracked sources | No diff from baseline, including submit, guards, UI, HTTP, market data, Room and Gradle configuration |
| Generated manual-submit flags | false in debug and release |

Command: `gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest
:app:lintDebug :app:lintRelease --offline --rerun-tasks --continue
--max-workers=2 --console=plain`. All 69 tasks executed. Overall exit code 1 is
solely the two known lint task failures, not test failures; no suppression added.

Five new production files, five test suites plus one fixture file, and this
document. Existing tracked files unchanged. No emulator, install, real anchor,
runtime GET/POST, IEX, migration, commit/push or Phase 2.y.3. Staging remains empty.
