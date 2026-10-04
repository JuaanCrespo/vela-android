# Phase 3.a.1-C — Execution Price Trust Hardening

Status: publication audit applied (§24). The independent audit found defects, which were fixed before publication. The audit trail, the fixes, and the residual limitations are recorded below. The publication commit hash is recorded in the audit report, not in this file.
Baseline before this phase: `8328049990099a8e38e16c93e9cbd37f4239bd16` (`docs: define market trust boundaries`, also `origin/main`).
Audit start: `2026-10-04T23:36:26Z`.
Scope: Paper execution price authority only. No Market Dataset implementation, no Room schema change, no network change, no submit transport change, no runtime.
Historical documents: `docs/phase-3a1-market-dataset-architecture.md` and `docs/phase-3a1b-market-trust-boundaries.md` are unchanged. This document records the corrections to them (§1.1).

Evidence legend: VERIFIED = read in code, reproduced by a test, or proven by a static search in this phase. NOT VERIFIED = stated as open, with the reason.

---

## 1. Root cause and the baseline path

### 1.1 Corrections to earlier reasoning (historical documents not edited)

- VERIFIED in the baseline: the audited UI arm action requires `preparedPreviewIsSynchronized(preflight, manual)`. `OfflineDashboardScreen.kt` lines 239–245 call `paperManualSubmitViewModel?.armSession()` only inside `manualPaperArm`, which first checks `canArm`. `preparedPreviewIsSynchronized` (lines 1864–1880) requires `result.priceSource == LIVE_QUOTE_MID` and `result.priceFreshness == FRESH`. The VM's `armSession` (`PaperManualSubmitViewModel.kt` 138–145) checks no price. So a Room-only preview could not be armed through the audited UI.
- VERIFIED: the domain layer did accept Room-to-Room. The baseline final gate applies the price check to every order type (`PaperManualSubmitGate.kt` line 71), and the baseline `isSourceCompatible` treats `ROOM_BAR_CLOSE` and `ROOM_BAR_CLOSE` as the same source class. So the domain defect stands as HIGH at the domain boundary. The UI reachability claim in 3.a.1-B §7 (F1) was broader than the UI evidence.

### 1.2 Old path (baseline `8328049`), reconstructed from git

Each step below is from `git show 8328049:<path>`:

1. Persisted bar: `market_bars_1m` (Room). A demo or IEX bar is written by `OfflineMarketPipelineCoordinator`.
2. Snapshot provider, tier 2: `MarketPriceSnapshotProvider.kt` lines 71–84 read `recentBars(symbol, 1).lastOrNull()` and return `close` as the price, with `source = ROOM_BAR_CLOSE`. Age is measured from the bucket start (`bucketStart`). No rejection of a stale bar.
3. `hasPrice` (`MarketPriceSnapshot.kt` line 40): `price != null && freshness != MISSING`, so a `STALE` snapshot still "has a price".
4. Preflight precedence (`PaperOrderPreflightEngine.kt` lines 92–96): limit price, then `snapshotPrice`, then `latestLocalClose`. Notional and the BUY buying-power block use `priceUsed`.
5. Preflight view model (`PaperOrderPreflightViewModel.kt` line 607): reads `recentBars(symbol, 1)` directly and passes `latestLocalClose = latestBar?.close` (line 626). This is a third path, with no freshness check.
6. Preview fallback labels (`PaperOrderPreflightEngine.kt` lines 202–204): a Room-priced result is reported as `ROOM_BAR_CLOSE` / `STALE`.
7. Final gate: `PaperFinalPriceStabilityPolicy.kt` computes age from the NEWEST timestamp (`rawAgeMillis`, line 153 `maxOrNull`). `isSourceCompatible` treats `ROOM_BAR_CLOSE` as class `BAR` with rank 1 (lines 172, 180). So a `ROOM_BAR_CLOSE` preview is compatible with a `ROOM_BAR_CLOSE` final price, and drift compares the bar with itself (the self-comparison loophole).
8. Final gate result → executor → `PaperManualOrderSubmitClient` (the only POST).

Where legacy Room influenced authorization (baseline, VERIFIED): the notional and BUY buying-power block (steps 4–5), the preview metadata (step 6), and the final gate's acceptance of a Room price (step 7), which controls whether the POST is sent. The market-order body contains no price, so the Room price cannot change the broker price or the quantity. It decides whether the POST is sent and what is recorded.

Tick buffer baseline (VERIFIED): `MarketTickBuffer.kt` contains no `source` or provenance field. The quote's origin is lost before the snapshot is built.

Stock client baseline (VERIFIED): `AlpacaStockMarketDataClient.kt` lines 233 and 263 hard-code `source = "alpaca-iex-stream"`, regardless of the endpoint. Its constructor accepts the TEST endpoint through the endpoint guard, so a FAKEPACA connection would have been labelled IEX (latent; not reachable through the production wiring, which passes no endpoint).

---

## 2. Real diff and classification (publication audit)

Enumeration: `git status --porcelain=v1 --untracked-files=all`, `git diff --name-status`, `git diff --stat`. Staging was empty. HEAD = `origin/main` = baseline.

