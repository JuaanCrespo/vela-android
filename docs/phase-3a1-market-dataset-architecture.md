# Phase 3.a.1 — Market dataset architecture audit

Status: architecture audit and design. No implementation, no runtime, no network,
no ML. Baseline: `f83f9e5d58b6aaa434f0eb147e07755713855f82` (Phase 2.y closure).
This document is documentation only. It is not committed or pushed by this phase.

Evidence sources: production source at the baseline, existing unit tests, and the
read-only inventory artifact `phase-2y5d-resume-20261003/inventory-pre-v8.json`. The
device was not accessed. Provider (Alpaca) documentation was not consulted; every
provider-semantics assumption is listed in section 38.

## 1. Verdict summary

- The market stack is small and well bounded. Two Alpaca WebSocket feeds are wired:
  the synthetic FAKEPACA test feed and the real IEX stock feed (default SPY). Plus
  offline demo generators. No REST market-data client, no crypto, no FX, no trades.
- The current persistence layer cannot serve as ML ground truth. It stores one
  per-minute bar per symbol with no source, no feed, no finality, no revision history,
  and it mixes synthetic and real data under the same keys.
- The dataset must be a separate, append-only, source-tagged, versioned store, outside
  the operational Room database.
- Recommendation: `USE_SEPARATE_MARKET_DATABASE`. Operational Room stays at v9 for 3.a.
- Architecture is resolved for 3.a.2 onward. Provider-semantic items in section 38
  must be verified before the corresponding implementation block, but they do not block
  the design.

## 2. Existing market stack (production files)

Sources and clients (package `data/market/source`):
- `MarketDataSource.kt`: enum of sources (OFFLINE, OFFLINE_STUB, ALPACA_TEST_STREAM,
  ALPACA_STOCK_IEX, ALPACA_PAPER).
- `alpaca/AlpacaStreamEndpoint.kt`: allowlist of WebSocket URLs. Allowed: test stream
  and IEX. SIP and delayed_sip are named but rejected. Trading hosts and trading paths
  are rejected.
- `alpaca/AlpacaStreamMessage.kt`: sealed model of parsed messages.
- `alpaca/AlpacaStreamMessageParser.kt`: JSON-to-model parser (see section 4).
- `alpaca/AlpacaStockMarketDataClient.kt`: IEX client. Subscribes `bars` and `quotes`
  only. Emits `BootstrapMarketUpdate` for bars and `MarketTick` for quotes.
- `alpaca/AlpacaTestStreamMarketDataClient.kt`: test-feed client, same shape,
  source label `alpaca-test-stream`.
- `alpaca/AlpacaWebSocket.kt`, `OkHttpAlpacaWebSocketFactory.kt`: transport.
- `alpaca/*Credentials*`: credential providers (secure store, BuildConfig fallback).
- `StreamHealth.kt`, `StreamHealthTracker.kt`, `MarketDataConnectionStatus.kt`: in-memory
  connection and health state (not durable).
- `StubPaperMarketDataClient.kt`: deterministic offline stub. Emits synthetic
  updates with source `offline-stub`. Not wired into the application.

Domain and aggregation (package `data/market`):
- `BootstrapMarketUpdate.kt`: input shape to the aggregator. Default source
  `bootstrap-simulated`.
- `OneMinuteBarAggregator.kt`: in-memory 1-minute aggregator, max 128 bars per symbol.
- `OneMinuteBar.kt`: bar domain model.
- `FeatureEngine.kt`, `SymbolFeatures.kt`: derived features per minute.
- `SignalEngine.kt`, `SymbolSignal.kt`, `SignalState.kt`: derived signals per minute.

Tick buffer and price snapshot (packages `data/market/tick` and `data/market/price`):
- `MarketTick.kt`: quote tick model (bid, ask, market timestamp in millis, receipt
  millis from the device clock, source label).
- `MarketTickBuffer.kt`: in-memory ring, 100 ticks per symbol, 1000 total. Counts
  overflow drops. Not persisted.
- `MarketPriceSnapshotProvider.kt`: resolves a price for the Paper execution path
  (see section 6.4).

Pipeline and persistence (packages `data/pipeline`, `data/repository`, `db/room`):
- `OfflineMarketPipelineCoordinator.kt`: per update, runs aggregator, then
  `persistBar`, then features, then signal, then journal. Each stage uses upsert.
- `AlpacaTestStreamPipelineBridge.kt`: forwards client `updates` into the coordinator.
- `MarketDataRepository.kt`, `MarketBarDao.kt`: `market_bars_1m`.
- `FeatureRepository`, `SignalRepository`, `JournalRepository`: derived and journal tables.
- Room entities: `MarketBar1mEntity` (`market_bars_1m`), `SymbolFeaturesEntity`
  (`symbol_features`), `SymbolSignalEntity` (`symbol_signals`), `JournalEventEntity`
  (`journal_events`). All inside `VelaDatabase` version 9.

Wiring (`VelaLabApplication.kt`): one shared `pipelineCoordinator`, used by both
`alpacaTestStreamPipelineBridge` and `alpacaStockPipelineBridge`.

Demo and UI:
- `ui/dashboard/OfflineDashboardViewModel.kt`: demo buttons generate deterministic
  `BTC/USD` and `SPY` ticks and call `coordinator.addUpdate`.
- `AlpacaTestStreamViewModel.kt`: subscribes `FAKEPACA` after the user taps the test
  button.
- `AlpacaStockStreamViewModel.kt`: subscribes `SPY` by default and shows quotes.
- `ui/candles/*`, `ui/dashboard/MarketHistoryViewModel.kt`: read Room bars for display.

Background execution: none. No WorkManager, no AlarmManager, no foreground service for
market data. Reconnection is not automatic: the client records `reconnectAttempts` only
for user-initiated retries.

