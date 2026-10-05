# Phase 3.a.1-D / 3.a.1-D.1 — Legacy Clear, Display-Only Price, Release Demo Isolation

Status: independent final audit complete. Internal verdict `READY_3A1D_TO_PUBLISH`, issued before staging. The publication commit and push are recorded in the publication report, not in this file, because a file cannot contain its own commit hash.
Written for: owners and reviewers of the 3.a.1 phase series.
Baseline: `e1840dc3c15c31dec975adcc1a7d834d7a7a28cd` (`fix: require trusted market prices for paper execution`). HEAD and origin/main were both this commit at audit start, and at the race check before push.
Scope: the demo clear path, the Paper portfolio display of the latest persisted close, and the Release reachability of the demo card and its generators.
Not changed: Room schema, version and migrations; network endpoints and contracts; the submit boundary; the 3.a.1-C execution authority; the Market Dataset (3.a.2 is NOT STARTED); separate demo storage (deferred, §25).

Evidence legend: VERIFIED = read in code, reproduced by a test, or proven by a static search or a Gradle run in this phase. NOT VERIFIED = open, with the reason.

---

## 0. Verdicts and revision history

- Local D verdict `PASS_3A1D_LEGACY_CLEAR_DISPLAY_HARDENING_IMPLEMENTED`: superseded. Its residual (a Release demo write able to REPLACE a same-minute legacy row) was found after D.
- Local D.1 verdict `PASS_3A1D1_RELEASE_DEMO_ISOLATION_IMPLEMENTED`: superseded by the owner's final decision, which hides the entire Demo card in Release, not only its generator buttons. That change is part of this audit.
- Final audit verdict: `READY_3A1D_TO_PUBLISH` (internal, before staging).
- `3.a.1-C-R1 = DEVICE_VALIDATION_DEFERRED_SAFE_UI_PATH_UNAVAILABLE`. This is not a PASS and is not presented as one.

---

## 1. Owner decisions and how each is implemented

1. Release demo card. The whole Demo card (generator buttons, reset button and demo status row) is not composed in Release. Debug keeps it. The lower-layer generator guard stays in place. The card is not hidden by visibility alone (§13.2).
2. DAO delete queries. The five retained DAO delete queries are kept. Production callers are 0. Recorded as follow-up technical debt; no test fixture was touched to remove them (§8).
3. Variant tests. `src/testDebug` and `src/testRelease` stay. Each reads the real generated `BuildConfig.DEBUG` value of its variant (§13.1).
4. Debug persistence. Debug demo persistence may still REPLACE same-minute legacy rows. It is explicitly `DEBUG_DEMO_PERSISTENCE_REMAINS_LEGACY_CONTAMINATING_BY_DESIGN`. The whole legacy table stays quarantined: not training eligible, not DatasetSnapshot eligible, not feature eligible, not execution authoritative, and the future market DB import is 0 (§13.3, §19).

---

## 2. Precheck

- `git fetch origin` at audit start: HEAD = origin/main = `e1840dc3c15c31dec975adcc1a7d834d7a7a28cd`. Nothing was pulled, merged or rebased.
- Staging before this audit: empty.
- Showcase: `ui/showcase/` holds three untracked files (`VelaShowcaseComponents.kt`, `VelaShowcaseFixtures.kt`, `VelaShowcaseScreen.kt`). They were not edited in this audit and are excluded from the commit.

---

## 3. Old clear path (reconstructed from baseline history)

Method: `git show e1840dc:<path>`, `git grep` on `e1840dc`, and `git log -S clearDemoState`. The history shows `clearDemoState` was introduced in `db1f66b feat: publish VELA Android app with UX-2 cockpit` (the initial publication). The phase document was not used as a source for this section.

1. UI entry. The DIAGNOSTICS destination is listed in the MORE menu (`VelaAppShell.kt:332`) and rendered at `VelaDashboardSections.kt:175`. `DiagnosticsSection` called `ControlsCard(actions.generateBtc, actions.generateSpy, actions.clearDemo)` at `VelaDashboardSections.kt:568`. No `BuildConfig` gate exists on this path.
2. Wiring. `OfflineDashboardScreen.kt:200` sets `clearDemo = viewModel::clearDemoState`.
3. ViewModel. `OfflineDashboardViewModel.clearDemoState()` (`baseline lines 78–92`) runs, in a coroutine: `marketDataRepository.clearAll()`, `featureRepository.clear()`, `signalRepository.clear()` and `journalRepository.clear()`. It then resets `sequenceCounter` to 0, resets the demo prices, and sets the UI state to `Initial`. On exception it sets `lastError = "Clear failed: …"`.
4. Repositories. `MarketDataRepository.clearAll()` calls `dao.clear()`. `FeatureRepository.clear()`, `SignalRepository.clear()` and `JournalRepository.clear()` each call `dao.clear()`. `MarketDataRepository.clear(symbol)` calls `dao.deleteBySymbol(...)`. Correction to the earlier phase text: at baseline, `clear(symbol)` had no production caller. Only `clearAll()` was reached, from `clearDemoState()`.
5. DAO SQL (baseline, five delete statements): `DELETE FROM market_bars_1m` (`MarketBarDao.kt:44`), `DELETE FROM market_bars_1m WHERE symbol = :symbol` (`MarketBarDao.kt:41`), `DELETE FROM symbol_features` (`FeatureDao.kt:39`), `DELETE FROM symbol_signals` (`SignalDao.kt:42`), `DELETE FROM journal_events` (`JournalDao.kt:37`).
6. Composition root. `MainActivity.dashboardFactory()` (baseline lines 107–116) constructed the ViewModel with no build input.