| Class | Paths | Note |
|---|---|---|
| A execution-reference domain | `data/market/price/ExecutionReferencePrice.kt` (new), `data/market/price/MarketPriceSnapshotProvider.kt`, `data/market/price/MarketPriceSnapshot.kt` (enums only), `data/market/price/MarketPriceFreshnessPolicy.kt` (documentation only) | |
| B market provenance | `data/market/tick/MarketDataProvenance.kt` (new), `data/market/tick/MarketTick.kt`, `data/market/tick/MarketTickBuffer.kt` | Buffer also gets the snapshot-publication fix (§24) |
| C stock stream / source identity | `data/market/source/alpaca/AlpacaStockMarketDataClient.kt` | Provenance and label only; no connectivity change |
| D preflight / readiness | `data/paper/preflight/PaperOrderPreflightEngine.kt`, `data/paper/preflight/PaperOrderPreflightResult.kt`, `ui/dashboard/PaperOrderPreflightViewModel.kt` | |
| E risk / notional | (covered by D: notional and buying power are computed in the engine) | `PaperPortfolioRiskViewModel` is NOT modified (§11) |
| F authorization / token | `ui/dashboard/PaperManualSubmitViewModel.kt` (field rename and gate call only) | `PaperManualSubmitConfirmation.kt` (token) NOT modified |
| G final submit gate | `data/paper/submit/PaperFinalPriceStabilityPolicy.kt`, `data/paper/submit/PaperManualSubmitGate.kt`, `data/paper/submit/PaperManualSubmitExecutor.kt`, `data/paper/submit/PaperOrderSubmitModels.kt` (new error code) | POST client, body, endpoint: NOT modified |
| H wiring | `MainActivity.kt` (one constructor argument removed), `VelaLabApplication.kt` (provider wiring; freshness property removed) | |
| I tests | 14 modified test files, 2 new test files (`ExecutionReferencePriceEvaluatorTest.kt`, `ExecutionTrustRegressionTest.kt`) | §18 |
| J docs | `docs/phase-3a1c-execution-price-hardening.md` (new) | 3.a.1-B untouched |
| K clearAll-related | none | `OfflineDashboardViewModel.clearDemoState`, `MarketDataRepository.clearAll`, `MarketBarDao` NOT modified |
| L unrelated | 0 | Required: 0 |

Untracked and unrelated: `android/app/src/main/kotlin/com/vela/android/lab/ui/showcase/` (3 files, user-owned, untouched).

---

## 3. Execution price inputs (classified)

Class key: A = trusted live market data, B = legacy Room bar, C = synthetic, demo, or test, D = other.

| Input | Baseline | Now |
|---|---|---|
| Two-sided live quote, IEX endpoint | A (not proven) | A, proven by provenance derived from the canonical IEX endpoint |
| Quote, TEST endpoint | C labelled as A (latent) | C, `ALPACA_TEST_SYNTHETIC`, rejected |
| Quote, any other endpoint | not possible through the guard | `UNKNOWN`, rejected |
| One-sided quote | A (used the valid side) | rejected as `INVALID_QUOTE` |
| Demo injection | none (demo writes Room only) | none in-memory; a `LOCAL_DEMO_SYNTHETIC` tick would be rejected |
| Undeclared provenance | not representable | `UNKNOWN` by default, rejected |
| Room bar close (tier 2) | B | removed from execution |
| Direct Room close (`latestLocalClose`) | B | removed |
| Limit price (LIMIT orders) | D (operator input) | D for notional, and a trusted reference is still required (§10) |
| Preview price (`notional / quantity`) | derived from A or B | derived from A only, and only a trusted preview can pass |

---

## 4. Trusted execution authority (new path)

Chain, VERIFIED by static trace and tests:

1. Market source: IEX real-time quote stream (`AlpacaStockMarketDataClient`).
2. Provenance assignment: `provenance = when (endpoint) { IEX_STREAM_URL → ALPACA_IEX_REAL_TIME; TEST_STREAM_URL → ALPACA_TEST_SYNTHETIC; else → UNKNOWN }` (`AlpacaStockMarketDataClient.kt` lines 75–78). Equality with canonical constants. No substring, label, symbol, or caller text is consulted. The stream label follows the same mapping (lines 81–85).
3. `MarketTick` (`provenance` field, default `UNKNOWN`), then `MarketTickBuffer.pushQuote`, which stores `lastProvenance` on the per-symbol stats. Publication is inside the lock (lines 64, 84).
4. `MarketPriceSnapshotProvider.executionReferenceFor(symbol)` (line 25): reads the snapshot of the tick buffer. It has no repository (see §12). It builds one `LiveQuoteObservation` from the same immutable `PerSymbolTickStats`, so price, provenance, and timestamps cannot be torn.
5. `ExecutionReferencePriceEvaluator.evaluate` (line 237) → `ExecutionReferencePrice.Trusted.derive` (line 67), the only creation path of `Trusted`. Its constructor is `private` (line 23).
6. Preflight: `PaperOrderPreflightEngine.preflight` (line 54, the only `preflight` function) requires a `Trusted` reference for the same symbol (lines 97–108). Otherwise it blocks with `NoTrustedExecutionPrice(reason)`.
7. Review: the VM refreshes the reference (`PaperManualSubmitViewModel.refreshSubmitReadiness`) and stores it. Token issuance evaluates the stored reference against the current clock (§10).
8. Final gate: `PaperFinalPriceStabilityPolicy.evaluate` recomputes freshness and drift against the current reference (`PaperManualSubmitGate.kt` line 71).
9. Submit decision: `PaperManualSubmitExecutor` re-fetches the reference after the durable start audit (line 75) and runs the gate again. Only then does `submitOnce` send the one POST.

No hidden Room fallback exists on this path (VERIFIED by search: `recentBars`, `bySymbol`, and `recent` are not called from the provider, the engine, the policy, the gate, or the executor).

---

## 5. Type safety of `ExecutionReferencePrice`

- `Trusted` has a `private` constructor. The only creation path is `Trusted.derive` (internal companion function), which applies every rule in §6. Test code cannot construct `Trusted` with an arbitrary tuple either.
- Production construction sites of `Trusted(`: one (inside `derive`). Verified by search.
- `Trusted` is not a `data class`, so there is no `copy` that can change a field.
- `Rejected` is a `data class` with no authority. It carries no price.
- `init` re-checks provenance (IEX only), source (`LIVE_QUOTE_MID` only), price (finite and positive), and age (within the freshness limit). These checks protect the invariants if `derive` changes later.
- Serialization: `ExecutionReferencePrice`, `Trusted`, and `MarketTick` are in-memory only. No Bundle, SavedState, JSON, or Room path carries them (VERIFIED by search; `AlpacaStockMarketDataClient` uses `JSONObject` to parse incoming frames only).
- Reflection and deserialization are not supported boundaries and are not defended here.

