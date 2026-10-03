# Phase 3.a.1-B — Legacy Market Quarantine and Trusted Price Source Boundary

Status: DESIGN AND PUBLICATION. Static audit of the committed tree at `279077a`.
Scope: no production code, Room, schema, Gradle, manifest, tests, UI, network, or database changes. No runtime, emulator, broker, or device DB inspection. Phase 3.a.2 is not started.

Verdict: `PASS_3A1B_MARKET_TRUST_BOUNDARIES_DESIGNED`
Severity of current fallback: HIGH
`IMMEDIATE_EXECUTION_PRICE_HARDENING_REQUIRED`: YES
Path: PATH A — APPROVED (3.a.1-C execution price hardening before 3.a.2)
Legacy policy: `LEGACY_MARKET_ROWS_DECISION = PRESERVE_QUARANTINED_EXCLUDE_FROM_LEARNING`

Approved implementation order:

1. Publish 3.a.1-B (this document).
2. Implement 3.a.1-C execution price hardening, within the scope in §15 and the constraints in §15.1.
3. Validate and publish 3.a.1-C.
4. Only then start 3.a.2 dataset domain.

Legend for evidence: VERIFIED = read in code in this phase. NOT VERIFIED = stated as open, with the reason.

---

## 1. Summary of findings

1. The only price store, `market_bars_1m`, has no provenance column. It mixes three writers: the IEX stream (real), the FAKEPACA test stream (synthetic), and two local demo buttons (synthetic, device clock). Nothing in a row says which one wrote it.
2. `MarketPriceSnapshotProvider` tier 2 returns the last Room bar close as an execution-relevant price. It has no provenance check and no staleness rejection. `hasPrice` treats `STALE` as usable.
3. The final execution gate (`PaperFinalPriceStabilityPolicy`) is the only thing that checks freshness and drift before the one-shot POST. For a Room close, it is satisfied only when the submit happens within 10 seconds of the bar's minute open. That window is measured from the bucket start, not from the market data. A synthetic close that is written and then submitted inside that window passes the gate. The drift check compares the close with itself and cannot detect it.
4. The market order body carries no price. The Room price therefore cannot change the quantity or the price sent to the broker. It does decide whether the POST is sent, and it feeds the notional and buying-power decision that the operator reviews.
5. `clearDemoState()` calls `clearAll()`, which deletes every row of `market_bars_1m` for every symbol. The same action also clears features, signals, and the journal. It is reachable from a diagnostic card without a DEBUG gate found on the call path.
6. The required rule "synthetic and demo data must never qualify for real Paper execution authority" is violated by the current code. This is the reason the immediate hardening is required.

---

## 2. Scope and method

Files read (this phase, static):

- `data/market/price/MarketPriceSnapshotProvider.kt` (tiers 1–3, lines ~39–95)
- `data/market/price/MarketPriceSnapshot.kt` (`hasPrice`)
- `data/market/price/MarketPriceFreshnessPolicy.kt` (thresholds, `classify`, `thresholdFor`)
- `data/paper/submit/PaperFinalPriceStabilityPolicy.kt` (defaults, `evaluate`, `isSourceCompatible`, `sourceClass`, `qualityRank`)
- `data/paper/submit/PaperManualSubmitExecutor.kt`, `PaperManualSubmitGate.kt`, `PaperManualOrderSubmitClient.kt`
- `data/paper/preflight/PaperOrderPreflightEngine.kt`
- `ui/dashboard/PaperOrderPreflightViewModel.kt`, `PaperManualSubmitViewModel.kt`, `PaperPortfolioRiskViewModel.kt`
- `data/pipeline/OfflineMarketPipelineCoordinator.kt`, `AlpacaTestStreamPipelineBridge.kt`
- `ui/dashboard/OfflineDashboardViewModel.kt`, `VelaDashboardSections.kt`, `OfflineDashboardScreen.kt`
- `data/market/source/alpaca/*`, `data/market/tick/MarketTickBuffer.kt`, `data/market/OneMinuteBarAggregator.kt`
- `db/room/dao/MarketBarDao.kt`, `db/room/entities/MarketBar1mEntity.kt`, `data/repository/MarketDataRepository.kt`
- `app/build.gradle.kts` (build flags), `MainActivity.kt` (DEBUG gating), `VelaLabApplication.kt`

Writer and reader searches were repository-wide (`*.kt`, excluding tests) for `persistBar`, `persistBars`, `clearAll`, `clear(`, `recentBars`, `countAll`, `addUpdate`, `pushQuote`, and `recordBar`.

Not read in this phase: `local.properties` (contains developer credentials; not needed and deliberately not opened), the release APK, and any device state.

---

## 3. Producer map — every writer of `market_bars_1m`