Existing market and feature tests (reusable, section 34): `OneMinuteBarAggregatorTest`,
`MarketTickBufferTest`, `AlpacaStreamMessageParserTest`, `AlpacaStockQuoteEmissionTest`,
`AlpacaStockMultiSymbolSubscribeTest`, `AlpacaStopSemanticsTest`,
`AlpacaTestStreamClientContractTest`, `AlpacaTestStreamPipelineBridgeTest`,
`OfflineMarketPipelineCoordinatorTest`, `MarketDataRepositoryTest`,
`MarketPriceSnapshotProviderTest`, `StreamHealthTrackerTest`, `FeatureEngineTest`,
`SignalEngineTest`.

## 3. Network sources

- Test feed (`wss://stream.data.alpaca.markets/v2/test`): synthetic symbol FAKEPACA.
  Source label `alpaca-test-stream`. Subscribes bars and quotes. Must never be used as
  training data (section 13).
- IEX feed (`wss://stream.data.alpaca.markets/v2/iex`): real US equity quotes and bars
  for subscribed symbols. Default subscription SPY. Source label `alpaca-iex-stream`.
  Subscribes bars and quotes. Authentication via an `auth` action over the socket.
- SIP and delayed_sip: named in code, rejected by the endpoint guard. Not used.
- Paper REST (`paper-api.alpaca.markets`): account, positions, orders, order status,
  and a clock read (`fetchClock` on the read-only client, called by the Paper account,
  preflight, manual submit and portfolio-risk view models). Not market data. Must not
  feed the dataset (section 46), and the dataset must not use it as a time source.
- Demo generators: no network. Synthetic BTC/USD and SPY ticks, source label
  `bootstrap-simulated`, generated by the dashboard view model.
- Offline stub: no network, not wired.
- Historical bars over REST: not implemented. No `/v2/stocks/.../bars` or snapshot client
  exists.
- Crypto and FX feeds: not implemented.

Rate and subscription limits: none encoded in code beyond the single socket per client.

## 4. Message types: parsed and dropped

Parsed by `AlpacaStreamMessageParser`:
- `success` with `connected` or `authenticated`.
- `subscription` (trades, quotes, bars arrays). Used only for health.
- `q` (quote): reads `S`, `bp`, `ap`, `t`.
- `b` (bar): reads `S`, `o`, `h`, `l`, `c`, `v`, `t`.
- `error`: `code`, `msg`.
- Anything else: `Unknown(tag)`. The payload is discarded. Only the tag survives.

Not parsed, therefore lost at the parser:
- `t` (trade). Not subscribed, so not received. If received, only the tag survives.
- `u` (updated bar), `d` (daily bar), `s` (status), correction and cancel messages:
  not recognised, tag only.
- Quote fields `bs`, `as`, `bx`, `ax`, `c` (conditions), `z` (tape): not read.
- Bar fields `n` (trade count) and `vw` (VWAP): not read.
- Any sequence or ID field: not read (none exists in the parsed subset).

Parser behaviour: invalid JSON returns an empty list silently. Non-object elements are
skipped silently. A quote or bar without a parseable `t` becomes `Unknown(raw)`, and
the raw text is kept only in memory and never persisted.

## 5. Current data-loss map

Quote path (`q`):
- Arrives: symbol, bid price, ask price, bid size, ask size, bid exchange, ask exchange,
  conditions, tape, timestamp with nanosecond precision.
- Survives parsing: symbol, bid, ask, timestamp.
- Price precision: bid and ask are converted to `Double` at parse time.
- Timestamp precision: `MarketTick.marketTimestampMillis` is truncated to milliseconds.
- Sizes, exchanges, conditions, tape: lost.
- Persistence: none. Quotes live only in the 100-per-symbol in-memory ring. They reach
  the UI and the Paper price path, nothing else.
- Overflow: the quote flow uses `tryEmit` with a buffer of 256. When full, the drop is
  silent. No counter is kept.

Bar path (`b`):
- Arrives: symbol, open, high, low, close, volume, timestamp (nanoseconds), trade count,
  VWAP.
- Survives parsing: symbol, OHLCV, timestamp. Trade count and VWAP are lost.
- Price and volume precision: `Double` at parse time.
- Aggregation: `OneMinuteBarAggregator` re-buckets to the minute. It keeps open, max
  high, min low, last close, and the last volume when a volume is present. When it is
  absent, the volume is a synthetic `+1` per update, and the field is named
  `syntheticVolume`.
- Persistence: `market_bars_1m` by upsert on `(symbol, bucketStart)`. Source is not
  stored. The bar is rewritten on every update within its minute, so revisions are
  overwritten and history is lost.
- `lastUpdateTime` is stored in millis.
- Overflow: the bar flow uses `tryEmit` with a buffer of 64. Silent drop.

Subscription and connection:
- Subscription acknowledgements: used only for health counters.
- Connection transitions and errors: in-memory only (`StreamHealth`,
  `MarketDataConnectionStatus`). Not durable. Gaps cannot be explained after the fact.
- Reconnect history: only a user-retry counter in memory.

Sequence: `sequenceCounter` is an in-memory `AtomicInteger` restarting at zero for each
session. It is not durable and not unique across sessions.

Receipt time: `receivedAtMillis` uses the device wall clock and is only in memory.

Journal: `journal_events` payloads embed price as `Double` text (for example
`{"price":...}`) and sequence numbers. These are operational breadcrumbs, not dataset
truth.

Demo updates: timestamps come from the device clock (`clock()`), not from a provider.

## 6. Demonstrated contamination and provenance defects

These are the most important findings. They come from code and from the existing
inventory artifact.

6.1 Mixed sources in one table. Test-feed bars (`FAKEPACA`), IEX bars (`SPY`) and demo
bars (`BTC/USD`, `SPY`) all reach `market_bars_1m` through the same coordinator. The
table has no source column. The existing inventory `inventory-pre-v8.json` (2026-10-02)
shows `market_bars_1m` = 434 rows: 380 `SPY` and 54 `FAKEPACA`. No `BTC/USD` row
appears in that snapshot. Demo ticks are not distinguishable from real IEX ticks for the
same symbol.