Consequences (VERIFIED):
- Any user who reached Diagnostics in Release could delete every persisted market bar, including the legacy rows whose provenance is unknown, and every feature, signal and journal row.
- Deleting `journal_events` also removed Paper event types (`position_snapshot`, `paper_order`, `auto_paper_decision`), per the `JournalEventEntity.kt` KDoc.
- The counters were then reset to zero. The action was destructive under a benign label.

---

## 4. New clear path (current code, VERIFIED)

Reset demo status is `OfflineDashboardViewModel.resetDemoStatus()` (`OfflineDashboardViewModel.kt`, around line 107). It:
- sets `btcPrice` and `spyPrice` back to their initial values, which are in-memory only;
- sets `demoStatus` to "Demo generator prices reset. Stored market bars, features, signals and journal were kept.";
- clears `lastError`.

It calls no repository, DAO or coordinator. `sequenceCounter` is not reset (§14).

The four repositories (`MarketDataRepository`, `FeatureRepository`, `SignalRepository`, `JournalRepository`) expose no clear or delete function. Reflection tests check this, and `DemoResetScopeTest` scans the source.

Static counters (§24):
- `DEMO_RESET_BROAD_MARKET_DELETE_PATHS = 0`
- `DEMO_RESET_PAPER_HISTORY_DELETE_PATHS = 0`
- `DEMO_RESET_POSITION_HISTORY_DELETE_PATHS = 0`

Release visibility: the reset button lives in the Demo card. The card is not composed in Release (§13.2), so the reset is not reachable from the UI in Release. The method still exists at the lower layer, and it is non-destructive.

---

## 5. Legacy market rows: preservation proof

- Reset cannot delete, relabel, rewrite or replace a legacy `market_bars_1m` row. Reset makes no repository call (§4). Tests prove that a seeded legacy row is equal by value after demo activity plus reset (`reset demo status leaves seeded legacy market bars unchanged`), and that reset performs no durable write or delete (`reset demo status performs no durable write or delete`).
- Release demo generation cannot replace a legacy row. The card is absent, and the ViewModel rejects generator calls before any coroutine or repository call (`closed build gate leaves a same-minute legacy row unchanged`: zero inserts, row equal by value).
- Debug generators can still REPLACE a same-minute row when they are intentionally invoked (`open build gate replaces a same-minute row by design`). This is accepted only under the Debug quarantine policy (§13.3).
- Legacy rows remain readable by the read-only candle and history screens. In Debug builds the portfolio exposure also shows the persisted close, as a display value. None of these reads feeds execution (§9, §10).

---

## 6. Features, signals and journal

- The reset no longer deletes them. Tests: `reset demo status preserves features`, `reset demo status preserves signals`, `reset demo status preserves journal`, and `reset demo status issues no delete or clear on any table`. The last one checks zero delete and clear calls on every fake.
- Other production callers of broad destructive deletes: none (§7). Unrelated administrative clears are classified separately in §7.3 and are not treated as part of the demo reset.

---

## 7. Production delete surface (final tree, VERIFIED)

7.1 SQL deletes. Exactly five statements exist in production, all in `db/room/dao`: `FeatureDao.kt:40`, `JournalDao.kt:38`, `MarketBarDao.kt:45` (by symbol), `MarketBarDao.kt:49` (all rows) and `SignalDao.kt:43`. The Paper and position DAOs contain no DELETE. The Paper audit DAO is append-only by its own KDoc (`PaperOrderDryRunAuditDao.kt:12`). Paper reconciliation uses UPDATE only. No `@Delete` annotation exists anywhere in production.

7.2 Production callers of the retained DAO broad-delete methods: 0 (`PRODUCTION_CALLERS_OF_RETAINED_BROAD_DELETE_DAOS = 0`). No production code outside `db/room/dao` calls `clear()` or `deleteBySymbol()` on a DAO.