---

## 6. Trust rules (evaluation order; first failure rejects)

Applied by `ExecutionReferencePrice.Trusted.derive`:

1. No observation → `NO_LIVE_QUOTE`.
2. Provenance is not `ALPACA_IEX_REAL_TIME` → `PROVENANCE_NOT_REAL_TIME`. This covers TEST, demo, and unknown values. The check is by equality with the one trusted value, so a future value is also rejected.
3. Timestamps invalid at `now` (`executionTimeRejection`, line 178):
   - either timestamp missing or not positive → `TIMESTAMP_MISSING`;
   - the event time is more than 2 000 ms after now → `FUTURE_TIMESTAMP`;
   - the receipt time is more than 2 000 ms after now → `FUTURE_TIMESTAMP`;
   - the event time is more than 2 000 ms after the receipt → `EVENT_AFTER_RECEIPT`.
4. Quote: bid AND ask must both be finite and positive, and not crossed (bid ≤ ask). Otherwise → `INVALID_QUOTE`. A one-sided quote is never a reference.
5. Conservative age (`conservativeAgeMillis`, line 207) above 10 000 ms → `STALE`.
6. Otherwise `Trusted`: price = (bid + ask) / 2 (`LIVE_QUOTE_MID`), with `ageMillis` = the conservative age.

Rules that do not exist:

- No `Math.max(0, age)` hides an invalid future timestamp. The clamp to zero is applied only after rule 3 has accepted the timestamps, so it absorbs an age inside the 2 000 ms tolerance and nothing beyond it.
- No symbol-based exception.
- No "latest trusted" fallback. The newest quote for the symbol decides. If it is not trusted, the answer is not trusted, even if an older trusted quote exists.

Additional checks outside `derive`:

- Engine (preflight): the reference symbol must equal the intent symbol (both normalized). Otherwise `SYMBOL_MISMATCH` (lines 97–108). A trusted QQQ quote never prices an SPY intent.
- Final policy: the reference symbol must equal the preview symbol (`PaperFinalPriceStabilityPolicy.kt`, `freshAndValid`).

---

## 7. Freshness formula and clock semantics

- Event time `E` (provider `t`, epoch millis). Receipt time `R` (device clock at parse). Now `N` (device clock).
- Time reference: `min(E, R)`, the EARLIEST trustworthy timestamp. Age = `N − min(E, R)` = `max(N − E, N − R)`. This is the conservative formula. The implementation computes `maxOf(N − E, N − R)`, and a test proves the older timestamp decides (§18: "the OLDER of event and receipt time decides freshness, within the skew window" and "receipt fresh but event 11 s old is STALE").
- Limit: 10 000 ms, the existing live-quote threshold (`MarketPriceFreshnessPolicy.DEFAULT_LIVE_QUOTE_FRESH_MILLIS`). `ExecutionReferencePriceEvaluator` refuses a larger limit, and `Trusted.init` re-checks it.
- Skew tolerance: 2 000 ms, the existing value from phase 2.v.3 (`DEFAULT_MAX_FUTURE_SKEW_MILLIS`). It is applied only to clock disagreement, inside rule 3 of §6.
- Future timestamps: a far-future event with a current receipt, and a far-future receipt with a current event, both fail closed (`FUTURE_TIMESTAMP`). A negative age beyond the tolerance is never accepted, so it cannot make a price "extra fresh" (VERIFIED by tests in `ExecutionReferencePriceEvaluatorTest` and `ExecutionTrustRegressionTest`).
- Event after receipt: allowed up to 2 000 ms (network latency makes the receipt later, which is normal, and the window only absorbs clock disagreement). Beyond 2 000 ms → `EVENT_AFTER_RECEIPT`.
- The final gate recomputes the age with its own clock at submit time (`PaperFinalPriceStabilityPolicy.evaluate`). A reference that was fresh at review is refused if it is stale at submit (VERIFIED by test).
- No system clock is modified. No Room bucket start is a freshness input.

Clock limitations (stated, not hidden):

- The device wall clock is compared with the provider's event time. If the device clock disagrees by more than 2 000 ms, the quote is rejected (`FUTURE_TIMESTAMP` or `EVENT_AFTER_RECEIPT`) or ages out (`STALE`). Fail closed. This is an availability cost, and it is accepted.
- Event time has millisecond precision in this model. Sub-millisecond precision from the provider is truncated. The model does not claim a provider time guarantee beyond that.

---

## 8. Fail-closed behavior

Every non-trusted case blocks with a deterministic code, never with a zero, NaN, Infinity, placeholder, or previous value:

- Preflight: `PreflightBlockReason.NoTrustedExecutionPrice(reason)`. Status `BLOCKED`. Notional and buying power are NOT computed (`estimatedNotionalUsd = null`). Result `priceSource = NONE`, `priceFreshness = MISSING`, `priceAgeMillis = null`.
- Final gate: `PaperFinalPriceGateResult.NO_TRUSTED_EXECUTION_PRICE` → `PaperOrderSubmitError.NO_TRUSTED_EXECUTION_PRICE`. Stale or drifted references: `PRICE_NOT_FRESH` or `PRICE_DRIFT_EXCEEDED`.
- Executor: if the final provider throws, the result is `Rejected(PROVIDER_FAILED)`. No default price.
- Reason codes: `NO_LIVE_QUOTE`, `PROVENANCE_NOT_REAL_TIME`, `TIMESTAMP_MISSING`, `INVALID_QUOTE`, `FUTURE_TIMESTAMP`, `EVENT_AFTER_RECEIPT`, `STALE`, `SYMBOL_MISMATCH`, `NOT_PROVIDED`, `PROVIDER_FAILED`.