6.2 Same key, different truth. `SPY` demo ticks and `SPY` IEX bars share the unique key
`(symbol, bucketStart)`. REPLACE means whichever wrote last wins. Real market data can be
overwritten by synthetic data in the same minute.

6.3 Destructive demo clear. `OfflineDashboardViewModel.clearDemoState()` calls
`marketDataRepository.clearAll()` (deletes every bar, including real IEX bars),
`featureRepository.clear()`, `signalRepository.clear()` and `journalRepository.clear()`.
In the dataset architecture the dataset store must be unreachable from any clear action.
This `clearAll` path is a recorded risk that must be isolated before the Learning Dataset
is connected. No code change is made in phase 3.a.1.

6.4 Execution reads the same bars. `MarketPriceSnapshotProvider` (tier 2) returns the
last Room bar close when no live quote is available. The Paper manual submit executor
uses this provider as `finalPriceSnapshotProvider`. A demo `SPY` bar could therefore
feed an execution-time price. This existing coupling is outside 3.a's implementation
scope, but the dataset must not widen it. Recommendation: in a later safety phase,
tier 2 should be restricted to source-tagged real bars, or removed. This document does
not change execution code.

6.5 Test feed in the production table. `FAKEPACA` is documented as synthetic yet is
persisted with the same schema and reaches the same features and signals. It must be
excluded from any training dataset by source, not by symbol name.

## 7. Raw evidence versus canonical dataset

Two conceptual layers, both append-only at the storage level:

A. Raw market evidence. One row per received, parsed provider frame. Preserves every
field the provider sent that matters for reproducibility: numeric values as decimal
lexemes (not `Double`), exact provider timestamp (nanosecond UTC), receipt time, source,
feed, session, ingestion sequence, and a content fingerprint. Malformed or unknown
frames go to quarantine (section 31), not to the raw table.

B. Canonical market dataset. Deterministic, versioned transformation of raw evidence
into consumable rows: canonical 1-minute bars with finality and revision metadata,
quote-level views, and quality windows. Canonical rows always reference the raw rows
they came from. Canonicalization is rebuildable from raw evidence plus a canonicalization
version.

The UI models (`OneMinuteBar`, `MarketTick`, `PerSymbolTickStats`) are not the source of
truth for the dataset.

## 8. Persistence philosophy and trade-offs

Options:
- Raw events only: maximal reproducibility; large volume; bars must be rebuilt at
  every read.
- Canonical bars only: small and simple; cannot reproduce corrections or quote-level
  microstructure; loses provider evidence.
- Raw plus canonical: reproducible and efficient for reads, with some duplication of
  bar information.
- Multi-layer with on-demand resampling: raw plus canonical 1m, with 5m, 15m, 1h and 1d
  derived deterministically at read time, not stored.

Decision: raw plus canonical 1m, with derived timeframes resampled on read or cached
as versioned derived artifacts. Raw is not duplicated as JSON text; raw keeps the
lexeme-preserving fields plus a fingerprint, which is sufficient to recompute canonical
rows.

Duplication is limited to the bar summary and is justified by query speed and the
provider-finality separation in section 16.

## 9. Time model

- Storage time base: UTC. Canonical storage uses epoch nanoseconds (`Long`, valid until
  2262) for provider event time, and epoch milliseconds for receipt and persistence time
  where precision is not provided.
- Provider event time: the frame `t` field, parsed with `Instant.parse` (supports up to
  nanoseconds). The original RFC-3339 text is retained in raw evidence for audit.
- Bar boundaries: `[start, end)` where `start` is the bar's provider timestamp, after
  verification (section 38). Until verified, the aggregator convention is used
  (truncation to the minute).
- Receipt time: device wall clock at parse time. Labelled `receivedAt`. Used only for
  latency, freshness and availability decisions, never as the primary ordering key.
- Persistence time: device wall clock when the batch commits.
- Aggregation interval: explicit in each canonical row.
- Trading or session date: derived from the session calendar in section 14, not from the
  device time zone.
- Local timezone (including Argentina): presentation only, never storage.
- Precision required: provider frames carry nanoseconds. Store nanoseconds. Do not
  discard provider precision.

## 10. Clock trust

- Provider event time is the ordering key whenever it exists.
- The device clock is untrusted for ordering. It is used for receipt latency, gap
  diagnostics and availability.
- Each canonical row stores `receivedAt`. Skew is a derived diagnostic:
  `receivedAt - eventTime`, which can be negative. Negative values are stored, not
  clamped.
- Clock skew affects: latency reporting (honest), gap classification (a gap cannot be
  classified by device time alone), and availability (training cut uses `receivedAt`,
  which depends on the device clock. A clock regression therefore marks the affected
  window `CLOCK_REGRESSION` instead of silently reordering).
- No manipulation of the system clock. No NTP or time-sync writes.

## 11. Exact numeric representation

Field by field:

- Price (bid, ask, OHLC, trade price): decimal text in raw; canonical decimal with
  scale preserved (same discipline as `DecimalQuantity`). Never `Double` in persisted
  training truth. Floating-point is allowed only in derived features and must be
  versioned.
- Size and quantity (bid size, ask size, trade size): decimal text. Integer shares on
  IEX are expected but not assumed; the representation must allow fractional sizes.
- Volume: decimal text. IEX volume is IEX-only volume, not consolidated (section 38).
- VWAP: decimal text. Derived or provided: recorded with a source flag.
- Spread and mid: derived; canonical text computed by exact decimal arithmetic.
- Timestamps: integer nanoseconds, never text in canonical rows.

