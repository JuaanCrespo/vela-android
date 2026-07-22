# Windows VELA Codebase Audit — Read-Only

Date: 2026-05-26
Source: `G:\vela` (untouched; read-only inspection).
Audience: future Android lab implementer.

This audit captures **only what was directly observed** during the Phase 0
inspection. Anything not verified is marked `unverified`.

---

## 1. Inspection Method

Files were read in place under `G:\vela`. No file was modified, moved, or
copied out. No `.env`, database file, log, journal, cache, export, or
installer artifact was opened.

The inspection covered:

- Repository root listing.
- `README.md`, `AGENTS.md`, `requirements.txt`.
- `backend_main.py` (full).
- `app/main.py` (full).
- `app/config.py` (full).
- `app/constants.py` (full).
- `app/services/app_state.py` (full).
- `app/services/mode_guard.py` (full).
- `app/data/alpaca_client.py` (full).
- `app/data/auto_paper_trader.py` (header — enums, profile constants).
- `app/data/risk_manager.py` (header — limits, snapshot, decision).
- `app/data/paper_execution_engine.py` (header — order request/record).
- `app/db/models.py` (full).
- `app/api/fastapi_app.py` (route inventory only — ~80 endpoints).
- `app/data/stream_manager.py` (class inventory only).
- `docs/` (listing only — no read).

Tests under `G:\vela\tests\` were enumerated but not opened.

---

## 2. Verified Architecture Snapshot

### 2.1 Runtime

- Python 3.13.x backend sidecar (FastAPI + Uvicorn) on
  `127.0.0.1:LOCAL_API_PORT`.
- WinUI 3 frontend (Windows App Runtime 1.8, .NET 8 x64) as the
  product UI; legacy PySide6 shell retained for tests.
- Embedded SQLite at `%LOCALAPPDATA%\VELA\Data\database\vela.db` by
  default. PostgreSQL via `DATABASE_URL` is supported but optional.

### 2.2 Safety invariants observed

- `AppState.real_mode_locked: bool = True` (default).
- `mode_guard.validate_mode_transition` rejects requests to enter
  `REAL` while locked.
- `AlpacaClient.fetch_latest_market_data` raises if
  `use_paper_trading` is false.
- `AlpacaClient.submit_paper_market_order`,
  `get_order_by_id`, `get_order_by_client_order_id`,
  `fetch_paper_account_snapshot` all enforce paper-only at the call
  site.
- `Settings.alpaca_environment_name` defaults to `paper`.
- `live_*` policy fields exist on `Settings` with safe defaults and
  `live_risk_policy_locked=True` by default.

### 2.3 Data pipeline (observed in `backend_main.py`)

```
StreamManager (Alpaca poller / stream)
    → publish_market_update(update)
        → AutoPaperTrader.record_market_seen
        → BarAggregator.add_update
            → MarketCandlePersistence.persist_bar
            → FeatureEngine.add_bar
                → SignalEngine.add_features
                    → TradeSimulator.add_signal
        → TradeSimulator.add_market_update
            → SimulationJournal.add_position_snapshot
            → AutoPaperTrader.on_position_update
```

Paper order events flow:
```
PaperExecutionEngine.order_updated
    → SimulationJournal.add_paper_order_record