7.3 Other clear, reset and delete functions in production. Each was checked. None touches Room deletion.
- Credentials (encrypted preferences): `EncryptedPrefsAlpacaCredentialsStore.clear()`, `SecureAlpacaCredentialsStore.clear()`, `AlpacaTestStreamViewModel.clearCredentials()`.
- In-memory: `MarketTickBuffer.clear()`, `StreamHealthTracker.resetAttempts()`, `AlpacaTestStreamPipelineBridge.resetCounters()`, `PaperPositionEvidenceHttpTransport.clearAccountBinding()`.
- Watchlist (preferences, not Room): `WatchlistRepository.remove()`, `WatchlistRepository.resetToDefaults()`, `WatchlistViewModel.remove()`.
- UI preparation and selection state: `PaperManualSubmitViewModel.clearOrderStatusSelection()`, `resetForNewPreparation()`, `clearActivePreparation()`; `PaperOrderPreflightViewModel.resetForNewPreparation()`, `clearGuidedPreparationResult()`.
- Durable but non-destructive: `PaperManualSubmitViewModel.resetForNewPreparation()` calls `acknowledgeAllTerminalResets`. That runs an UPDATE on `PaperOrderReconciliationDao` (around line 85) that sets `resetAcknowledgedAtEpochMillis` on terminal rows. It is an acknowledgement flag, not a delete. It is unrelated to the demo reset and is unchanged by this phase.

---

## 8. DAO delete queries (follow-up technical debt)

- Retained: the five delete queries listed in §7.1, exposed as `MarketBarDao.clear()`, `MarketBarDao.deleteBySymbol()`, `FeatureDao.clear()`, `SignalDao.clear()` and `JournalDao.clear()`.
- Production callers: 0.
- Not removed in this phase, by owner decision. Removing them would mean editing the test fakes in 21 test files that override these methods. The phase does not touch those fixtures.
- Recorded as `FOLLOW_UP_TECH_DEBT = YES`. This is not a blocker.
- The KDoc of each DAO method says "Forbidden in production (3.a.1-D)".

---

## 9. LegacyDisplayPrice (display-only model, VERIFIED)

- `data class LegacyDisplayPrice(val price: Double)` in `data/market/price/LegacyDisplayPrice.kt`. Its only instance field is `price` (test: `carries only the price field`).
- Companion `LABEL = "LEGACY PRICE - NOT FOR EXECUTION"`. The exact text is asserted by `label is the approved text`.
- No provenance, source, freshness or trust field.
- No conversion to `ExecutionReferencePrice.Trusted`. `Trusted.derive` requires a `LiveQuoteObservation` (bid, ask, provenance, event time and receipt time). It is the only creator of `Trusted`, at `ExecutionReferencePrice.kt:103`, inside `derive` (line 67). The display type has no quote, no provenance and no timestamps, so no conversion exists.
- Not accepted by preflight. `PaperOrderPreflightEngine.preflight` takes `executionReference: ExecutionReferencePrice?`. No execution file references the display type (scan `DISPLAY_DOES_NOT_REENTER_EXECUTION`).
- Not accepted by the final gate. `PaperFinalPriceStabilityPolicy` and `PaperManualSubmitGate` do not reference the display type. The 3.a.1-C tests confirm that a legacy Room preview is blocked at the final gate (`legacy Room preview cannot pass even against a trusted final quote`; `a legacy Room preview with a trusted final quote is blocked, never allowed`).
- Not used for authoritative notional or buying power. The engine derives `priceUsed` only from the trusted reference for the same symbol (`PaperOrderPreflightEngine.kt`, lines 99–113). A missing, rejected or symbol-mismatched reference blocks with `NoTrustedExecutionPrice`.
- UI label: the exposure row renders `LegacyDisplayPrice.LABEL` (test: `the exposure row renders the label constant`).

---

## 10. Display reentry trace (VERIFIED)

Consumers of `PerSymbolPaperExposure.legacyDisplayClose` and of `PaperPortfolioRiskUiState`:
- `PaperPortfolioRiskViewModel.buildExposure` is the producer. `PaperPortfolioRiskUiState` carries the result.
- `OfflineDashboardScreen.kt` renders the exposure row (label and price). `VelaDashboardSections.kt` renders the summary count "Sin precio legado".
- `MainActivity.kt` constructs the risk ViewModel only when `BuildConfig.DEBUG` is true (the ternary at line 89).
- No preflight, submit, gate, executor, notional or buying-power consumer exists. The source scan finds no execution file that mentions `LegacyDisplayPrice`, `legacyDisplayClose` or `PaperPortfolioRiskUiState`.

Result: `DISPLAY_DOES_NOT_REENTER_EXECUTION = PASS`. `DISPLAY_EXECUTION_REENTRY_PATHS = 0`.

---

## 11. 3.a.1-C non-regression (VERIFIED)