Lesson from Phase 2.y: a `Double` projection can equate distinct decimal values
(`0.100000000000000001` vs `0.100000000000000002`). Training truth must not depend on
lossy equality.

The current `Double` storage in `market_bars_1m` and in journal payloads is acceptable
for operational display and is not a dataset primitive.

## 12. Instrument identity

Do not use raw ticker text as the permanent identity.

Conceptual key:
- `assetClass`: EQUITY, ETF, CRYPTO, FX, SYNTHETIC. Only EQUITY is implemented; the
  others are reserved.
- `provider`: ALPACA, and in the future others.
- `feed`: IEX, SIP, TEST_SYNTHETIC, and in the future others.
- `canonicalSymbol`: normalized uppercase form, using the existing `normalizeMarketSymbol`
  rules (`BASE/QUOTE` for crypto pairs).
- `providerSymbol`: the exact symbol string received.
- `instrumentId`: a stable surrogate assigned by the dataset, not derived from the symbol
  text.

Identity rule: the same ticker from two feeds (for example `SPY` from IEX and `SPY` from
SIP) is two distinct series. They must never be merged silently. Source and feed are part
of provenance.

`FAKEPACA` is `assetClass=SYNTHETIC`, `feed=TEST_SYNTHETIC`. Demo `BTC/USD` and demo
`SPY` are `source=DEMO_SIMULATED` and excluded from training.

## 13. Asset classes: implemented versus future

Implemented:
- US equity, IEX feed, real bars and quotes, subscribed symbols.
- Synthetic test feed (FAKEPACA).
- Offline demo generators (BTC/USD, SPY), synthetic.

Not implemented (state explicitly; do not invent feeds):
- ETFs as a separate class (ETFs trade through the same equity feed, but no class
  distinction exists).
- Crypto market data. No crypto stream URL or client. The normalization helper knows
  crypto base and quote names only.
- FX and dollar-related markets. NOT CURRENTLY IMPLEMENTED.
- SIP and delayed SIP feeds. Named, deliberately rejected by the endpoint guard.
- Historical REST bars and snapshots. NOT CURRENTLY IMPLEMENTED.
- Trades stream. Not subscribed.
- Corporate actions, splits, dividends, symbol changes. NOT CURRENTLY IMPLEMENTED.

Extension point: `assetClass` and `feed` are enum-like, versioned fields in the
instrument key. Adding a feed adds a source value and a parser adapter, not a schema
rewrite.

## 14. Market session and calendar

Equity sessions (conceptual, provider-dependent): PRE_MARKET, REGULAR, AFTER_HOURS,
CLOSED. The application has no market calendar. The Paper clock read exists but is not
used by the dataset. The session model is therefore:

- A `MarketCalendar` abstraction with a versioned data source (a static, dated table or
  an import), not device time.
- Each canonical bar carries `session` and `tradingDate`.
- Crypto uses 24/7 semantics. The dataset must not assume shared equity hours.

No implementation in 3.a.1. The calendar is required before quality scoring can decide
that an empty minute is `MARKET_CLOSED` rather than `UNEXPLAINED_GAP` (section 19).

## 15. Granularity and bar semantics

Verified current granularity: 1-minute bars, aggregated locally from provider bar
messages (the aggregator buckets provider bar timestamps to the minute).

Layers:
- Raw quotes: event-level, highest volume.
- Raw provider bars (`b`, and `u` when parsed): as received.
- Canonical 1-minute bars: the primary bar dataset.
- 5m, 15m, 1h, 1d: derived deterministically from canonical 1m on demand or as
  versioned artifacts. Not persisted redundantly by default.
- Sub-minute bars: not justified by current provider data. Add only if a 1-second or
  finer provider series is subscribed.

Canonical bar contract:
- `instrumentId`, `interval` (`1m`), `barStart`, `barEnd` (`barStart + interval`),
  interval convention `[barStart, barEnd)`.
- `open`, `high`, `low`, `close` (decimal text).
- `volume` (decimal text) and `volumeSource` (PROVIDER or ABSENT). `syntheticVolume`
  is removed from the training schema. Its current meaning (`+1` per update) is not a
  volume.
- `tradeCount` and `vwap` where the provider supplies them (not parsed today; add in
  3.a.2 with `NULL` when absent).
- `finality` (section 16), `revision` (integer, starts at 1), `sourceFrameCount`,
  `firstFrameSeq`, `lastFrameSeq` (references to raw rows).
- `canonicalizationVersion`, `datasetSchemaVersion`.

Ambiguous candle boundaries are not allowed. The convention is fixed in the schema.

## 16. Bar finality and revisions

The current code cannot tell provisional from final bars. It rewrites a bar on every
update, which overwrites history. The dataset must distinguish:

- `PROVISIONAL`: the bar's interval is still open, or the provider has not finalized it.
- `FINAL`: the interval closed and no provider revision has arrived within the revision
  grace window defined by the quality policy (versioned).
- `REVISED`: a later provider revision (for example an updated bar `u`) changed a
  previously stored value. The previous value stays in raw evidence and in a revision
  row.

Rules:
- Every canonical bar revision is a new row or a new revision record. No destructive
  overwrite of a previous revision.
- The "current" bar is a projection (latest revision), kept separately from history.
- Training uses only rows that were FINAL at the training cut (section 24).

## 17. Duplicates

Provider identifiers: none in the parsed subset. Alpaca quotes and bars, as parsed here,
have no unique message ID (section 38 to verify). Therefore:

- The raw identity is a compound fingerprint: SHA-256 over a canonical serialization of
  `feed | symbol | messageType | eventTime nanos | numeric fields as decimal text |
  conditions when present`, in fixed field order, UTF-8, no locale-dependent formatting.
- Two frames with identical fingerprints inside the same collector session and the same
  event time are duplicates. The second is not written as a new raw row. Its occurrence is
  counted in `duplicateCount` on the first row. The count is never dropped.