Only one insert path reaches the table in production code: `OfflineMarketPipelineCoordinator.addUpdate` → `MarketDataRepository.persistBar` → `MarketBarDao` insert with `OnConflictStrategy.REPLACE` on the unique key `(symbol, bucketStartEpochMillis)`. VERIFIED.

| ID | Method (class) | Upstream caller | Source type | Symbol(s) | Real / synthetic / demo / test | Network or local | Overwrite semantics | Provenance survives in row? |
|---|---|---|---|---|---|---|---|---|
| P1 | `persistBar` via `OfflineMarketPipelineCoordinator.addUpdate` | `AlpacaTestStreamPipelineBridge` (class used for test feed) | FAKEPACA test stream `wss://stream.data.alpaca.markets/v2/test`, source label `alpaca-test-stream` | FAKEPACA mapped by the test stream | SYNTHETIC / TEST | Network | REPLACE on `(symbol, bucket)` | NO |
| P2 | `persistBar` via `addUpdate` | `alpacaStockPipelineBridge` in `VelaLabApplication` — same class `AlpacaTestStreamPipelineBridge` | IEX stream `wss://stream.data.alpaca.markets/v2/iex`, source label `alpaca-iex-stream` | SPY (default) and others subscribed | REAL (single-venue IEX, see NOT VERIFIED N4) | Network | REPLACE on `(symbol, bucket)` | NO |
| P3 | `persistBar` via `addUpdate` | `OfflineDashboardViewModel.generateSpyUpdate` (line ~62) | Locally generated update, symbol `"SPY"` | SPY | SYNTHETIC / DEMO | Local, device clock | REPLACE on `(symbol, bucket)` — collides with P2 for the same minute | NO |
| P4 | `persistBar` via `addUpdate` | `OfflineDashboardViewModel.generateBtcUpdate` (line ~46) | Locally generated update, symbol `"BTC/USD"` | BTC/USD | SYNTHETIC / DEMO | Local, device clock | REPLACE on `(symbol, bucket)` | NO |

Deletes and clears:

| ID | Method | Caller | Effect | Notes |
|---|---|---|---|---|
| D1 | `MarketBarDao.clear()` → `DELETE FROM market_bars_1m` via `MarketDataRepository.clearAll()` | `OfflineDashboardViewModel.clearDemoState()` (line ~81) → `ControlsCard` clear button | Deletes every row, every symbol, real and synthetic alike | Destructive, irreversible, no provenance filter |
| D2 | `MarketBarDao` `DELETE ... WHERE symbol` via `MarketDataRepository.clear(symbol)` | No production caller found | None observed | Exists; unused |

Bulk insert paths (`MarketBarDao.insertAll`, `MarketDataRepository.persistBars`) exist but have no production caller found. VERIFIED by repository-wide search. Any future caller must be added to this map.

Producer-side consequences:

- P2 and P3 write to the same key space. A demo SPY press in the same minute as a real IEX SPY bar replaces the real bar. The REPLACE has no source check.
- P1 (test stream) and P2 (IEX) are both routed through the same bridge class. The only distinguishing information is the source label, and it is not persisted.
- The test-stream path (P1) does not push ticks into `MarketTickBuffer`. Tier 1 is therefore fed only by the IEX stock client path (see §5 and NOT VERIFIED N3).

---

## 4. Consumer map

Classes: A = UI/display, B = diagnostics, C = analysis, D = Paper preview/readiness, E = risk, F = execution authorization, G = execution parameter, H = other.