Sensitive files, compared with HEAD (`git diff --stat e1840dc -- <files>` is empty for every file in this list): `ExecutionReferencePrice.kt`, `MarketPriceSnapshotProvider.kt`, `MarketPriceSnapshot.kt`, `MarketPriceFreshnessPolicy.kt`, `MarketDataProvenance.kt`, `MarketTick.kt`, `MarketTickBuffer.kt`, `AlpacaStockMarketDataClient.kt`, `AlpacaStreamEndpoint.kt`, `PaperOrderPreflightEngine.kt`, `PaperOrderPreflightResult.kt`, `PaperOrderPreflightViewModel.kt`, `PaperManualSubmitViewModel.kt`, `PaperFinalPriceStabilityPolicy.kt`, `PaperManualSubmitGate.kt`, `PaperManualSubmitExecutor.kt`, `PaperOrderSubmitModels.kt`, `AlpacaPaperSubmitEndpoint.kt`, `PaperManualOrderSubmitClient.kt`, `AlpacaPaperOrderSubmitHttpClient.kt`, `VelaLabApplication.kt`, `AlpacaTestStreamPipelineBridge.kt`, `OfflineMarketPipelineCoordinator.kt`.

Two files differ from HEAD. Both differences are intentional and neither changes execution:
- `MainActivity.kt`: one import and one constructor argument for the demo ViewModel (`demoGeneratorsEnabled = demoGeneratorsEnabledForThisBuild()`). The 3.a.1-C wiring lines are unchanged.
- `PaperPortfolioRiskViewModel.kt`: a display-only change. The 3.a.1-C document (§11) recorded this file as unchanged. Phase D changes it, as the owner requested, to harden the display of the persisted close. No execution input changes.

Semantic invariants (static reads plus the 3.a.1-C tests, all green in this run):
- Trusted creation is private and derive-only: `class Trusted private constructor(` at `ExecutionReferencePrice.kt:23`; the only construction is inside `derive` (line 67, construction at line 103).
- IEX trusted source unchanged: `IEX_STREAM_URL → ALPACA_IEX_REAL_TIME` (`AlpacaStockMarketDataClient.kt:76`).
- TEST and FAKEPACA rejected: `TEST_STREAM_URL → ALPACA_TEST_SYNTHETIC` (`AlpacaStockMarketDataClient.kt:77`). `derive` rejects any provenance other than IEX (`ExecutionReferencePrice.kt:78–79`).
- UNKNOWN rejected by the same equality check.
- Future timestamp rules unchanged: `FUTURE_TIMESTAMP` and `EVENT_AFTER_RECEIPT` (`ExecutionReferencePrice.kt`, around lines 139–143 and 171–172). The file is unchanged.
- Symbol binding unchanged: `SYMBOL_MISMATCH` in `ExecutionReferencePrice.kt` (line 149) and in the engine (line 105). Both files are unchanged.
- Final revalidation unchanged: `PaperManualSubmitGate.evaluate` (line 50) and `PaperManualSubmitExecutor` (line 36) are unchanged.
- Room fallback execution authority = 0: no market-bar read in the price, preflight, submit or submit-ViewModel code. The only `recent(` calls in that code are Paper audit repositories.

---

## 12. Synthetic authority (VERIFIED)

Inputs checked: Debug demo bars, the TEST feed (`ALPACA_TEST_SYNTHETIC`, symbol `FAKEPACA`, `TEST_STREAM_URL`), unknown provenance, and legacy Room rows.
- Debug demo: writes Room bars only. The demo ViewModel holds no `ExecutionReferencePrice`, no `MarketTickBuffer` and no `MarketPriceSnapshotProvider` (scan `DEMO_PATH_CANNOT_REACH_EXECUTION`).
- TEST feed and FAKEPACA: the TEST ViewModel and bridge are Debug-only. The only `pushQuote` caller in production is `AlpacaStockStreamViewModel.kt:87` (the IEX stock stream). Even if synthetic provenance reached `derive`, `derive` rejects it.
- Unknown: the default provenance is rejected by `derive`.
- Legacy Room: no execution path reads market bars (§11). The final gate rejects a legacy Room preview (3.a.1-C tests).

`SYNTHETIC_EXECUTION_AUTHORITY_PATHS = 0`.

---

## 13. Release and Debug boundaries

### 13.1 Generated build constants (VERIFIED, read from the generated `BuildConfig.java` of this phase's builds)
- Debug: `public static final boolean DEBUG = Boolean.parseBoolean("true");`
- Release: `public static final boolean DEBUG = false;`
- `MANUAL_PAPER_SUBMIT_COMPILED = false` in both variants.

### 13.2 Release (`BuildConfig.DEBUG = false`)
- Gate closed. `demoGeneratorsEnabledForThisBuild()` returns false in the release variant. `ReleaseDemoGeneratorGateTest` asserts `assertFalse(BuildConfig.DEBUG)` and `assertFalse(demoGeneratorsEnabledForThisBuild())`.
- Demo card absent. The Diagnostics section renders the Demo card, its status row and the reset button only inside `if (data.dashboard.demoGeneratorsAvailable)` (`VelaDashboardSections.kt:568`). The screen content renders the card only inside `if (state.demoGeneratorsAvailable)` (`OfflineDashboardScreen.kt:387`). `ControlsCard` has two call sites in production, and both are gated (grep, final tree).
- Lower-layer guard retained. `generateBtcUpdate()` (`OfflineDashboardViewModel.kt:51`) and `generateSpyUpdate()` (line 71) check the gate before `viewModelScope.launch`. A closed gate calls `rejectDemoGeneration()` (line 95), which sets a status message only. No coroutine, repository, DAO or coordinator call happens. Tests show zero insert calls on all four DAO fakes.
- No user-configurable flag. No preference, setting, endpoint or label reads the demo gate (scan `DEMO_GENERATOR_NO_RUNTIME_TOGGLE`). The gate is the only production read of `BuildConfig.DEBUG` for demo generation (grep).