- Two legitimate identical quotes at the same nanosecond cannot be distinguished by
  content alone. This is a documented limitation: the dataset records a duplicate count
  instead of silently discarding the second frame. If the provider adds a sequence field
  later, the fingerprint is extended, with a new `parserVersion`.
- Never deduplicate by `symbol + timestamp` alone.
- Duplicate receipt must not double volume: canonical volume is recomputed from unique
  raw rows, or taken from the provider bar which already aggregates.

## 18. Out-of-order and late data

Behaviour:
- Out-of-order frames (event time earlier than the latest accepted event for the same
  instrument) are accepted into raw evidence with their receipt sequence. Canonical rows
  are rebuilt by event time, not by arrival order. The reorder is recorded as `OUT_OF_ORDER`
  in a diagnostic, never silent.
- Late frames (event belongs to a bar already marked FINAL): accepted into raw evidence,
  flagged `LATE_ARRIVAL`, and they produce a revision of that bar (section 16). The
  original FINAL row is not rewritten.
- Duplicate frames: section 17.
- Provider corrections: represented as revisions, never as edits.
- Quarantine: frames that cannot be validated (malformed, unknown structure, impossible
  values such as negative sizes or non-finite prices) are quarantined with a reason.

## 19. Gaps

Gap taxonomy (each minute or interval must carry exactly one state):
- `OBSERVED`: a bar exists for the interval.
- `MARKET_CLOSED`: the calendar says the market was closed. Requires the calendar.
- `NOT_SUBSCRIBED`: the symbol was not subscribed during the interval (from the
  collector session history).
- `DISCONNECTED`: the collector had no live connection (from connection events).
- `PROVIDER_NO_EVENT`: connected and subscribed, the provider sent no bar for the
  interval. This can be a real zero-activity period or a provider gap. It is not treated
  as zero volume.
- `COLLECTOR_STOPPED`: the collector session was stopped by the user or the process
  ended.
- `UNKNOWN_GAP`: none of the above can be proven.

Never fill a missing bar with synthetic zeros. A zero-volume bar is written only when the
provider sends one, or when a versioned derived-data policy explicitly defines it, and
the derived row is labelled as derived.

## 20. Data quality states

Row or window quality metadata, versioned with the quality policy:

- `COMPLETE`: all expected intervals observed, no revisions pending.
- `PARTIAL`: some intervals missing or uncertain.
- `GAP_BEFORE` and `GAP_AFTER`: a gap adjacent to the row.
- `LATE_DATA`: late arrivals changed the row.
- `REVISED`: a provider revision changed the row.
- `SOURCE_DISCONNECTED`: the source was disconnected during the window.
- `SYNTHETIC_SOURCE`: the source is a test or demo feed. Such rows are always excluded
  from training.
- `UNKNOWN`: insufficient evidence.

Future ML must be able to exclude or model poor-quality windows explicitly.

## 21. Subscription history and collector sessions

Dataset validity depends on what was intended to be collected. Absence of data is
ambiguous without subscription history.

`MarketCollectorSession` (conceptual durable record):
- `sessionId` (UUID generated at start)
- `feed`, `provider`, `source`
- `requestedSymbols` (ordered set, with a set digest)
- `startedAt`, `endedAt` (receipt time), `endReason`
- `appVersion`, `schemaVersion`, `parserVersion`, `canonicalizationVersion`
- `subscriptionChanges` (time-stamped, with reason)
- No credentials, no tokens, no account identifiers.

## 22. Connection events

Durable collector events, one row per transition:

- `CONNECTING`, `CONNECTED`, `AUTHENTICATED`, `SUBSCRIBED`, `DISCONNECTED`,
  `RECONNECTING`, `STOPPED`, `ERROR`, each with receipt time, session id, and a safe reason
  (error category, not the raw message).

These events explain gaps (section 19). Today they exist only in memory and are lost on
process death.

## 23. Ingestion identity

Every raw and canonical row has:
- `rawRowId` or `canonicalRowId`: a stable identity for dedupe, audit, replay and
  slicing. Chosen as a UUID v7 or a deterministic `(sessionId, ingestSequence)` pair.
  Prefer the pair for replay determinism; UUID is used only for cross-system references.
- `fingerprint` (section 17).
- `sessionId`.
- `ingestSequence`.

## 24. Durable ingestion sequence

- `ingestSequence` is a monotonic counter per collector session, persisted with each
  row. It records the causal order of receipt and persistence within the session.
- It is not provider event time and never replaces it.
- Global order across sessions is `(sessionStartedAt, sessionId, ingestSequence)` and is
  documented as such.
- Do not use the SQLite `rowid` as protocol truth. It can change under VACUUM and is not a
  contract.
- The current in-memory `sequenceCounter` is replaced by this durable sequence.

## 25. Storage engine decision

Options assessed:
- Inside operational Room (version 10): simplest wiring, but the database holds Paper
  evidence. High-volume quote rows would bloat the operational database, slow its
  backups, and complicate migrations of trading evidence. The demo clear function sits
  next to it. Rejected for the dataset.
- Separate Room or SQLite database: isolates dataset growth, backup, retention and schema
  evolution from trading evidence. The operational database stays at v9. Chosen.
- File and columnar storage (Parquet): best for export and training, poor for append
  transactions and crash consistency at high frequency. Used as the export format, not as
  the write path.
- Hybrid: write path in a separate SQLite database; export to Parquet partitions after
  verification; SQLite copy as a backup format.

Decision: `USE_SEPARATE_MARKET_DATABASE` with the hybrid export. Operational Room stays at
version 9 for 3.a. The existing `market_bars_1m`, `symbol_features`, `symbol_signals` and
`journal_events` stay as operational tables, classified as non-authoritative for ML and
excluded from the dataset.

## 26. Query and repository boundary