STALE in preflight is a BLOCK. The pre-change "warning only" stale path (`PreflightWarning.StalePrice`) was removed, so no execution authorization path treats a stale price as executable. VERIFIED by search: no `StalePrice` reference remains in production.

Not done, VERIFIED by absence and by tests: no Room fallback, no demo fallback, no reuse of a stale trusted price, no zero, no guessed price, no bypass of the notional or buying-power check.

---

## 9. Final gate revalidation (`PaperFinalPriceStabilityPolicy`)

Required, in order:

- The final reference is `Trusted` (otherwise `NO_TRUSTED_EXECUTION_PRICE`).
- The preview's own `priceSource` is `LIVE_QUOTE_MID` (the only trusted source now). A preview priced from a Room close, a demo bar, a one-sided quote, or anything else is refused. This closes the self-comparison loophole.
- The reference symbol equals the preview symbol.
- Freshness: the conservative age at the gate's `now`, against `min(10 s, maxFinalPriceAgeMillis)`.
- Drift: `|final − previewPrice| / previewPrice ≤ 0.25 %`.
- Source compatibility: the same class (`QUOTE`), which is the only trusted case.

Drift semantics:

- Two trusted states are compared. When the final reference is the same fresh quote as the preview, drift is zero, and the trade is allowed only while the quote is fresh. This is the existing safe policy applied to one observation at review and at submit. No comparator is synthesized.
- A numeric preview relabelled as trusted cannot pass unless its value matches the CURRENT trusted quote within 0.25 %. The test `a numeric preview relabelled as a trusted source cannot pass against the current trusted quote` uses 727 against 520 and gets `PRICE_DRIFT_EXCEEDED`.

---

## 10. Authorization token, quantity, order body, notional, buying power, LIMIT

- Token (`PaperManualSubmitTokenStore`, `PaperManualSubmitConfirmation`): UNCHANGED. Verified by absence from the diff. TTL 30 s (`DEFAULT_TTL_MILLIS`), single use (`consume` clears the active token), one active token, one emission per armed session (`confirmationTokenIssuedForArmedSession`, reset only by `armSession`), and the exact phrase (`requiredText`) are unchanged.
- Token-bound price state: the token binds `priceSource` and `priceFreshness` strings, the symbol, side, quantity, preview id, and preview generation time. It does NOT bind the price value or a snapshot identity. The final gate revalidates the current trusted reference, so the token alone cannot authorize a stale, untrusted, or different-symbol price (§9).
- Token issuance uses the reference fetched at refresh time, and checks it against the current clock. It does not re-fetch. A trust change therefore becomes visible at latest when the executor re-fetches, and the token cannot be used without that re-fetch.
- Quantity: UNCHANGED. User input. No sizing from price.
- Market order body (`PaperManualOrderSubmitClient`): UNCHANGED (file not in the diff). Fields: symbol, side, type, qty, time_in_force, client_order_id, and `limit_price` only for LIMIT. `PaperManualOrderSubmitClientTest` asserts that no `price`, `limit_price`, or `stop_price` key is sent for a market order.
- Notional: computed only from a trusted reference, for MARKET orders. For LIMIT, the operator's limit is used (operator input, not market data), and a trusted reference is still required (see the LIMIT decision below).
- Buying power: compared only against the trusted notional. Without a trusted reference, the comparison is not performed, and readiness is BLOCKED (`NoTrustedExecutionPrice`). It is never skipped silently.
- Buying-power margin after preflight: the buying-power comparison is made at preflight. The submit gate revalidates price drift (≤ 0.25 %) but does not re-run the buying-power comparison. The preflight result must be ≤ 60 s old at submit (`maxPreflightAgeMillis`). This residual is documented in §19.

LIMIT decision (`LIMIT_TRUSTED_REFERENCE_REQUIREMENT`):

- Evidence: the baseline final gate evaluated the price for every order type (`git show 8328049:…/PaperManualSubmitGate.kt`, line 71, no type condition). So a LIMIT order could never be submitted without a fresh market price in the baseline either. The baseline preflight, however, reported LIMIT as ready without any market price.
- The change makes preflight consistent with the gate that already existed. It moves the refusal earlier. It does not add a new restriction to submission.
- The UI builds only MARKET intents (`PaperOrderPreflightViewModel` sets `OrderType.MARKET`), so LIMIT is not reachable from it.
- Pre-existing LIMIT drift semantics, unchanged: the preview price of a LIMIT order is the limit itself (`notional / quantity`), so the drift check compares the market price with the limit. This was true in the baseline and is not changed here. It is recorded in §19.
- Decision: `LIMIT_TRUSTED_REFERENCE_REQUIREMENT=INTENTIONAL_SAFE_POLICY`.

---

## 11. Review UI and the display-only Room consumer