### 13.3 Debug (`BuildConfig.DEBUG = true`)
- Gate open. The demo card is available (state `demoGeneratorsAvailable = true`, asserted by `DebugDemoGeneratorGateTest` in `testDebugUnitTest`). The generators run and write (`DEBUG_DEMO_GENERATOR_AVAILABLE`).
- Debug persistence remains the legacy operational table: `DEBUG_DEMO_PERSISTENCE_REMAINS_LEGACY_CONTAMINATING_BY_DESIGN`. Debug demo rows are never training eligible, never DatasetSnapshot eligible, never feature eligible, never execution authoritative, and never imported into a future market DB (import = 0). The whole table stays quarantined. Debug rows do not gain trustworthy provenance, and no provenance column is added.

---

## 14. Reset sequence counter

The reset keeps `sequenceCounter` monotonic instead of setting it to zero. The counter feeds `BootstrapMarketUpdate.sequence`, and the coordinator writes that value into the journal payload (`{"sequence":…}`, `OfflineMarketPipelineCoordinator.kt`). Resetting it to zero would make later journal sequence references ambiguous. The counter is in-memory and only orders demo updates causally. Decision: ACCEPTED. Restoring the zero reset is not needed.

---

## 15. Failure atomicity

The reset performs no durable write or delete. The test `reset demo status performs no durable write or delete` checks this across all DAO fakes, and `DemoResetScopeTest` checks that the reset body has no repository or DAO call. Result: `DEMO_RESET_DURABLE_ATOMICITY_RISK = NONE`. No transaction machinery was added.

---

## 16. Room

- Version 9. `VelaDatabase.kt` is unchanged. No `Migration9To10` exists (grep).
- The exported schema (`android/app/schemas`) is unchanged. Schema diff = 0.
- Entities are unchanged. No provenance column was added. The DAO changes are KDoc-only, apart from the reset-related wording.
- Legacy market migration = 0.

---

## 17. Network

`NETWORK_CONTRACT_CHANGED = NO`. `git diff` is empty for `data/market/source`, `data/paper/status`, the Alpaca endpoints and clients, and `data/pipeline`. The D and D.1 diff adds no `executeGet`, request builder, WebSocket, GET, POST, DELETE or PUT call. No REST, WebSocket, subscription, polling or retry behaviour changed.

---

## 18. Submit boundary

`SUBMIT_BOUNDARY_BEHAVIOR_CHANGED = NO`. `git diff` is empty for `data/paper/submit` and `PaperManualSubmitViewModel.kt`. Unchanged: the POST endpoint (`AlpacaPaperSubmitEndpoint.kt`), the body, the quantity, the token TTL and single use, the confirmation phrase, the arm and session semantics, retry, redirect, the REAL lock, the LIVE lock and Auto Paper.

---

## 19. Market Dataset absence

Absent, checked by git status and by grep: a separate market DB, a raw market evidence store, a canonical dataset, DatasetSnapshot, Parquet, features v2, labels, ML, training, and replay. 3.a.2 is NOT STARTED. The future market DB import is 0.

---

## 20. Historical fixture

`androidTest/.../PaperOrderHistoryMigrationTest.kt` is unchanged (`git diff` empty). It contains the historical order ID as SQL fixture data. The file predates this phase, is not production code, and makes no broker call. Not a blocker, and no action taken.

---

## 21. Test quality audit