- A repository interface in the domain, for example `MarketDatasetReader`: `barsFor(
  instrumentId, interval, barStart, barEnd, snapshot)`, `eventsFor(instrumentId, start,
  end, snapshot)`, `qualityFor(...)`.
- Feature and training code depends on the domain interface, never on Room DAOs.
- No feature code imports the dataset DAO.

## 27. Dataset snapshot and versioning

`DatasetSnapshot` (logical, immutable cut):
- `snapshotId`
- `datasetSchemaVersion`
- `canonicalizationVersion`
- `qualityPolicyVersion`
- `feeds` and `instrumentIds`
- `timeRange` as `[start, end)` in UTC
- `highWaterIngestSequence` per session (or global high-water)
- `availabilityCutoff` (the instant after which rows are excluded)
- `contentDigest`: SHA-256 over the canonical serialization of the selected rows, in
  `(instrument, interval, barStart, revision)` order.

Logical immutability: raw evidence is append-only, so a snapshot that references rows up
to a high-water mark does not change when new rows are appended. A later revision of a
bar gets a new revision row, so the snapshot still refers to the revision it selected.
Physical copies are not required when logical immutability is proven by the digest.

Datasets versioned separately from: Room version, app version, model version, feature
version. A model records the `snapshotId` it trained on.

## 28. Parser and canonicalization versions

- `parserVersion` is stored on every raw row. A parser change never reinterprets old rows
  silently. Old rows keep their parser version.
- `canonicalizationVersion` is stored on every canonical row.
- A future rebuild computes canonical rows from raw evidence plus the chosen
  canonicalization version, deterministically. Two rebuilds with the same versions produce
  identical digests.

## 29. Training cut and availability

- Each row carries `eventTime` (provider) and `availableAt` (`receivedAt` of the frame that
  made the row final or visible).
- A training cut at time `T` includes rows with `availableAt <= T`, not rows with
  `eventTime <= T`. This is the no-lookahead rule.
- A late correction first known after `T` is excluded from the cut even if its
  `eventTime` is before `T`.
- The training cut for a model is therefore a pair `(T, snapshotId)`.

## 30. Feature and label leakage prevention (design requirements for 3.b–3.c)

- A feature at time `T` may use only rows with `availableAt <= T`.
- No future bar close may enter a feature at `T`.
- Labels use future returns but live in a separate namespace and are never joined into
  feature rows at the storage layer.
- Lineage (section 33) must be able to prove, for each feature row, the maximum
  `availableAt` among its inputs.
- Random row shuffle is not a valid default for this data. Splits must be chronological,
  walk-forward or regime-based, as required in 3.f.

## 31. Feature, label, prediction, outcome separation

Separate namespaces (storage level, not only conceptual):
- `market_*`: raw evidence, canonical bars, quality windows, collector sessions,
  connection events, quarantine. Phase 3.a.
- `features_*`: versioned feature rows. Phase 3.b.
- `labels_*`: versioned targets. Phase 3.c.
- `predictions_*`: model outputs with model and snapshot references. Phase 3.d onward.
- `outcomes_*`: realized outcomes from shadow or trading evaluation. Phase 3.j onward.

Not allowed in 3.a: RSI, MACD, moving averages, labels, win or loss, strategy identifiers,
predictions, any outcome. The existing `symbol_features` and `symbol_signals` are app
logic, not dataset truth.

## 32. Dataset is not trading history

Position and order reconciliation evidence (`paper_*` tables, fills, PnL, mismatches) is
not a raw market observation. The dataset never reads it. It may later become evaluation or
outcome context under separate schemas (3.j).

## 33. Data lineage

Every feature, prediction and outcome row must trace back to:
`snapshotId`, source raw row identities, canonicalization version, feature version, and
model version. Lineage hooks are fields on the rows, not a separate graph in 3.a.

## 34. Test strategy

Layers, to be implemented from 3.a.2:
- Unit: parser and canonicalization (lexeme preservation, nanosecond timestamps, rejection
  of non-finite and negative values, unknown frames to quarantine). Reuse
  `AlpacaStreamMessageParserTest` as the baseline.
- Property: ordering, dedupe fingerprints, idempotent canonicalization, decimal exactness.
- Repository: append-only behaviour, revision rows, snapshot digests. Reuse
  `MarketDataRepositoryTest` patterns.
- Migration: schema evolution of the separate dataset database.
- Replay: determinism across runs (same snapshot, same version, identical digest).
- Quality: gap taxonomy and quality states.
- Load: high-volume ingest with bounded memory.
- Offline: replay with network disabled.
- Safety: reflection and source-inspection tests that the dataset package has no
  execution, order, credentials or Paper-REST dependency (pattern from
  `PositionDomainIsolationTest`).

Existing tests that can be reused: `OneMinuteBarAggregatorTest`, `MarketTickBufferTest`,
`AlpacaStockQuoteEmissionTest`, `AlpacaStopSemanticsTest`,
`AlpacaTestStreamPipelineBridgeTest`, `OfflineMarketPipelineCoordinatorTest`,
`StreamHealthTrackerTest`, `MarketPriceSnapshotProviderTest`.

## 35. Crash consistency

- One batch = one transaction in the dataset database: raw rows, their quality
  metadata and the session high-water mark commit together.
- A crash before commit loses the in-flight batch but never leaves a partial batch
  visible. Idempotent re-ingestion is possible because fingerprints dedupe.
- A canonical bar is never committed without its source raw rows in the same transaction.
- The session high-water mark never advances past uncommitted rows.
- Volume is never double counted because canonical volume is computed from unique raw
  rows.

## 36. Backpressure and memory

- Today: `tryEmit` silently drops on overflow (bars 64, quotes 256). This is the current
  data-loss point and is unacceptable for a dataset.
- Target: bounded queue between the socket listener and the writer. Overflow policy is
  explicit and recorded: when the queue is full, the listener records a `QUEUE_OVERFLOW`
  quality event with a count and marks the window `PARTIAL`. Never a silent drop.