```

Operational scheduling drives `start/stop market reading` and
`start/stop auto paper` based on per-symbol windows.

### 2.4 SQLAlchemy entities observed in `app/db/models.py`

`Asset`, `MarketBar1m`, `Signal`, `SimulatedOrder`, `SystemLog`,
`SimulationJournalEventRecord`, `SimulationJournalMetricsSnapshot`,
`LearningConfig`, `LearningSource`, `MarketObserverCandleRecord`,
`MarketObserverDailySnapshotRecord`, `MarketObserverMetricRecord`,
`MarketObserverReportRecord`, `AccountSnapshotRecord`,
`AccountPositionSnapshotRecord`, `AutoPaperDecisionRecord`,
`AutoPaperOrderAttemptRecord`.

There may be additional models past byte 470 of the file that the
header read did not cover — `unverified`.

### 2.5 FastAPI surface area

The frontend consumes ~80 endpoints. Sampled prefixes:

- `/health`, `/api/v1/system/health`
- `/dashboard/summary`
- `/operator/control` (+ POST control actions)
- `/state`, `/api/v1/state`
- `/market/bars`, `/market/bars/{symbol}`
- `/market-observer/status`, `/market-observer/collect`
- `/account-snapshot/status|current|history`
- `/signals/latest`
- `/simulator/state`
- `/journal/metrics`, `/journal/events`
- `/activity/recent`
- `/settings/language|alpaca|learning|learning/sources`
- `/diagnostics/export`

These are **not** the Android contract. The Android lab replaces them
with internal repository interfaces (see migration map §4).

### 2.6 Auto Paper specifics (header of `auto_paper_trader.py`)

- Default symbol constant: `AUTO_PAPER_SYMBOL = "BTC/USD"`.
- Risk profiles: `CONSERVATIVE`, `BALANCED`, `DYNAMIC`.
- Cadence modes: `CONFIRMED_ONLY`, `OPPORTUNITY_CONFIRMATION`,
  `CONTROLLED_DYNAMIC`.
- Opportunity FSM states: `BLOCKED`, `WATCH_ONLY`, `GRAY_OPPORTUNITY`,
  `PAPER_READY`, `STRONG_OPPORTUNITY`, `EXECUTION_ALLOWED`,
  `ERROR_PAUSED`.
- Stop states: `NONE`, `SOFT_STOP`, `EMERGENCY_STOP`,
  `CIRCUIT_BREAKER`.
- Crypto qty increment: `Decimal("0.00000001")`.

### 2.7 Risk defaults (`RiskLimits` in `risk_manager.py`)

- `max_position_size = 100.0`
- `max_open_positions = 10`
- `max_daily_loss = 1000.0`
- `blocked_symbols = frozenset()`

### 2.8 Alpaca symbol normalization

- Canonical crypto format: `BASE/QUOTE`.
- Quotes accepted at boundaries: `USD`, `USDT`, `USDC`.
- Bases recognized: `AAVE`, `AVAX`, `BCH`, `BTC`, `DOGE`, `ETH`,
  `LINK`, `LTC`, `MKR`, `SHIB`, `SOL`, `UNI`, `YFI`.
- Historical timeframes: 1Min, 5Min, 15Min, 30Min, 1Hour, 4Hour,
  1Day, 1Week, 1Month (with case-insensitive aliases like `1m`,
  `1h`, `4h`, `24h`, `1d`, `1w`, `1mo`).

---

## 3. What Was Not Inspected

- Bodies of: `auto_paper_trader.py` past line 80,
  `risk_manager.py` past line 80, `paper_execution_engine.py`
  past line 80, `stream_manager.py` past line 30,
  `fastapi_app.py` past the route inventory.
- All `app/data/market_observer*.py` files.
- All `app/data/alpaca_rules_engine.py`, `common_decision_engine.py`,
  `execution_adapters.py`, `live_risk_policy.py`.
- All `app/db/*` store classes (only `models.py` read).
- The `docs/` directory contents.
- The WinUI 3 source under `winui/`.
- All `scripts/`, `packaging/`, `branding/`, `build/`, `dist/`.
- The `tests/` suite (file names only).

The migration map's per-module mapping for these areas is based on
filenames + the calling pattern observed in `backend_main.py` and
`app/main.py`. Treat that mapping as a starting point, not a
specification. Each module should be re-audited as it enters Phase 1
implementation.

---

## 4. Modules to Migrate First (Phase 1 critical path)

In dependency order, smallest-first:

1. `OperationMode` enum (`constants.py`) → `core/OperationMode.kt`.
2. `ModeGuard.validate_mode_transition` → `state/ModeGuard.kt`.
3. `AppState` (with `realModeLocked=true`) → `state/AppState.kt`.
4. `RiskLimits`, `RiskStateSnapshot`, `RiskDecision`, `RiskManager`
   → `data/risk/*.kt`.
5. `BarAggregator` → `data/market/BarAggregator.kt`.
6. `FeatureEngine` → `data/market/FeatureEngine.kt`.
7. `SignalEngine` → `data/market/SignalEngine.kt`.
8. `TradeSimulator` (in-memory only) → `data/simulation/TradeSimulator.kt`.
9. `SimulationJournal` + Room DAO → `data/simulation/*.kt`.
10. Operational schedule evaluator (pure function) →
    `services/scheduler/OperationalSchedule.kt`.

Everything above is offline. No Alpaca code, no service, no UI.

---

## 5. Modules That Must Be Rewritten (not ported)

- Anything PySide6 / Qt-based (`app/main.py`, `app/ui/`, Qt signals
  in `paper_execution_engine.py`, `stream_manager.py`,
  `trade_simulator.py` if Qt-based).
- WinUI frontend (`winui/`).
- FastAPI sidecar (`backend_main.py`, `api_manager.py`,
  `app/api/*`).
- `alpaca-py` consumers (`app/data/alpaca_client.py`,
  `app/data/stream_manager.py`).
- SQLAlchemy schema (`app/db/*`) → Room.
- Loguru (`app/services/logger_service.py`) → Timber.
- Windows path / registry logic (`app/config.py`,
  `app/services/runtime_paths.py`) → Android scoped storage.
- All `scripts/` and `packaging/`.

---

## 6. What Must Not Be Touched Yet

- The entire `G:\vela` tree (read-only for the lab forever).
- Any credential. No `.env` copied out. No keys imported.
- The Windows database, journal, cache, export, learning, installer,
  updater, and log files.
- LIVE Alpaca endpoints.
- Auto-start on boot, background order submission, real-mode unlock,
  remote policy fetch.

---

## 7. Open Questions for Phase 1 Kick-off

These are explicit gaps that should be resolved before Kotlin code lands:

1. Does the Android lab need a desktop companion (Windows or web) for
   chart inspection, or is the phone the sole UI? Affects API design.
2. Minimum Android API target. Foreground service rules tightened at
   API 34. Recommendation: `minSdk=29`, `targetSdk=34` (verify).
3. Build system: Gradle + Kotlin DSL is standard. Confirm.
4. DI framework: Hilt vs. Koin. Either works; Hilt is more idiomatic.
5. Serialization: Moshi vs. kotlinx-serialization. Either works;
   kotlinx-serialization is simpler for Compose multiplatform later.
6. Should the lab vendor Alpaca's WebSocket protocol from scratch or
   wrap an existing community Kotlin Alpaca client? Existing clients
   are unaudited; recommendation is to vendor.
7. ML target window: TF Lite or ONNX Runtime Mobile. Decide once
   feature vector shape is finalized.

None of these block Phase 0. All should be answered before the first
Kotlin commit.