| Case | Test (current name) | What it proves |
|---|---|---|
| A. reset preserves market bars | `OfflineDashboardViewModelTest` › `reset demo status preserves market bars` | Bar rows are equal before and after reset |
| B. reset preserves features | `… preserves features` | Feature rows are equal before and after reset |
| C. reset preserves signals | `… preserves signals` | Signal rows are equal before and after reset |
| D. reset preserves journal | `… preserves journal` | Journal rows are equal before and after reset |
| E. Release gate false, actual Release BuildConfig | `ReleaseDemoGeneratorGateTest` › `RELEASE_DEMO_GENERATOR_REACHABILITY_ZERO the release build flag is closed` | Asserts the real `BuildConfig.DEBUG` is false, and the gate is closed |
| F. Debug gate true, actual Debug BuildConfig | `DebugDemoGeneratorGateTest` › `DEBUG_DEMO_GENERATOR_AVAILABLE the debug build flag is open` | Asserts the real `BuildConfig.DEBUG` is true, and the gate is open |
| G. Release generator invocation causes zero persistence | `ReleaseDemoGeneratorGateTest` › `RELEASE_DEMO_MARKET_WRITE_PATHS_ZERO release generator calls persist no market bars` | Zero inserts on all four DAOs |
| H. Debug generator remains functional | `DebugDemoGeneratorGateTest` › `DEBUG_DEMO_GENERATOR_AVAILABLE the build-configured generators run and write both bars` | Two bars written |
| I. legacy display label | `LegacyDisplayPriceTest` › `label is the approved text`; `the exposure row renders the label constant` | Exact text, and the row uses the constant |
| J. legacy display price cannot enter execution | `LegacyDisplayPriceTest` source scans and reflection; `DemoGeneratorReleaseGateScanTest` › `DEMO_PATH_CANNOT_REACH_EXECUTION`; 3.a.1-C final-gate tests | No execution file references the type; the final gate blocks legacy previews |
| K. trusted C path unchanged | The 3.a.1-C suites in the targeted run, plus the empty `git diff` (§11) | Trusted behaviour is unchanged |
| L. production broad delete callers = 0 | `DemoResetScopeTest` › `no production caller outside the DAO layer issues a broad … delete` | Source scan of production code |

Quality notes:
- Each gate test asserts the real generated constant of its own variant. The test name alone is not the evidence.
- Each source scan asserts that its anchor (a file, or the text it depends on) exists before it checks absence or ordering, so no scan can pass vacuously.
- Lower-layer behaviour has executable tests. Card visibility is proven by source scans (call-site gates) and by state-level variant tests, because Compose is not unit-rendered in this project. The owner allowed this for card visibility.

---

## 22. Verification (final tree, this audit)

### 22.1 Fresh unit suites (`--rerun-tasks`)

| Variant | Command | Suites | Tests | Failures | Errors | Skipped | Exit |
|---|---|---|---|---|---|---|---|
| Debug | `:app:testDebugUnitTest --rerun-tasks` | 116 | 2177 | 0 | 0 | 0 | 0 |
| Release | `:app:testReleaseUnitTest --rerun-tasks` | 116 | 2177 | 0 | 0 | 0 | 0 |

Required minimums: at least 2171 for Debug and at least 2172 for Release. Both are met. The two counts are equal because each variant carries one three-test gate class (`DebugDemoGeneratorGateTest` and `ReleaseDemoGeneratorGateTest`).

### 22.2 Targeted run (`--tests` filters, both variants)

| Variant | Suites | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| Debug | 41 | 1233 | 0 | 0 | 0 |
| Release | 41 | 1233 | 0 | 0 | 0 |

Filters in both variants: `data.market.price.*`, `data.market.tick.*`, `AlpacaStockMarketDataClientTest`, `AlpacaStockQuoteEmissionTest`, `data.paper.preflight.*`, `data.paper.submit.*`, `data.repository.*`, `safety.*`, `OfflineDashboardViewModelTest`, `PaperPortfolioRiskViewModelTest`, `PaperOrderPreflightViewModelTest`, `PaperManualSubmitViewModelTest`. Each variant also runs its own gate class.

### 22.3 Required categories (all PASS, per variant)

| Category | Debug | Release | Tests |
|---|---|---|---|
| LEGACY_MARKET_ROWS_PRESERVED | PASS | PASS | `reset demo status preserves market bars`, `closed build gate leaves a same-minute legacy row unchanged`, `reset demo status leaves seeded legacy market bars unchanged`, `DemoResetScopeTest` |
| DEMO_RESET_SCOPE_NARROW | PASS | PASS | `reset demo status preserves features` / `signals` / `journal`, `reset demo status performs no durable write or delete`, `reset demo status issues no delete or clear on any table`, the repository reflection tests, `DemoResetScopeTest` |
| DISPLAY_PRICE_NON_AUTHORITATIVE | PASS | PASS | `LegacyDisplayPriceTest`: carries only the price field; no method returns `ExecutionReferencePrice`; label is the approved text; exposure row renders the label constant |
| DISPLAY_DOES_NOT_REENTER_EXECUTION | PASS | PASS | `LegacyDisplayPriceTest`: only display files reference the type; no execution file mentions it; no display file builds an execution reference in code |
| TRUSTED_EXECUTION_PATH_UNCHANGED | PASS | PASS | The 3.a.1-C suites in the targeted run, plus the empty `git diff` (§11) |
| RELEASE_DEMO_GENERATOR_REACHABILITY_ZERO | n/a | PASS | `RELEASE_DEMO_GENERATOR_REACHABILITY_ZERO the release build flag is closed`; `closed build gate reports demo generators unavailable`; `DEMO_GENERATOR_CONTROLS` call-site scans |
| RELEASE_DEMO_MARKET_WRITE_PATHS_ZERO | n/a | PASS | `RELEASE_DEMO_MARKET_WRITE_PATHS_ZERO release generator calls persist no market bars`; `closed build gate rejects demo generation without any write`; `RELEASE_MARKET_WRITE_PATHS_ZERO` scans |
| DEBUG_DEMO_GENERATOR_AVAILABLE | PASS | n/a | `DEBUG_DEMO_GENERATOR_AVAILABLE the debug build flag is open`; `… the build-configured generators run and write both bars`; `open build gate exposes the demo generators and writes a bar per update` |
| RELEASE_DEMO_CARD_HIDDEN | n/a | PASS | `RELEASE_DEMO_CARD_HIDDEN the build-configured state hides the Diagnostics demo card`; scans `RELEASE_DEMO_CARD_HIDDEN the diagnostics section …` and `… the screen content …` |
| DEBUG_DEMO_CARD_AVAILABLE | PASS | n/a | `DEBUG_DEMO_CARD_AVAILABLE the build-configured state shows the Diagnostics demo card` |