- Spill to disk is an option for later; not in 3.a.2.
- Writer batching: size-bounded and time-bounded (for example up to 500 rows or 250 ms,
  whichever comes first). Exact values are part of section 40 targets.
- Disconnect on sustained overload is allowed only with the disconnect event recorded.
- Memory: the in-memory aggregator (128 bars per symbol) and tick buffer (100 per symbol,
  1000 total) are display caches, not the dataset. The dataset must not depend on them.

## 37. Process death, reboot and background

- Current state: no background service exists for market data. Collection runs only while
  the app process is alive and a user has started it.
- Process death: the open session is closed on the next start with
  `endReason = PROCESS_DEATH_OR_UNKNOWN`. The gap after that point is `COLLECTOR_STOPPED`
  or `UNKNOWN_GAP`, not `PROVIDER_NO_EVENT`.
- Reboot: same as process death.
- No foreground or background trading service is introduced. Any future collector
  service requires a separate, explicit approval (phase request, item 54).

## 38. NOT VERIFIED — open provider-semantics items (not blockers)

Everything in this section is NOT VERIFIED. None of it is stated as fact elsewhere in
this document. These require provider documentation or a read-only sample, and must be
resolved before the matching implementation block:

- Meaning of bar `t`: bar start versus bar end. The aggregator assumes it can be truncated
  to a minute. Verify against provider documentation before 3.a.2 relies on it.
- Updated bar (`u`) semantics and whether revisions carry a sequence.
- Presence of any unique message ID or sequence in quote and bar frames.
- Precision of provider timestamps (documentation indicates nanoseconds; the parser
  accepts them; verify with a real sample).
- Meaning of IEX volume (IEX-only, not consolidated) and whether it is comparable across
  symbols.
- Quote size semantics (round lots versus shares) and whether sizes are fractional.
- Whether bars can be late after their interval closes, and the typical grace window.
- Rate limits and maximum subscription count for the IEX stream.

None of these changes the architecture. They change the parser adapter and the quality
policy values.

## 39. Capacity planning (assumptions stated; not measurements)

Assumptions:
- Equity regular session: 390 minutes per trading day, 252 trading days per year.
- Crypto (future): 1440 minutes per day, 365 days.
- Bytes per raw quote row: about 150 including indices (planning assumption).
- Bytes per canonical bar row: about 150 including indices (planning assumption).
- Quote rate per subscribed equity symbol: a planning value of 10 per second during the
  regular session. This is not a measured provider rate. Measure in 3.a.7 before any
  capacity claim.
- Compression: Parquet export is assumed to reduce size about 5 to 10 times. Planning only.

Canonical 1-minute bars (equity):
- Per symbol per year: 390 × 252 = 98,280 rows, about 15 MB.
- 10 symbols: about 0.98 million rows per year, about 150 MB.
- 100 symbols: about 9.8 million rows per year, about 1.5 GB.

Raw quotes (planning rate):
- Per symbol per regular session: 10 × 23,400 seconds = 234,000 rows per day.
- Per symbol per year: about 59 million rows, about 8.8 GB uncompressed.
- 10 symbols: about 88 GB per year uncompressed, roughly 9 to 18 GB per year as Parquet.
- 100 symbols: about 880 GB per year uncompressed, roughly 90 to 180 GB per year as Parquet.

24/7 crypto, if added later, multiplies bar rows by about 3.7 per symbol.

Conclusion: bars alone are small. Raw quotes drive storage. A large SSD can hold multi-year
10-symbol quotes. 100-symbol quotes need archival and compression from the start.

## 40. Performance targets (proposed; to measure in 3.a.7)

- Sustained ingest: at least 3× the planning rate (about 30 quotes per second per
  subscribed symbol), with zero silent drops.
- Batch commit latency: p95 at or below 100 ms for a 500-row batch on the target device.
- Listener-to-queue handoff: never blocks the socket thread.
- RAM: bounded queue and writer buffer, with an explicit upper limit and a measured peak.
- Disk: measured bytes per row and per day, compared to section 39.
- Quality: the overflow counter is zero during the validation run, or every overflow is
  recorded.

These are targets, not claims.

## 41. Observability

Operational metrics (no sensitive data):
- events received, persisted, duplicates, parse failures, quarantined, late, out-of-order
- queue depth and peak, batch size and commit latency
- collector uptime, last event time per instrument, gap count
- revision count, overflow count
- bytes written per day

Logs carry counters and categories only, never frame content, credentials or account data.

## 42. UI boundary

- The dataset collector is controlled from the UI (start, stop, status) but does not
  depend on the UI being visible.
- The UI observes status; it does not own the collector or the dataset.
- Screen lifecycle must not start or stop collection implicitly (no `LaunchedEffect`
  side effects).

## 43. Authentication and security

- The dataset never stores the Alpaca key, secret, authorization headers or tokens.
- The dataset never stores the Paper account identifier. Market data does not need it.
- Provider authentication stays inside the client, as it is today.
- Quarantine rows store a safe fingerprint and a reason, not full payloads containing
  unexpected fields (section 44).

## 44. Quarantine

Malformed or untrusted frames are not dropped silently:

- Stored in a `market_quarantine` table with: receipt time, session id, parser version,
  reason code, safe payload fingerprint, byte length, and the field names that failed.
- Full raw payload is stored only if it is provably free of credentials and the reason
  requires it. Default: no full payload.

## 45. Dataset quality report and training eligibility

`DatasetQualityReport` (versioned, produced per snapshot) contains:
- expected intervals, observed intervals, missing windows by reason
- duplicates, late events, revisions, overflow count
- disconnect duration per session
- parse failures and quarantine counts
- symbol coverage and source coverage
- synthetic-source rows excluded (count)

A snapshot is eligible for training only if the quality policy (versioned) passes. Manual
"looks fine" is not a sufficient criterion.