| ID | Consumer | Reads from | Class | Effect |
|---|---|---|---|---|
| C1 | `MarketPriceSnapshotProvider.snapshotFor` tier 2 | `recentBars(symbol, 1)` | — (source of price) | Feeds C2, C3, C4 |
| C2 | `PaperManualSubmitExecutor` → `finalPriceSnapshotProvider` → `PaperManualSubmitGate.evaluate` → `PaperFinalPriceStabilityPolicy` | C1 | F (gate that controls POST) | Decides `PRICE_NOT_FRESH` and `PRICE_DRIFT_EXCEEDED`; if allowed, calls `submitClient.submitOnce` |
| C3 | `PaperManualSubmitViewModel` (line ~267 snapshot, ~655 `evaluateFinalPrice`, ~688 gateInput) | C1 | F (UI pre-check; executor is authoritative) | Shows gate status; the operator sees a Room-derived pass or block |
| C4 | `PaperOrderPreflightViewModel` line ~609 → `PaperOrderPreflightEngine` `priceUsed`, `notional`, buying-power block, `priceSource`, `priceFreshness` | C1 | D + G | Preflight notional, BUY `InsufficientBuyingPower`, preview metadata |
| C5 | `PaperOrderPreflightViewModel` line ~607 `latestBar` → `latestLocalClose` → `PaperOrderPreflightEngine` | `recentBars(symbol, 1)` directly, **not through the provider** | D + G | Fourth price path: used when the snapshot price is absent. No freshness, no provenance |
| C6 | `PaperPortfolioRiskViewModel` line ~127/138 → `PerSymbolPaperExposure.latestLocalClose` | `recentBars(symbol, 1)` | A + E (display; warning at line ~208) | Shows exposure with a Room close; warning when null; no gate found |
| C7 | `VelaDashboardSections.kt:453`, `OfflineDashboardScreen.kt:2214` | C6 | A | Display |
| C8 | `CandlesViewModel` line ~94 | `recentBars(selectedSymbol, n)` | A | Chart display |
| C9 | `MarketHistoryViewModel` lines ~64, ~80 | `recentBars`, `countAll` | B | History and diagnostics |
| C10 | `OfflineDashboardViewModel` line ~100 | `countAll` | B | Pipeline count |
| C11 | `FeatureEngine` | in-memory `OneMinuteBarAggregator`, **not Room** | C (analysis) | Features do not read `market_bars_1m` today. VERIFIED. Must stay that way (see §10) |

Result: no consumer currently uses Room data for a model feature, dataset export, or training. The only execution-relevant consumers are C2, C3, C4, and C5.

---

## 5. Price chain: exact precedence and freshness mechanics

### 5.1 `MarketPriceSnapshotProvider.snapshotFor(symbol)`

- Tier 1 — live quote from `MarketTickBuffer` (in-memory). Source `LIVE_QUOTE_MID` or `LIVE_QUOTE_BID_ASK`. Threshold `liveQuoteFreshMillis` = **10 000 ms**.
- Tier 2 — last Room bar close: `marketDataRepository.recentBars(normalized, 1).lastOrNull()`. Price = `recent.close`. Source `ROOM_BAR_CLOSE`.
  - Timestamp used for age: **`recent.bucketStart`** (the minute open), not the last update and not the last trade.
  - Age = `nowMillis − bucketStart` (coerced to ≥ 0).
  - Freshness = `classify(ROOM_BAR_CLOSE, age)` with threshold `roomBarFreshMillis` = **300 000 ms**.
  - No rejection when stale. The `reason` field is set, but the snapshot is still returned with a price.
- Tier 3 — `MarketPriceSnapshot.missing(...)`.

VERIFIED.

`MarketPriceSnapshot.hasPrice = price != null && freshness != MISSING`. A `STALE` snapshot therefore has `hasPrice == true`. VERIFIED. This is the defect in F3 below.

Sources enum: `LIVE_QUOTE_MID`, `LIVE_QUOTE_BID_ASK`, `LIVE_BAR_CLOSE` (not produced by the provider), `ROOM_BAR_CLOSE`, `NONE`.

### 5.2 Final gate thresholds (`PaperFinalPriceStabilityPolicy`)

| Parameter | Value | Source |
|---|---|---|
| `maxDriftPercent` | 0.25 % | `DEFAULT_MAX_DRIFT_PERCENT` |
| `maxFinalPriceAgeMillis` | 10 000 ms (allowed range 1 000–60 000) | `DEFAULT_MAX_FINAL_PRICE_AGE_MILLIS` |
| `maxFutureSkewMillis` | 2 000 ms (allowed range 0–5 000) | `DEFAULT_MAX_FUTURE_PRICE_SKEW_MILLIS` |
| Source freshness limit | `thresholdFor(finalSource)` | `MarketPriceFreshnessPolicy` |
| Allowed age | `min(sourceFreshnessLimit, maxFinalPriceAgeMillis)` | computed |

For `ROOM_BAR_CLOSE`: allowed age = `min(300 000, 10 000)` = **10 000 ms**.

Conditions for `ALLOWED` (all must hold, computed in `evaluateFinalPrice`):

1. `preview.priceFreshness == FRESH`
2. `finalPrice.freshness == FRESH`
3. `finalSource != NONE`
4. `isSourceCompatible(previewSource, finalSource)`
5. `rawAge ≥ −maxFutureSkewMillis`
6. `effectiveAge ∈ [0, allowedAge]`
7. `classify(finalSource, effectiveAge) == FRESH`
8. Symbol matches the preview.
9. Drift `|latest − previewPrice| / previewPrice × 100 ≤ 0.25`.

Otherwise: `PRICE_NOT_FRESH` (1–8) or `PRICE_DRIFT_EXCEEDED` (9).

Source compatibility: same `sourceClass` (QUOTE or BAR), or `qualityRank(final) > qualityRank(preview)`. Ranks: LIVE_QUOTE_MID 4, LIVE_QUOTE_BID_ASK 3, LIVE_BAR_CLOSE 2, ROOM_BAR_CLOSE 1. VERIFIED.