Card visibility is proven by source scans of the call sites plus state-level variant tests (see §21). Lower-layer behaviour is proven by executable tests.

### 22.4 Safety scan

`android/scripts/safety-scan.ps1`: `allowed_phase2v_submit=11 suspicious=0 forbidden=0`, equal to the baseline.

### 22.5 Lint (`lintDebug` and `lintRelease`, separate invocations)

| Variant | Exit | Errors | Warnings | Phase-touched or new findings |
|---|---|---|---|---|
| `:app:lintDebug` | 1 | 1: `NewApi` at `core/Symbols.kt:40` (pre-existing, file untouched) | 55 | 0 new. One pre-existing information item, `AutoboxingStateCreation` at `OfflineDashboardScreen.kt:877`: the same statement as HEAD line 874, shifted by the lines this phase added above it |
| `:app:lintRelease` | 1 | 1: the same `NewApi` | 42 | Same as Debug |

- New suppressions: 0. No `@Suppress`, `tools:ignore` or `@SuppressLint` in the diff or in the new files. The lint configuration is unchanged.
- Global lint is NOT green. Both variants exit non-zero on the baseline `NewApi`. That finding is recorded as a baseline failure, not claimed as a pass.

### 22.6 Diff check

`git diff --check` (tracked files): exit 0. The new files have no trailing whitespace and end with a newline.

### 22.7 Git state before commit

HEAD = origin/main = `e1840dc`. Staged files before the commit: none. The three showcase files stay untracked and unstaged.

---

## 23. Complete diff classification against `e1840dc`

Classes: A = reset safety; B = display-only price; C = Release demo gating; D = tests; E = documentation; F = unrelated (must be 0).

Tracked, modified (19):

| Path | Class |
|---|---|
| `android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt` | C |
| `android/app/src/main/kotlin/com/vela/android/lab/data/paper/PaperPortfolioModels.kt` | B |
| `android/app/src/main/kotlin/com/vela/android/lab/data/repository/FeatureRepository.kt` | A |
| `android/app/src/main/kotlin/com/vela/android/lab/data/repository/JournalRepository.kt` | A |
| `android/app/src/main/kotlin/com/vela/android/lab/data/repository/MarketDataRepository.kt` | A |
| `android/app/src/main/kotlin/com/vela/android/lab/data/repository/SignalRepository.kt` | A |
| `android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/FeatureDao.kt` | A |
| `android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/JournalDao.kt` | A |
| `android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/MarketBarDao.kt` | A |
| `android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/SignalDao.kt` | A |
| `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt` | A, B, C |
| `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardUiState.kt` | A, C |
| `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardViewModel.kt` | A, C |
| `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperPortfolioRiskViewModel.kt` | B |
| `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/VelaDashboardSections.kt` | A, C |
| `android/app/src/test/kotlin/com/vela/android/lab/data/repository/FeatureRepositoryTest.kt` | D |
| `android/app/src/test/kotlin/com/vela/android/lab/data/repository/MarketDataRepositoryTest.kt` | D |
| `android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardViewModelTest.kt` | D |
| `android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/PaperPortfolioRiskViewModelTest.kt` | D |

Untracked, new (9):

| Path | Class |
|---|---|
| `android/app/src/main/kotlin/com/vela/android/lab/data/market/price/LegacyDisplayPrice.kt` | B |
| `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/DemoGeneratorGate.kt` | C |
| `android/app/src/test/kotlin/com/vela/android/lab/data/market/price/LegacyDisplayPriceTest.kt` | D, B |
| `android/app/src/test/kotlin/com/vela/android/lab/safety/DemoResetScopeTest.kt` | D, A |
| `android/app/src/test/kotlin/com/vela/android/lab/safety/DemoGeneratorReleaseGateScanTest.kt` | D, C |
| `android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/OfflineDemoTestDoubles.kt` | D |
| `android/app/src/testDebug/kotlin/com/vela/android/lab/ui/dashboard/DebugDemoGeneratorGateTest.kt` | D, C |
| `android/app/src/testRelease/kotlin/com/vela/android/lab/ui/dashboard/ReleaseDemoGeneratorGateTest.kt` | D, C |
| `docs/phase-3a1d-legacy-clear-display-hardening.md` | E |