## 46. Repository and trading separation

Market data must not consume trading REST responses:
- Paper REST responses (account, positions, orders, order status, clock) never enter the
  market dataset.
- Market data does not depend on the execution package or the manual submit gate.
- The execution path currently reads a market price fallback from Room (section 6.4). That
  coupling must not grow. The dataset package must not be imported by execution code.

## 47. Python and export interoperability

- Canonical schema is language-neutral: documented as a schema definition (SQL DDL plus a
  JSON description) and exported as Parquet with explicit types. Decimal columns are
  exported as decimal or fixed-scale integers, not floats.
- Export formats:
  - Parquet: recommended for training (typed, columnar, compressed).
  - SQLite copy: recommended for backup and replay.
  - CSV: small debug extracts only, with explicit decimal text.
  - Excel: not a primary ML dataset format.
- No Kotlin-specific serialization as the only authoritative format.
- No Python code in 3.a.

## 48. Content hashes

- Raw row fingerprint: SHA-256 over canonical field serialization (section 17).
- Canonical bar digest: SHA-256 over canonical serialization of the bar fields.
- Snapshot digest: SHA-256 over the ordered canonical rows of the snapshot.
- Canonical serialization rules: fixed field order, UTF-8, decimal text as stored (no
  locale), integer nanoseconds, explicit null markers. No hashing of locale-dependent
  strings.
- Full byte hashing of every row is not required; the pragmatic level is fingerprint per
  raw row, digest per snapshot.

## 49. Dataset immutability

- Raw evidence: append-only.
- Canonical bars: append-only revisions. The current-state projection is a rebuildable
  view or table, never the only copy.
- Quality windows: versioned; a new policy creates new quality rows, it does not
  overwrite old ones.
- Corrections: explicit revisions. No destructive update of original evidence.

## 50. Retention

- Raw quotes: retained by default. Compression and archival after verification into
  Parquet partitions. Deletion of raw evidence is not automatic in v1 and requires a
  verified archive first.
- Canonical 1m bars: retained indefinitely (small, reproducible).
- Quarantine: retained with a configurable limit, defaulting to retention forever until a
  review process exists.
- Partitioning for Parquet export: by `assetClass`, `feed`, `symbol`, `date` and `interval`.
  For the SQLite write path: indexes on `(instrumentId, barStart)` and
  `(instrumentId, eventTime)`. No implementation in 3.a.1.

## 51. Failure-mode matrix

Each row gives the desired fail-safe behaviour:

- Network loss: close session with `DISCONNECTED` event; mark window; no synthetic data.
- Authentication failure: `ERROR` event with safe category; no dataset rows; no retry loop.
- Parse failure: quarantine with reason; continue the stream.
- Duplicate event: fingerprint dedupe; increment duplicate count.
- Out-of-order event: accept with `OUT_OF_ORDER` diagnostic; canonical rebuilt by event time.
- Disk full: fail closed; stop the writer; record `ERROR`; never drop rows silently.
- DB write failure: roll back the batch; retry bounded; then fail closed with an event.
- Process death: close open session on next start with a process-death reason.
- Schema mismatch on open: refuse to write; read-only diagnostics only; no migration without
  a versioned migration.
- Clock skew or regression: store raw timestamps; mark `CLOCK_REGRESSION`; do not reorder
  silently.
- Unknown symbol: accept only if subscribed; otherwise quarantine.
- Provider correction: revision row; no overwrite.
- Queue overflow: `QUEUE_OVERFLOW` quality event with count; window `PARTIAL`.

## 52. Test-first implementation sequence (proposed)

- 3.a.2: core dataset domain. Instrument key, time model, decimal types, raw event model,
  canonical bar model, quality states, fingerprint rules. Pure Kotlin, unit tests.
- 3.a.3: persistent market database. Separate Room database, schema v1, DAOs, migration
  test scaffolding, append-only and revision semantics, snapshot digest.
- 3.a.4: ingestion and canonicalization. Lexeme-preserving parser adapter for `q`, `b`,
  `u`, quarantine path, bounded queue, batch writer, duplicate and late handling.
- 3.a.5: collector sessions and quality. Session and connection events, gap taxonomy,
  quality windows, `DatasetQualityReport`.
- 3.a.6: offline query and replay. Repository boundary, snapshot selection, deterministic
  replay clock, offline replay tests.
- 3.a.7: runtime collection validation. Human-controlled start and stop, measured ingest
  rate, measured bytes per row, overflow and crash behaviour, on the Runtime AVD only after
  a separate approval.
- 3.a.8: dataset export and closure. Parquet export, SQLite backup, digest verification,
  closure document.

Decision recorded for the existing operational rows (section 6):

    LEGACY_MARKET_ROWS_DECISION = PRESERVE_QUARANTINED_EXCLUDE_FROM_LEARNING

The existing `market_bars_1m`, `symbol_features`, `symbol_signals` and `journal_events`
rows are preserved as legacy operational data and excluded from the learning dataset,
because their source provenance (TEST, IEX, demo) cannot be reconstructed with certainty.
They are not automatically relabelled, deleted or moved by this phase. No database change
is made. Implementing the quarantine is a later block, not part of 3.a.1.

## 53. Safety and scope statement

- No change to REAL lock, LIVE lock, Auto Paper, submit, orders, anchors, reconciliation or
  the Risk Core in this phase.
- No ML code. No autonomous decision. No training.
- Market data is observational evidence, not execution authority.
- The Learning Core may predict, rank, score, classify and propose strategies. It may not
  bypass broker truth, position reconciliation, the Risk Core, execution guards, the REAL
  lock or the LIVE lock.

## 54. Final statement

Phase 3.a.1 produces this architecture only. It makes no runtime claim, no provider claim
beyond what the repository shows, and no claim that existing `market_bars_1m` data is
suitable for training.