### 5.3 What this means for a Room close

A Room close becomes an execution price only if all of these hold at the same time:

- Preview taken within 300 s of its bucket open (preview FRESH).
- Final submit taken when `now − bucketStart ∈ [−2 s, 10 s]`. The latest bar must therefore have a bucket that opened in the last 10 seconds. A bar that already existed from the previous minute is 10–70 s old at the start of the new minute and fails.
- Preview and final both `ROOM_BAR_CLOSE` (compatible).
- The close has not moved more than 0.25 % since preview. A synthetic close that is unchanged gives drift 0.

Concrete path (debug build with the submit compile flag enabled, see §8):

1. 12:00:03 — operator presses the demo SPY button. A bar with bucket 12:00:00 and close X is written by REPLACE.
2. 12:00:04 — preflight and preview read close X from Room. Preview: `ROOM_BAR_CLOSE`, FRESH, price X.
3. 12:00:07 — final gate reads the same bar. `rawAge = 7 000 ms`, `effectiveAge = 7 000 ms` ≤ 10 000, FRESH, same source, drift 0 → `ALLOWED`.
4. `PaperManualOrderSubmitClient` POSTs a market order. The body contains symbol, side, type, qty (user input), time_in_force, client_order_id. No price.

Effect on the broker order: none on price (the market order fills at the broker's price). Effect on the decision: the buying-power and notional check used X, and the gate allowed the POST based on X. If X is fabricated, both decisions are based on a fabricated number.

The same window also means the gate blocks most real Room closes (`PRICE_NOT_FRESH`) because the age is measured from the bucket open. The protection is accidental: it is a clock window, not a data-quality test.

---

## 6. Execution impact classification of the Room fallback

For each item, the effect of a Room close (C1) on the Paper flow:

| Item | Affected by Room price? | Precise effect |
|---|---|---|
| Display | YES | Price, notional, and gate status shown to the operator |
| Notional | YES | Preflight `notional = priceUsed × quantity` (C4) |
| Readiness | YES | Preflight can become `PREFLIGHT_BLOCKED` (BUY `InsufficientBuyingPower`) |
| Risk gate | YES | Final gate `PRICE_NOT_FRESH` / `PRICE_DRIFT_EXCEEDED` (C2). This is the only gate that decides whether POST is sent based on price |
| Quantity | NO | Quantity is user input. No quantity sizing from price found. VERIFIED for the flow read |
| Authorization token | NOT RE-VERIFIED | No price input observed in the parts read. The token binding must be re-checked in 3.a.1-C |
| Order draft | YES (metadata) | Draft carries `priceSource`, `priceFreshness`, `estimatedNotional` |
| Review | YES (display) | Review shows the same metadata; operator sees `ROOM_BAR_CLOSE` labelled as a price |
| POST body | NO | Body fields: symbol, side, type, qty, time_in_force, client_order_id; `limit_price` only for LIMIT orders |
| Limit price | NO | For LIMIT, `priceUsed` is the operator's limit price. Room close does not set the limit. The final gate still uses Room for LIMIT orders |
| Stop price | NOT VERIFIED | No stop order type was observed in the parts read |
| Market order decision | YES (gate, not body) | Gate allows or denies the POST. The price does not travel to the broker |
| Other — audit trail | YES (preview); NOT RE-VERIFIED (submit audit) | Preview rows carry `priceSource` and `priceFreshness` from the snapshot (`PaperOrderPreflightEngine`, lines ~201–203), so a synthetic Room close is recorded as `ROOM_BAR_CLOSE`. Whether the submit audit row stores the same field was not re-read (N9) |

Nuance for market orders: the order body contains no price, so the Room price cannot change what is sent to the broker. It changes whether the send happens (gate), what the operator is told (display, notional, review), and what is recorded (audit).

---

## 7. Severity of the current fallback

| ID | Finding | Severity | Justification |
|---|---|---|---|
| F1 | Room tier 2 is an execution price (C2, C3, C4) with no provenance and no freshness rejection; a synthetic bar can pass the final gate inside the 10 s window | **HIGH** | Violates the hard execution trust rule. Reachable only in debug builds with `MANUAL_PAPER_SUBMIT_COMPILED` true (§8) and only after the human review, arm, token, and confirmation steps. Limited blast radius because the POST body has no price and quantity is user input |
| F2 | C5: `latestLocalClose` from Room is used in preflight notional/buying power when the snapshot price is absent. No freshness, no provenance | **MEDIUM** | Wrong notional can cause a false block or a false pass in the preflight buying-power check. Preflight is also debug-only |
| F3 | `hasPrice` and tier 2 accept `STALE` prices | **MEDIUM** | Stale prices flow into preflight notional and preview metadata. Contributes to F2 |
| F4 | Freshness clock is bucket open, not market or last-update time | **MEDIUM** | Produces both false blocks (common) and an accidental pass window (F1). Wrong clock for every source that writes `market_bars_1m`, including real IEX data |
| F5 | Demo writers P3 and P4 write synthetic bars into the only price store, and P3 replaces real IEX SPY bars for the same minute | **HIGH** | Contaminates the price store and destroys real bars (data integrity). Static read shows no DEBUG gate on the demo controls, so release reachability is possible (NOT VERIFIED N1) |
| F6 | `clearAll()` deletes all `market_bars_1m` rows and also clears features, signals, and the journal | **HIGH** | Irreversible deletion of real market history and the journal from a diagnostic card. No provenance filter. See §9 |
| F7 | C6/C7 `latestLocalClose` in portfolio exposure display with no freshness | **LOW** | Display only; warning shown when null |
| F8 | Test stream (P1) reaches the shared table but does not feed tier 1 | **INFO** | Keeps tier 1 real-only in code read. Re-check on any future change (N3) |
| F9 | Release build: `MANUAL_PAPER_SUBMIT_COMPILED = false`, credentials empty, Paper execution ViewModels passed as `null` when `BuildConfig.DEBUG` is false | **INFO** (positive) | Execution is not reachable in release code as read. VERIFIED from build config and `MainActivity` |
| F10 | Market order POST carries no price | **INFO** (positive) | Price cannot alter the broker order price for market orders |

**Overall severity of the current fallback: HIGH** (driven by F1, F5, and F6).

Why not CRITICAL: no release reachability for the execution path (F9); the POST body has no price (F10); quantity is user input; the arm, token, and confirmation steps still sit between the gate and the POST. Why not MEDIUM: a synthetic price can satisfy the only price gate that controls the POST, and the demo controls write into the same table the gate reads.

---

## 8. `IMMEDIATE_EXECUTION_PRICE_HARDENING_REQUIRED`: YES

Reasons:

1. The code violates a hard rule already set in the 3.a.1 architecture: synthetic and demo data must never qualify for real Paper execution authority (F1, F5).
2. The fallback is not fixable by the dataset work in 3.a.2. Dataset work lives in a separate database and does not change the execution provider.
3. The freshness clock is wrong (F4), so even correct real data passes or fails for the wrong reason.
4. The current release build disables the execution path (F9), but the debug build can enable it through `MANUAL_PAPER_SUBMIT_COMPILED` in the developer's `local.properties`. The state of that flag was not inspected (N2). A debug build with the flag on is in the scope of this rule.

Consequence: execution hardening must come before any further change that adds Paper execution exposure.

---

## 9. clearDemoState → clearAll: exact impact and callers

Callers:

- `ui/dashboard/OfflineDashboardScreen.kt:200` — `clearDemo = viewModel::clearDemoState`
- `ui/dashboard/VelaDashboardSections.kt:568` — `ControlsCard(actions.generateBtc, actions.generateSpy, actions.clearDemo)` inside the "Diagnostico" section. No DEBUG gate found on this call path.
- No other caller found.

Body of `clearDemoState()` (`OfflineDashboardViewModel.kt` line ~78; as read earlier in this phase's series):

1. `marketDataRepository.clearAll()` → `DELETE FROM market_bars_1m` (all symbols)
2. `featureRepository.clear()`
3. `signalRepository.clear()`
4. `journalRepository.clear()`

Not touched by this action, per the four calls listed above: `paper_*` evidence tables, the Paper order audit tables, and the position reconciliation tables. This relies on the body of `clearDemoState()` as read earlier in this audit series; it was not re-read in this phase.

Impact:

- Deletes real IEX bars, synthetic bars, and test-stream bars. Irreversible.
- Deletes the journal (`journal_events`). This removes audit history. It is an audit-integrity action on a diagnostic card.
- Side effect on execution: after `clearAll`, the Room tier has no bar. The provider returns `MISSING`, the final gate returns `PRICE_NOT_FRESH`, and preflight has no notional price. So today `clearAll` fails closed for execution. That is incidental, not designed.
- Partial-failure behavior across the four steps: NOT RE-VERIFIED in this phase (N6).

Future remove / narrow / rename / isolate recommendation for `clearAll`:

- Remove from the diagnostic card in release. Keep for debug only, behind an explicit confirmation.
- Narrow: the market table must never be cleared by a diagnostic button. Clear only synthetic demo state, and only after the demo writes are moved out of `market_bars_1m` (see §11).
- Rename to make the destructive scope explicit (`clearDemoStateOnly` or similar), and split the journal clear from the market clear so that the audit trail is not deleted with the market data.
- Isolate: the destructive path should not exist against any table that holds real market data. The recommended end state is that real market data is never deleted by the app; retention is a separate, reviewed operation.

These are recommendations only. No change is made in this phase.

---

## 10. Legacy `market_bars_1m` policy

Decision (inherited from 3.a.1): `LEGACY_MARKET_ROWS_DECISION = PRESERVE_QUARANTINED_EXCLUDE_FROM_LEARNING`.

Formal policy:

| Rule | Meaning |
|---|---|
| PRESERVE | Keep every existing row. Do not compact, rewrite, or export-and-delete |
| DO NOT DELETE | No migration, no code path, and no diagnostic button may delete legacy rows. This requires action on D1 (see §9) |
| DO NOT RETRO-RELABEL | Do not infer provenance from symbol, timestamp, `syntheticVolume`, or any pattern. A `+1` volume is not proof of synthetic data |
| EXCLUDE FROM TRAINING | No training input may read the legacy table |
| EXCLUDE FROM DATASET SNAPSHOTS | No dataset snapshot may include legacy rows |
| EXCLUDE FROM MODEL FEATURES | Features must not read the legacy table. Today they use the in-memory aggregator (C11). This must remain true |
| Granularity | Prefer entire-table quarantine. Row-level labels are not possible without a provenance column, and the table mixes real, test, and demo writers |
| EXECUTION | The legacy table is never an execution price source (see §12) |
| DISPLAY | Allowed for display and diagnostics only, labelled as legacy and unverified provenance |

Quarantine mechanism options:

| Option | Description | Recommendation |
|---|---|---|
| A | Logical quarantine: a single policy constant plus read gates. Execution never reads the table. Dataset and feature code is forbidden from reading it. Diagnostics read it with a label | **RECOMMENDED for 3.a.1-C** |
| B | Physical migration: move rows to a new table or database inside Room (schema v10) | Deferred to 3.a.2, when the separate market database exists. Requires a migration and a migration proof |
| C | Rename or archive the table | Deferred; requires schema change; fold into B |
| D | Other | None identified |

Import rule for 3.a.2: the import of legacy rows into the new market database must be **0**. This is a requirement for the importer, not yet a property of code (no importer exists). It must be an explicit contract test in 3.a.2.

Consequence of entire-table quarantine: the live IEX bars in the same table are also quarantined. Real live data can still reach execution through tier 1 (quotes), which is not in the table. Persisted real data becomes usable again only when a provenance-bearing store exists (§12, fallback B).

---

## 11. Trust categories

Categories as required. A price carries exactly one provenance and one trust result. `STALE` overrides any other trust result for execution (a stale price is never execution-usable regardless of origin).

| Category | Definition | Qualifies for EXECUTION_REFERENCE? | Qualifies for RESEARCH? | Qualifies for DISPLAY? |
|---|---|---|---|---|
| `LIVE_TRUSTED_MARKET_DATA` | Quote received from the real provider stream on the allowlisted endpoint, with the feed recorded, within the live threshold | YES (fresh only) | NO (not a dataset) | YES |
| `CANONICAL_DATASET_TRUSTED` | Bar or price from the future separate market database with recorded provenance, ingestion verification, and `availableAt` ≤ decision time | Future only (fallback B), fresh only | YES | YES |
| `LEGACY_UNKNOWN_PROVENANCE` | Any `market_bars_1m` row, regardless of symbol or time | NO | NO | YES (labelled legacy) |
| `SYNTHETIC` | From FAKEPACA or any non-real stream | NO | NO | YES (labelled synthetic) |
| `DEMO` | Written by a local demo control | NO | NO | YES (labelled demo) |
| `STALE` | Any category beyond its freshness limit | NO | NO | YES (labelled stale) |
| `UNKNOWN` | No provenance recorded | NO | NO | YES (labelled unknown) |

Precedence for classification: `SYNTHETIC` and `DEMO` override the source label; `LEGACY_UNKNOWN_PROVENANCE` applies to every row in the legacy table; `STALE` overrides every trust for execution.

Current state: no persisted price in the app has verified trusted provenance. Every row in `market_bars_1m` is `LEGACY_UNKNOWN_PROVENANCE` (no source column, mixed writers, §3). Plausibility of ticker, time, or value does not change that. The only category that can currently be execution-authoritative is `LIVE_TRUSTED_MARKET_DATA` from the in-memory quote path (tier 1), subject to NOT VERIFIED N3.

Training eligibility and execution eligibility are separate questions:

| Question | Rule |
|---|---|
| May a price be used for training, dataset snapshots, or features? | Only `CANONICAL_DATASET_TRUSTED` in the future separate market database. Legacy rows: NO. Live in-memory quotes: NO (they are not a dataset) |
| May a price be used as execution-reference authority? | Only `LIVE_TRUSTED_MARKET_DATA`, fresh, and (future) `CANONICAL_DATASET_TRUSTED` with provenance under fallback B. Legacy rows: NO. Synthetic and demo: NO |

A price can be valid for display and not valid for training, and the reverse is also true. Neither permission implies the other.

---

## 12. Hard execution trust rule

1. An execution reference price may come only from `LIVE_TRUSTED_MARKET_DATA` (and, in the future, from `CANONICAL_DATASET_TRUSTED` with provenance, once fallback B exists), and only when fresh.
2. Legacy `market_bars_1m` rows never qualify automatically. Not by symbol, not by timestamp, not by being the latest row.
3. `SYNTHETIC` and `DEMO` data must never qualify for real Paper execution authority.
4. No symbol-based exception. Example: "SPY from IEX is trusted, therefore any SPY row is trusted" is forbidden. The rule is provenance-based only.
5. `STALE` never qualifies, whatever the origin.
6. `UNKNOWN` never qualifies.
7. The execution path must fail closed when no qualifying price exists.

Enforcement design (for 3.a.1-C, not implemented here): the `TrustedMarketPriceSnapshot` constructor or factory must refuse `authority = EXECUTION_REFERENCE` unless `trust == LIVE_TRUSTED_MARKET_DATA` and `freshness == FRESH`. The final gate must consume only that type.

---

## 13. Price authorities and the trusted price model

Three separate authorities. A price has one authority at a time; the type must carry it.

| Authority | Purpose | Who may use it | Fallback allowed |
|---|---|---|---|
| `DISPLAY` | Show a price with source and age | Any UI | Yes, labelled |
| `RESEARCH` | Dataset, features, training | Only `CANONICAL_DATASET_TRUSTED` (future) | Not for execution |
| `EXECUTION_REFERENCE` | Preflight notional, buying-power check, final gate | Only `LIVE_TRUSTED_MARKET_DATA` (and future canonical with provenance) | Fail closed |

Proposed `TrustedMarketPriceSnapshot` (design only):

| Field | Type | Notes |
|---|---|---|
| `symbol` | String | Normalized |
| `price` | Decimal text plus Double view | Exact text stored; Double for comparison only |
| `bid`, `ask` | Decimal text, nullable | Quotes only |
| `feed` | Enum | `IEX`, `SIP`, `TEST`, `DEMO`, `LEGACY`, `NONE` |
| `provenance` | Enum | `LIVE_STREAM`, `CANONICAL_DATASET`, `LEGACY_ROOM`, `SYNTHETIC`, `DEMO`, `NONE` |
| `trust` | Enum | Categories of §11 |
| `authority` | Enum | `DISPLAY`, `RESEARCH`, `EXECUTION_REFERENCE` |
| `marketTimestampMillis` | Long, nullable | Time of the trade or quote from the provider |
| `deviceReceivedAtMillis` | Long, nullable | Device receipt time |
| `ageMillis` | Long | Computed from the clock used for the authority |
| `freshnessLimitMillis` | Long | Limit for the authority |
| `freshness` | Enum | FRESH, STALE, MISSING |
| `reasons` | List | Human-readable rejection reasons |
| `datasetId` | String, nullable | Future |
| `contractVersion` | Int | For audit |

Freshness basis must be the market timestamp (or device receipt time) of the last trade or quote. Not the bucket open (F4).

---

## 14. Fallback policy

| Option | Description | Decision |
|---|---|---|
| A | Fail closed: no price means no execution. Preflight, preview, and final gate all refuse | **ADOPT for 3.a.1-C** |
| B | Most recent `CANONICAL_DATASET_TRUSTED` price, with strict provenance and freshness | **DEFERRED** until the separate market database and provenance exist (3.a.2+) |
| — | Automatic legacy Room fallback | **FORBIDDEN**, permanently |

Under A, the display path may still show the legacy close, labelled as `LEGACY_UNKNOWN_PROVENANCE` and not used for any gate.

---

## 15. Proposed scope for PATH A — 3.a.1-C (not implemented)

Design only. For the user to approve before any work begins.

1. Split the provider into two entry points. `snapshotFor` (display) keeps tier 2 but labels it `LEGACY_UNKNOWN_PROVENANCE`, authority `DISPLAY`. A new `executionReferenceFor` returns the tier 1 live quote or `MISSING`, nothing else.
2. Rewire the three execution consumers to `executionReferenceFor`: `PaperManualSubmitExecutor` final price (C2), `PaperManualSubmitViewModel` pre-check (C3), `PaperOrderPreflightViewModel` `priceSnapshot` (C4).
3. Remove C5 (`latestLocalClose` in preflight) from the notional and buying-power path. Keep it only for display if it is shown at all.
4. Make `hasPrice` reject `STALE` for execution. Keep display semantics separate.
5. Change the freshness basis for live quotes to the market timestamp or receipt time, and do not use bucket open for any execution decision.
6. Refuse `ROOM_BAR_CLOSE` as an execution source in `isSourceCompatible` and in the final gate, so it can never pass.
7. Move demo writers P3 and P4 out of `market_bars_1m`, or disable them in release and require debug-only writing to a synthetic-labelled store. Stop SPY collisions with IEX rows.
8. Narrow or isolate `clearAll` as in §9. Remove it from the release diagnostic card.
9. Add the logical legacy quarantine constant and read gate (option A, §10).
10. Tests to add in 3.a.1-C (list only): Room close never returns `EXECUTION_REFERENCE`; STALE never execution-usable; synthetic and demo never execution-usable; final gate refuses `ROOM_BAR_CLOSE`; preflight does not use Room for notional; no symbol-based exception; `clearAll` is not reachable in release.

Approved to proceed to 3.a.1-C after 3.a.1-B is published. Scope is the list above only.

### 15.1 Constraints on 3.a.1-C (approved)

3.a.1-C must:

- make legacy, synthetic, demo, and unknown-provenance persisted prices NOT qualify as execution-reference authority;
- fail closed when no sufficiently fresh trusted execution-reference price exists.

3.a.1-C must NOT:

- expand trading capability;
- enable REAL, LIVE, or Auto Paper;
- change the POST endpoint or the market-order body;
- add cancel, replace, or close-position;
- weaken any risk gate, the confirmation phrase, or token freshness;
- make legacy bars trusted;
- fabricate provenance.

Any `clearAll` hardening included later must remain separately auditable and must not become a general market-storage rewrite.

---

## 16. Why PATH A and not PATH B

PATH B would proceed to 3.a.2 and fold the `clearAll` isolation into that phase. That leaves the execution gate accepting synthetic bars for the length of 3.a.2. The 3.a.2 work does not touch the execution provider, so it would not fix F1. PATH A is the minimal change that closes the rule violation before any further exposure.

---

## 17. Open items — NOT VERIFIED

| ID | Item | Why not verified | Impact if wrong |
|---|---|---|---|
| N1 | Release reachability of demo controls (`ControlsCard`) | Static read found no DEBUG gate on the call path (`OfflineDashboardScreen.kt:198–200`, `VelaDashboardSections.kt:568`). Runtime not checked | If gated in release, F5 drops to MEDIUM |
| N2 | Value of `MANUAL_PAPER_SUBMIT_COMPILED` in the developer's `local.properties` | Not inspected by design (file holds credentials) | If false in all developer builds, F1 is theoretical today |
| N3 | Tier 1 instance: `AlpacaStockStreamViewModel` default-constructs `MarketTickBuffer()` (line ~41); injection from `VelaLabApplication` not confirmed | Partially read | If not the app singleton, tier 1 is empty and the chain is fail-closed by default |
| N4 | IEX as "real" — single-venue, not consolidated; provider semantics | Provider semantics in 3.a.1 §38 remain open | Affects the LIVE category label, not the rule |
| N5 | Authorization token binding — no price input observed in the read parts | Not re-read in this phase | If the token includes price, F1 widens |
| N6 | Partial-failure behavior of `clearDemoState` across the four steps | Not re-verified | Affects F6 severity only |
| N7 | Stop order types | Not observed | Would add a row to §6 |
| N8 | Release APK behavior | No build or run (out of scope) | Confirms F9 only at runtime |
| N9 | Whether the submit audit row persists `priceSource` / `priceFreshness` | Only the preview rows were read in this phase | If it does, the audit trail records a synthetic price on the POST path too (widens F1 evidence, not severity) |

---

## 18. Verdict

`PASS_3A1B_MARKET_TRUST_BOUNDARIES_DESIGNED`

- Producer map complete (§3), consumer map complete (§4), execution chain traced to thresholds (§5), execution impact classified (§6), severity assigned (§7), legacy policy formalized (§10), trust model and rule defined (§11–§13), fallback policy decided (§14), immediate hardening decided YES (§8), PATH A chosen (§16).
- Unresolved items are listed in §17 and do not block the design. None of them changes the decision to take PATH A.

Not done in this phase: no code, no tests, no Room, schema, Gradle, manifest, UI, network, or database change. Showcase files untouched. Phase 3.a.2 not started. This document is published as the only change in the publication commit; no runtime, broker GET, or broker POST was performed.