Total: 28 paths. Class F (unrelated) = 0. Excluded: the three showcase files (user-owned, untracked, not part of this change).

---

## 24. Static final assertions (final tree)

| Assertion | Value | Evidence |
|---|---|---|
| DEMO_RESET_BROAD_MARKET_DELETE_PATHS | 0 | §4, §7.2 |
| DEMO_RESET_PAPER_HISTORY_DELETE_PATHS | 0 | §7.1 |
| DEMO_RESET_POSITION_HISTORY_DELETE_PATHS | 0 | §7.1 |
| PRODUCTION_CALLERS_OF_RETAINED_BROAD_DELETE_DAOS | 0 | §7.2 |
| RELEASE_DEMO_GENERATOR_REACHABILITY | 0 | §13.2 |
| RELEASE_DEMO_MARKET_WRITE_PATHS | 0 | §13.2, §12 (coordinator writers: the gated demo, and the Debug-only bridge) |
| RELEASE_LEGACY_ROW_MUTATION_BY_DEMO | 0 | §5 (lower-layer test: zero inserts, row equal) |
| RELEASE_DEMO_CARD_VISIBLE | NO | §13.2 (two gated call sites) |
| DEBUG_DEMO_CARD_AVAILABLE | YES | §13.3 (variant test) |
| DEBUG_DEMO_GENERATOR_AVAILABLE | YES | §13.3 (variant test) |
| EXECUTION_AUTHORITATIVE_LEGACY_ROOM_CONSUMERS | 0 | §11 |
| SYNTHETIC_EXECUTION_AUTHORITY_PATHS | 0 | §12 |
| DISPLAY_EXECUTION_REENTRY_PATHS | 0 | §10 |
| DEMO_RESET_DURABLE_ATOMICITY_RISK | NONE | §15 |
| NETWORK_CONTRACT_CHANGED | NO | §17 |
| SUBMIT_BOUNDARY_BEHAVIOR_CHANGED | NO | §18 |
| Room version, Migration9To10, schema diff, legacy market migration, provenance columns | 9, NO, 0, 0, none | §16 |

---

## 25. Debt, residuals and limits

1. DAO delete queries retained (five), with production callers 0. `FOLLOW_UP_TECH_DEBT = YES`. Not a blocker (§8).
2. Debug demo persistence can REPLACE same-minute legacy rows, by design. The legacy table is quarantined as a whole. Not a Release path (§13.3).
3. Separate demo storage: DEFERRED / NOT REQUIRED FOR 3.a.1-D. The owner decided against it.
4. Compose is not unit-rendered in this project. Card visibility is proven by call-site scans and state-level variant tests. No device or emulator run was performed, as required.
5. Global lint is red on a pre-existing `NewApi` in `core/Symbols.kt`, which this phase does not touch (§22.5).
6. The demo status and the rejection status are in memory and are lost on process death. They are informational only.
7. The preflight ViewModel reads the latest persisted signal state (`PaperOrderPreflightViewModel.kt`, around line 605). It feeds only the `NoLocalSignal` warning and `relatedSignalState`. That read predates this phase, and it is unchanged. It reaches no price, notional, buying-power, block or submit decision.
8. Correction to the earlier phase text: `clear(symbol)` had no production caller at baseline (§3).

---

## 26. 3.a.1-C-R1 status

`3.a.1-C-R1 = DEVICE_VALIDATION_DEFERRED_SAFE_UI_PATH_UNAVAILABLE`. This is NOT a PASS.

The safe UI path to the Paper gate is unavailable. The only path performs Paper GETs (or AuthMissing without credentials), which conflicts with the no-broker-GET rule. The arm path requires the compile flag. The owner has deferred device validation. Evidence accepted for progression, from 3.a.1-C: Debug and Release 2140 PASS, targeted 141 PASS, safety 11/0/0, and static counts `EXECUTION_AUTHORITATIVE_LEGACY_ROOM_CONSUMERS = 0` and `SYNTHETIC_EXECUTION_AUTHORITY_PATHS = 0`.

---

## 27. Handoff (3.a.2 NOT STARTED)

- Every existing `market_bars_1m` row is LEGACY_UNKNOWN_PROVENANCE, because the table has no provenance column. Debug demo rows, and the Debug-period rows written by the TEST and IEX bridges, sit in the same table. A dataset must not read the table without a new provenance design in a later phase.
- Features, signals and journal keep mixed provenance. A dataset must filter by source before use.
- Separate demo storage is deferred (§25). Revisit only if a dataset needs Debug-period rows.

---

## 28. Verdict

`READY_3A1D_TO_PUBLISH` (internal, before staging). Every audit in §§2–24 passes. Lint is recorded as a baseline failure, not a pass (§22.5). The publication commit, its push and the final verification are recorded in the publication report.