- Review: the preflight and submit screens show `priceSource`, `priceFreshness`, and the block messages. A trusted preview shows `LIVE_QUOTE_MID`. A non-trusted preview shows `NONE` and `MISSING`, and the block message names the reason code. The feed name (IEX) is not shown. Limitation, §19.
- Arm guard: UNCHANGED. `preparedPreviewIsSynchronized` still requires `LIVE_QUOTE_MID` and `FRESH` before the arm action. So `LIVE_QUOTE_BID_ASK` never reaches arm (the one-sided case is now rejected upstream anyway).
- Display-only Room consumer, `PaperPortfolioRiskViewModel` (VERIFIED by trace):
  - It reads `recentBars(symbol, 1)` and builds `PerSymbolPaperExposure.latestLocalClose` (line 127 and 138), and a warning when that value is null (line 208).
  - Its state flows only into the dashboard: `MainActivity` (instance), `OfflineDashboardScreen` (lines 110, 188, 435–449, 2169), and `VelaDashboardSections` (line 68, 453 summary count).
  - Its refresh is the display refresh `riskRefresh` (line 223).
  - It is NOT referenced by `PaperOrderPreflightEngine`, `PaperManualSubmitGate`, `PaperFinalPriceStabilityPolicy`, `PaperManualSubmitExecutor`, `PaperManualSubmitViewModel`, or `PaperOrderPreflightViewModel` (VERIFIED by search).
  - The portfolio risk output cannot change readiness, risk authorization, the token, the submit gate, the quantity, or the order request. VERIFIED: no reference from those paths.

---

## 12. Legacy Room: removed from execution

- `MarketPriceSnapshotProvider` has no `MarketDataRepository` constructor parameter, and no field of that type (structural test: `provider has no dependency on the persisted market store`).
- `PaperOrderPreflightViewModel` no longer reads Room (the `recentBars` call and the `latestLocalClose` argument are removed).
- The `MarketPriceSnapshot` data class is removed. `MarketPriceSnapshot.kt` keeps only the `MarketPriceSource` and `PriceFreshness` enums. The file name is kept to avoid churn.
- `VelaLabApplication.marketPriceFreshnessPolicy` (no consumers) is removed.

Consumers of Room bars after the change, VERIFIED by search for `recentBars(`, `countAll(`, `bySymbol(`, `recent(`:

- `CandlesViewModel` (line 94): chart display of stored bars. Class A (display).
- `MarketHistoryViewModel` (lines 64, 80): history and counts. Class B (diagnostics).
- `OfflineDashboardViewModel` (line 100, `countAll`): pipeline count. Class B.
- `PaperPortfolioRiskViewModel` (lines 127, 138): display only (§11). Class A/E display.
- `FeatureEngine`: uses the in-memory aggregator, not Room. Class C (analysis). Unchanged.

`EXECUTION_AUTHORITATIVE_LEGACY_ROOM_CONSUMERS = 0`.

---

## 13. Synthetic, demo, and test producers (static audit)

`SYNTHETIC_EXECUTION_AUTHORITY_PATHS = 0`, VERIFIED by search:

- `MarketTick(` constructions in production: one (`AlpacaStockMarketDataClient.emitQuoteTick`). Its provenance comes from the endpoint, so a TEST connection yields `ALPACA_TEST_SYNTHETIC`.
- `pushQuote(` callers: one (`AlpacaStockStreamViewModel`, forwarding the stock client's quotes).
- `LiveQuoteObservation(` constructions in production: one (the provider).
- `Trusted` constructions: one (inside `derive`).
- `ALPACA_IEX_REAL_TIME` assignments in production: one (the endpoint mapping in the stock client). The rest are the enum, the rules, and tests.
- Demo controls (`OfflineDashboardViewModel.generateBtcUpdate`, `generateSpyUpdate`): write bars through the pipeline into Room. They create no tick and no quote.
- Test stream client (`AlpacaTestStreamMarketDataClient`): ignores quote frames (`AlpacaStreamMessage.Quote -> Unit`) and emits only bars.
- Mock and stub producers: `StubPaperMarketDataClient` (source `offline-stub`) produces bars, not ticks.
- No substring, endsWith, or label comparison is used as authority. Verified by search for `contains("iex")`, `endsWith("iex")`, `== "alpaca-iex-stream"`, and `contains("test")` in production.

---

## 14. Concurrency and atomicity (fixed before publication)

- Defect found (audit item 43): `MarketTickBuffer.pushQuote`, `recordBar`, and `recordParserError` built the snapshot inside `synchronized(lock)` but assigned `_snapshot.value` OUTSIDE it. Two concurrent updates could publish a snapshot built from an older state, so the newer update of the other symbol was silently lost. The buffer's own documentation claims thread safety.
- Fix: publication moved inside the lock in all three places (lines 64, 84, 97).
- Regression: `MarketTickBufferTest` → `concurrent quote pushes for two symbols keep the newest tick of each symbol` (two threads, 3 000 pushes each).
- Torn price/provenance: not possible. Each `PerSymbolTickStats` is one immutable object built from one tick, and the provider reads one snapshot value. VERIFIED by reading `MarketTickBuffer.buildSnapshot` and the data class.
- Production tick flow is single-threaded in practice (the collector runs on the main dispatcher), so the race is a latent defect of a documented thread-safe class. The fix is cheap and keeps the documented guarantee.

---

## 15. clearAll status

- `clearDemoState()` → `MarketDataRepository.clearAll()` (`DELETE FROM market_bars_1m`), and the feature, signal, and journal clears: UNCHANGED. Verified by absence from the diff.
- Execution effect: none. Room is not an execution input. `clearAll` does not touch the tick buffer.
- CLEARALL_HARDENING: NO (not in this phase).
- Approved deferred phase: `CLEARALL_HARDENING_DEFERRED_TO = PHASE 3.a.1-D — DIAGNOSTIC CLEAR ISOLATION`. 3.a.1-D will be a separate narrow safety and data-preservation phase. It is NOT implemented here.

---

## 16. Legacy row preservation

- No code in this phase deletes, updates, relabels, or migrates `market_bars_1m` rows. VERIFIED by absence of DAO, migration, and repository changes in the diff.
- Room remains version 9 (`VelaDatabase.kt`, `version = 9`), with no `Migration9To10`, no schema change, and no change to `db/room/**` (VERIFIED by diff).
- Logical quarantine: no execution path reads the table (§12). Training, dataset, and feature paths are unchanged, and none of them reads the table.
- Historical audit and preview rows keep `ROOM_BAR_CLOSE` labels. They are history and are not relabelled.

---

## 17. Network, submit transport, guards

- NETWORK_CONTRACT_CHANGED = NO. VERIFIED by diff: `AlpacaStockMarketDataClient` changes only provenance and labels (no `open`, `connect`, `subscribe`, `send`, retry, or redirect change). The test-stream client is not modified.
- Submit transport: POST endpoint `https://paper-api.alpaca.markets/v2/orders`, the submit HTTP client, the request body, retry, redirect, and one-shot semantics are UNCHANGED (files not in the diff).
- Gate decisions changed only in their price inputs (a non-trusted or legacy price now blocks). This is the intended change. The arm action, the confirmation phrase, the token lifetime and single use, and the REAL, LIVE, and Auto Paper locks are unchanged.
- `PaperTradingExecutionGuard.canExecuteOrders = false`: unchanged.
- Release `MANUAL_PAPER_SUBMIT_COMPILED = false`: unchanged (`app/build.gradle.kts` line 80). The debug value still comes from `local.properties` (unchanged mechanism).
- REAL locked, LIVE off, Auto Paper off: unchanged.
- Freeze test `PaperExecutionSafetyFreezeTest` and `scripts/safety-scan.ps1`: NOT modified.

---

## 18. Tests

Regression mapping for the required scenarios. The targeted suite is the set of classes listed; counts are in §20.

Targeted trust suite (classes, all run independently):

- `ExecutionReferencePriceEvaluatorTest` (18 tests)
- `MarketPriceSnapshotProviderTest` (14 tests)
- `PaperFinalPriceStabilityPolicyTest` (22 tests)
- `PaperManualSubmitGateTest` (30 tests)
- `PaperManualSubmitExecutorTest` (13 tests)
- `PaperPreflightWithSnapshotTest` (9 tests)
- `ExecutionTrustRegressionTest` (9 tests, in `data/paper/preflight`)
- `AlpacaStockQuoteEmissionTest` (7 tests)
- `MarketTickBufferTest` (13 tests)
- `PaperManualOrderSubmitClientTest` (6 tests)

Total: 141 tests, all PASS.

Required categories and the tests that prove them:

| Category | Tests |
|---|---|
| LEGACY_PRICE_EXECUTION_REJECTION | `no live quote yields NO_LIVE_QUOTE even if a persisted Room store holds a recent bar`; `provider has no dependency on the persisted market store`; `a numeric preview relabelled as a trusted source cannot pass against the current trusted quote`; `legacy Room preview cannot pass even against a trusted final quote`; `self comparison of a legacy Room reference cannot show zero drift and authorize`; `a legacy Room preview with a trusted final quote is blocked, never allowed` |
| SYNTHETIC_PRICE_EXECUTION_REJECTION | `synthetic FAKEPACA test-feed quote is rejected even when fresh`; `demo in-memory quote is execution ineligible`; `demo or test quote never feeds notional, even when fresh`; `latest visible price becoming a demo quote before submit blocks with zero POST`; `latest visible price switching to the TEST stream before submit blocks with zero POST` |
| TRUSTED_LIVE_PRICE_PATH | `fresh real-feed IEX quote is the only execution authority and resolves to its mid`; `real IEX quote with both sides is LIVE_QUOTE_MID at the mid price`; `IEX quote frame becomes a trusted execution reference end to end`; `trusted IEX quote feeds notional and result records LIVE_QUOTE_MID FRESH`; `approved fresher trusted quote within drift tolerance passes full gate`; `two-sided quote mid within tolerance passes`; `event time after receipt within the skew tolerance is accepted at its conservative age` |
| STALE_PRICE_FAIL_CLOSED | `stale IEX quote is rejected as STALE and no other source rescues it`; `age exactly at the 10 second limit is trusted, one millisecond beyond is STALE`; `receipt fresh but event 11 s old is STALE, because the event time is the older timestamp`; `stale trusted quote blocks in preflight and is not a warning-only price`; `trusted price that becomes stale before submit blocks with not-fresh`; `trusted reference that ages out between review and submit is blocked with zero POST` |
| FINAL_REVALIDATION | `trusted price that disappears before final submit is rejected with zero POST`; `final price provider failure fails closed with zero POST and no fallback value`; `no trusted execution price at review or at submit sends zero POST`; `final drift above threshold is rechecked and sends zero POST`; `trusted quote that disappears is rejected, never replaced by an older value` |
| FUTURE_TIMESTAMP_FAIL_CLOSED | `future event time ahead of now with a current receipt fails closed`; `future receipt time ahead of now with a current event fails closed`; `event time far in the future with a current receipt time fails closed`; `receipt time far in the future with a current event time fails closed`; `future skew beyond 2 seconds is FUTURE_TIMESTAMP, never trusted`; `raw age just beyond negative tolerance is rejected upstream as a future timestamp` |
| EVENT_AFTER_RECEIPT (receipt/event ordering) | `event time after receipt beyond the skew tolerance fails closed`; `event time after receipt within the skew tolerance is accepted at its conservative age` |
| UNKNOWN_PROVENANCE_FAIL_CLOSED | `quote with undeclared provenance is execution ineligible`; `unknown provenance fails closed in the provider and in preflight`; `only ALPACA_IEX_REAL_TIME can be trusted, every other provenance is rejected` |
| SYMBOL_MISMATCH_FAIL_CLOSED | `a trusted reference for another symbol is SYMBOL_MISMATCH and never prices this intent`; `a trusted QQQ quote never authorizes an SPY intent`; `different symbol blocks`; `symbol mismatch still blocks even with negative raw age within tolerance` |
| TEST_ENDPOINT_NOT_IEX | `test-stream endpoint is reported as synthetic and can never become an execution reference` (`AlpacaStockQuoteEmissionTest`, with the actual `AlpacaStreamEndpoint.TEST_STREAM_URL`) |
| NO_TRUSTED_PRICE_FAIL_CLOSED | `no trusted quote fails closed deterministically with no zero, NaN or placeholder price`; `missing reference is a block, identical in effect to a rejected one`; `missing final reference blocks as no trusted execution price`; `empty symbol and unknown symbol return NO_LIVE_QUOTE without crashing`; `bar-only symbol with no received quote is NO_LIVE_QUOTE`; `NaN, infinite, zero, negative and crossed quotes are INVALID_QUOTE and never a price`; `one-sided quote fails closed as INVALID_QUOTE and is never used as a reference` |
| DRIFT loophole closed | `self comparison of a legacy Room reference cannot show zero drift and authorize`; `a numeric preview relabelled as a trusted source cannot pass against the current trusted quote` |
| Order body unchanged | `valid request sends exactly one Paper POST body and parses order id` (asserts no price keys) |
| Quantity unchanged | `quantity is unchanged by the execution price` |
| Concurrency (publication) | `concurrent quote pushes for two symbols keep the newest tick of each symbol` |
| Existing guards preserved | the full debug and release unit suites (§20) |

Test quality notes:

- The provider, evaluator, engine, policy, gate, and executor tests run the real production code. Only the HTTP client, the audit DAO, and the clock are fakes.
- Provider tests prove "Room cannot rescue" by construction (no repository parameter), and by a structural test (reflection on the constructor and fields). A behavioural Room test is impossible because the provider has no Room input.
- The TEST endpoint test uses the real `AlpacaStreamEndpoint.TEST_STREAM_URL`.

---

## 19. Remaining limitations and residual risks (not fixed here)

1. Price numerics are `Double`, not exact decimal. The price is the mid of two Doubles and is not labelled exact anywhere. Kept because no current safety invariant depends on decimal exactness beyond the 0.25 % drift threshold. This is a known limitation for the later Market Dataset work.
2. Device clock: the receipt time and the event-time comparison use the device clock. A disagreement over 2 000 ms makes quotes unavailable (fail closed). Nothing is corrected automatically.
3. Availability: one-sided quotes are no longer accepted, and a quote beyond 10 s is stale. During quiet periods the execution reference can be unavailable (fail closed by design).
4. Stream overflow: `AlpacaStockMarketDataClient` quote emission uses `tryEmit` into a bounded SharedFlow (256). A dropped quote leaves the previous quote in place, which then ages out (fail closed). It is not a trust leak.
5. The IEX feed is single-venue. Provider semantics remain an open item from 3.a.1 §38. The LIVE label names the source, not consolidated market data.
6. The feed name (IEX) is not shown on the review screen (§11).
7. Buying-power margin: the buying-power comparison is made at preflight. The submit gate revalidates price drift but does not re-run the buying-power comparison. Preflight must be ≤ 60 s old at submit.
8. Token issuance uses the reference fetched at refresh, checked against the current clock. The executor re-fetches before the POST, so a trust change is refused there.
9. LIMIT orders: the pre-existing drift semantics compare the market reference with the limit price. This was true in the baseline, is unchanged here, and LIMIT is not reachable from the UI.
10. `PaperPortfolioRiskViewModel` still displays a Room close per exposure. It is display-only (§11) and should be relabelled in 3.a.1-D.
11. `clearAll` is unchanged (§15) and is deferred to PHASE 3.a.1-D.
12. Historical audit and preview rows keep their `ROOM_BAR_CLOSE` labels (§16).
13. The provider class keeps its name `MarketPriceSnapshotProvider`, and `MarketPriceSnapshot.kt` holds only enums, to avoid churn.
14. Validation is offline (JVM unit tests, static analysis, and lint). No runtime, emulator, device, IEX connection, test-feed connection, broker GET, or broker POST was used. Runtime validation is the next phase (§23).
15. Reflection and deserialization are not supported boundaries for the trust constructor.

---

## 20. Validation (publication audit)

Each result below comes from a run during the audit.

- Compile (main and unit tests, debug): BUILD SUCCESSFUL.
- Targeted trust suite (`--tests` on the 10 classes in §18, run independently): BUILD SUCCESSFUL. 141 tests, 0 failures, 0 errors, 0 skipped.
- Full debug unit suite (`:app:testDebugUnitTest --rerun-tasks`): BUILD SUCCESSFUL. From the XML reports: 112 classes, 2140 tests, 0 failures, 0 errors, 0 skipped.
- Full release unit suite (`:app:testReleaseUnitTest --rerun-tasks`): BUILD SUCCESSFUL. From the XML reports: 112 classes, 2140 tests, 0 failures, 0 errors, 0 skipped.
- Safety scan (`scripts/safety-scan.ps1`): `allowed_phase2v_submit=11 suspicious=0 forbidden=0`. `ALLOWED_NEGATIVE_DOC_OR_GUARD=61`. Matches the baseline; no regression.
- Lint debug (`:app:lintDebug`): exit FAILED, caused only by the pre-existing `NewApi` error at `core/Symbols.kt:40` (file not modified in this phase). XML: 57 issues, 1 error, 55 warnings and information, all in untouched files. Phase-file findings: 0.
- Lint release (`:app:lintRelease`): exit FAILED, caused by the same pre-existing error. XML: 44 issues, 1 error, 42 warnings and information. Phase-file findings: 0.
- New lint suppressions added: 0 (verified: no `@Suppress`, `tools:ignore`, `lint-disable`, or `SuppressLint` in the diff).
- `git diff --check`: PASS for tracked changes (exit 0). New untracked files checked with `git diff --no-index --check -- /dev/null <file>`: clean.

---

## 21. Exact numerics note

Authoritative price type: `Double` (`ExecutionReferencePrice.Trusted.price`). Mid = `(bid + ask) / 2.0`, with both sides required. This is a limitation, not an exactness claim. No BigDecimal refactor was made, because no safety invariant in this phase is violated by `Double`.

---

## 22. Scope and git state

- Publication artifact: the staged paths of this audit, and this document.
- Schema changes: 0. Room version: 9.
- Runtime: 0. Broker GET: 0. Broker POST: 0. Network requests: 0. Installed DB mutation: 0.
- Showcase: the three files under `ui/showcase/` are untracked and untouched.
- 3.a.1-B document: unchanged.

---

## 23. Handoff

The next phase is `PHASE 3.a.1-C-R1 — runtime fail-closed validation` (not started here). It must run in an isolated, approved environment and must not reuse any value from this document as runtime evidence.

3.a.2 must not begin before 3.a.1-C-R1 and 3.a.1-D are decided. 3.a.2 may rely on:

- The execution boundary types in §5. The dataset work must not reuse execution references as training rows.
- `MarketDataProvenance` as the live provenance vocabulary. A future canonical provenance is NOT implemented here and must be added with its own rules.
- The fail-closed behavior in §8.

3.a.2 must not assume:

- That any persisted price has provenance. Legacy rows still have none, and the importer must import them as 0 rows (3.a.1-B §10).
- That a Room bucket start is a freshness clock.
- That `clearAll` is safe. It remains unchanged until 3.a.1-D.

---

## 24. Publication audit (independent) — findings and fixes

Audit start `2026-10-04T23:36:26Z`. Baseline verified (`HEAD` = `origin/main` = `8328049`). Staging empty.

Defects found and fixed before publication:

| # | Finding | Severity | Fix | Evidence |
|---|---|---|---|---|
| F1 | `Trusted` had an `internal` constructor. Any production code in the module could construct a trusted reference with an arbitrary price, provenance, and freshness, skipping the policy. Only the evaluator did so today | HIGH (trust construction bypass capability) | Constructor made `private`. The only path is `Trusted.derive`, which applies every rule | §5; `ExecutionReferencePrice.kt` line 23 and 67 |
| F2 | One-sided quotes were trusted by using the single valid side as `LIVE_QUOTE_BID_ASK` | MEDIUM (a side was used as a reference) | Mid requires both sides. One-sided → `INVALID_QUOTE`. `Trusted` accepts only `LIVE_QUOTE_MID`. Policy trusted set is `LIVE_QUOTE_MID` only | §6; tests `one-sided quote is INVALID_QUOTE…`, `one-sided quote fails closed…` |
| F3 | An event time more than 2 s AFTER the receipt time was not rejected explicitly | MEDIUM (ordering invariant not enforced) | `EVENT_AFTER_RECEIPT` added; `executionTimeRejection` checks it | §7; tests `event time after receipt beyond the skew tolerance fails closed` |
| F4 | Future event and future receipt were rejected only through a combined minimum; there was no test for the single-timestamp cases | LOW (behaviour correct; not proven) | Explicit checks in `executionTimeRejection`; tests for each single-timestamp case added | §7; tests `event time far in the future…`, `receipt time far in the future…` |
| F5 | Preflight did not check that the trusted reference matched the intent symbol | MEDIUM (symbol binding relied on the caller) | `SYMBOL_MISMATCH` in the engine | §6; test `a trusted QQQ quote never authorizes an SPY intent` |
| F6 | Stock client used `else → TEST` for any non-IEX endpoint | LOW (fail-open label mapping, unreachable through the guard) | Explicit `when` with `UNKNOWN` as the default. Equality with canonical constants only | §4 step 2; `AlpacaStockMarketDataClient.kt` lines 75–85 |
| F7 | Tick buffer published its snapshot outside the lock (lost update) | MEDIUM (concurrency; documented thread-safe class) | Publication moved inside the lock in three places | §14; test `concurrent quote pushes for two symbols keep the newest tick of each symbol` |

Checks that passed without change (VERIFIED):

- Provenance derivation is by endpoint equality. No substring, label, symbol, UI state, or mutable boolean is authority.
- `UNKNOWN` is the default provenance. A tick without declared provenance is execution-ineligible (test).
- The in-memory demo path produces no tick and no quote. No `pushQuote` or `MarketTick(` path exists for it.
- The provider has no Room dependency (structural test). No preflight overload exists (one `preflight` function).
- `EXECUTION_AUTHORITATIVE_LEGACY_ROOM_CONSUMERS = 0`. `SYNTHETIC_EXECUTION_AUTHORITY_PATHS = 0`.
- The stale path blocks in preflight and in the final gate. No stale warning-only path remains.
- The notional and buying-power comparisons are trusted-only. Without a trusted reference they are not computed, and readiness is BLOCKED.
- The final gate and the executor re-fetch and re-evaluate. No `Trusted` value is cached as authority. Stored review references are rechecked against the current clock.
- The drift loophole is closed. A legacy or relabelled preview cannot pass against a current trusted quote.
- The token, the phrase, TTL, single use, one emission per session, the POST path, the body, quantity, guards, network, and Room are unchanged (files not in the diff).
- The UI arm correction is verified (§1.1). The domain defect stands (§1.1).
- Provenance enum handling: the only trust-producing comparison is `== ALPACA_IEX_REAL_TIME` (the rules) and `when` on the endpoint (with `UNKNOWN` as default). No `else → trusted`. A future enum value fails closed.
- No dataset, ML, feature, label, replay, collector, or Parquet code exists in the new or changed production code (VERIFIED by search).
- `clearAll` is unchanged. The legacy rows are untouched.

LIMIT decision (§10): `LIMIT_TRUSTED_REFERENCE_REQUIREMENT=INTENTIONAL_SAFE_POLICY`.
