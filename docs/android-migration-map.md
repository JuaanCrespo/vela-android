# VELA Android Migration Map

Status: Phase 0 — Lab bootstrap only.
Date: 2026-05-26
Source of truth (read-only): `G:\vela` (current Windows VELA project).
Lab root (write-only): `G:\vela-android`.

This document is the canonical migration map for porting the **conceptual
behavior** of the current Windows VELA application to a native Android lab.
It is **not** a line-by-line port. The Windows project must remain untouched.

---

## 0. Hard Safety Boundaries

These rules apply to every section below. No section overrides them.

- Do not modify anything under `G:\vela`.
- Do not copy `.env`, `vela.db`, real Alpaca credentials, installer, updater,
  cache, journal, logs, exports, or learning files out of `G:\vela`.
- The Android lab does **not** unlock REAL trading.
- The Android lab does **not** submit any orders in this phase (not even Paper).
- The Android lab does **not** connect to Alpaca in this phase.
- The Android lab does **not** ingest credentials in this phase.
- Auto Paper, when introduced later, stays Paper-only.
- LIVE remains visible-but-locked. The lock is an architectural invariant,
  not a feature flag.

---

## 1. Windows Architecture Summary (current state in `G:\vela`)

VELA today is a local Windows desktop trading application with a split
runtime: a Python backend sidecar (FastAPI on `127.0.0.1`) and a WinUI 3
frontend that consumes it. A legacy PySide6 shell is retained for tests.

### 1.1 Process topology

- **Frontend**: `winui/Vela.WinUI/` (WinUI 3, .NET 8 x64, Windows App Runtime).
  Launched via installer shortcut or `scripts\Start-VelaDesktop.ps1`. The
  frontend silently starts the Python sidecar when the local API is absent.
- **Backend sidecar**: `backend_main.py` — FastAPI + Uvicorn on
  `LOCAL_API_HOST` / `LOCAL_API_PORT` (default `127.0.0.1:8000`). Runs the
  ingestion → features → signals → simulation → paper-execution pipeline.
- **Legacy shell**: `app/main.py` — PySide6 entrypoint. Stable for tests.
- **Data root**: `%LOCALAPPDATA%\VELA\Data` (config, logs, embedded SQLite
  at `database\vela.db`, cache, journal, exports, learning).
  Install root and data root are kept separate by design.

### 1.2 Layered responsibilities

| Layer        | Module path                                | Responsibility                                                    |
|--------------|--------------------------------------------|-------------------------------------------------------------------|
| Entry        | `backend_main.py`                          | Wire all services, start FastAPI sidecar                          |
| Config       | `app/config.py`, `app/constants.py`        | `Settings` dataclass, data root, env loading, `OperationMode`     |
| State        | `app/services/app_state.py`                | `AppState` with `real_mode_locked=True` default                   |
| Guards       | `app/services/mode_guard.py`               | `validate_mode_transition` — REAL gate enforcement                |
| Alpaca       | `app/data/alpaca_client.py`                | Wraps `alpaca-py`: trading client, market data, account, bars     |
| Streaming    | `app/data/stream_manager.py`               | Poller / stream lifecycle, emits `BootstrapMarketUpdate`          |
| Aggregation  | `app/data/bar_aggregator.py`               | 1-minute OHLCV aggregation from updates                           |
| Features     | `app/data/feature_engine.py`               | Feature computation per bar                                       |
| Signals      | `app/data/signal_engine.py`                | Signal derivation from features                                   |
| Risk         | `app/data/risk_manager.py`                 | `RiskLimits`, `RiskStateSnapshot`, entry/exit gating              |
| Simulation   | `app/data/trade_simulator.py`              | In-memory simulated positions and PnL                             |
| Paper        | `app/data/paper_execution_engine.py`       | Real Alpaca paper order submission via `AlpacaClient`             |
| Auto Paper   | `app/data/auto_paper_trader.py`            | Profiles (Conservative/Balanced/Dynamic), opportunity FSM, stops  |
| Decisions    | `app/data/common_decision_engine.py`       | Shared decision logic for paper / future live                     |
| Live policy  | `app/services/live_risk_policy.py`         | LIVE-side caps; loaded but enforced as locked                     |
| Schedule     | `app/data/operational_schedule.py` + `app/services/operational_scheduler.py` | Per-symbol on/off windows |
| Journal      | `app/data/simulation_journal.py` + `app/db/simulation_journal_store.py` | Event ledger + metrics rollup |
| Performance  | `app/data/performance_evaluator.py`        | Win/loss, drawdown, P&L summaries                                 |
| Observer     | `app/data/market_observer.py` + reports/metrics | Periodic market scan + candle persistence + reports           |
| Account      | `app/data/account_snapshot.py` + store     | Paper account state history                                       |
| Persistence  | `app/db/*` (SQLAlchemy + SQLite default)   | `desktop_persistence`, schema migrations, stores                  |
| API          | `app/api/fastapi_app.py`, `app/api/schemas.py`, `app/api/serializers.py`, `api_manager.py` | ~80 REST endpoints for frontend |
| Logging      | `app/services/logger_service.py`           | Loguru sink, optional Qt sink                                     |
| Diagnostics  | `app/services/startup_diagnostics.py`, `app/services/diagnostics_export.py` | Startup checks + error reports |
| Learning     | `app/data/learning_settings.py` + `app/db/learning_store.py` | Preferences, source list, future ML config        |
| UI (legacy)  | `app/ui/main_window.py`                    | PySide6 shell (retained for tests, not the product UI)            |
| UI (current) | `winui/Vela.WinUI/`                        | WinUI 3 frontend — talks to FastAPI sidecar only                  |

### 1.3 Operation modes (`OperationMode`)

`READ_ONLY` → `SIMULATED` → `REAL` (locked).
`AppState.real_mode_locked` defaults to `True`. `mode_guard` rejects any
transition to `REAL` while locked. This contract is non-negotiable on
Android; see §11 and §12.

### 1.4 Alpaca contract (today)

- Trading base URL is paper vs. live by `Settings.alpaca_env`.
- Default `ALPACA_ENV=paper`. Paper URL: `https://paper-api.alpaca.markets/v2`.
- Market data: `https://data.alpaca.markets/v2` (IEX for equities, crypto feed
  for crypto). Symbols normalize to canonical `BASE/QUOTE` for crypto.
- Historical bars exposed to charts: 1Min, 5Min, 15Min, 30Min, 1Hour, 4Hour,
  1Day, 1Week, 1Month.
- All Alpaca traffic goes through `AlpacaClient`. Order submission is gated by
  `use_paper_trading`.

### 1.5 Persistence (today)

- Default: embedded SQLite at `%LOCALAPPDATA%\VELA\Data\database\vela.db`.
- Optional external `DATABASE_URL` (PostgreSQL) supported.
- Tables observed in `app/db/models.py` include: `assets`, `market_bars_1m`,
  `signals`, `simulated_orders`, `system_logs`, `simulation_journal_events`,
  `simulation_journal_metrics`, `learning_configs`, `learning_sources`,
  `market_observer_candles`, `market_observer_daily_snapshots`,
  `market_observer_metrics`, `market_observer_reports`, `account_snapshots`,
  `account_position_snapshots`, `auto_paper_decisions`,
  `auto_paper_order_attempts`.

---

## 2. Android Target Architecture

Android cannot run a Python sidecar. The conceptual pipeline must run
**in-process** as Kotlin services inside a single app, with the WinUI frontend
replaced by Jetpack Compose screens and the FastAPI surface replaced by an
internal service/repository layer.

### 2.1 Process / lifecycle model

- Single Android application package (`com.vela.android.lab`).
- **UI tier**: Jetpack Compose, MVVM-style ViewModels.
- **Domain tier**: pure Kotlin (no Android imports) — feature engine, signal
  engine, risk manager, trade simulator, auto paper FSM. These are the
  direct conceptual analogues of `app/data/*` modules.
- **Service tier**:
  - `MarketReadingService` — a **Foreground Service** with a
    `MediaSession`-style persistent notification, holding the market data
    stream alive for 24/7 reading.
  - `AutoPaperService` (later phase) — gated, Paper-only.
- **Background tier**:
  - `WorkManager` for non-critical jobs only: nightly report generation,
    journal archival compaction, cache pruning. Never for real-time market
    data or trading.
- **Persistence**: Room (SQLite) under app-private storage. No external DB.
- **Networking**: OkHttp + Retrofit (REST) and OkHttp WebSocket (streaming).
- **Concurrency**: Kotlin coroutines + `StateFlow` / `SharedFlow` replacing
  the existing Qt `Signal` wiring observed in `paper_execution_engine.py`,
  `stream_manager.py`, etc.
- **Secrets**: Android Keystore-backed `EncryptedSharedPreferences` for the
  future paper credential pair. Not introduced in Phase 0–1.

### 2.2 Module layout (proposed, not created yet)

```
android/
  app/
    src/main/kotlin/com/vela/android/
      core/            ← constants, OperationMode, error types
      config/          ← Settings repository, env-equivalent overrides
      state/           ← AppState (real-mode-locked invariant lives here)
      data/
        alpaca/        ← AlpacaClient (paper REST + market data + WS)
        market/        ← BarAggregator, FeatureEngine, SignalEngine
        risk/          ← RiskManager, RiskLimits, decisions
        simulation/    ← TradeSimulator, SimulationJournal
        paper/         ← PaperExecutionEngine (later phase)
        autopaper/     ← AutoPaperTrader FSM (later phase)
        observer/      ← MarketObserver, reports/metrics
        account/       ← Account snapshot service
      db/
        room/          ← Room entities + DAOs (parallel to app/db/models.py)
      services/
        market/        ← MarketReadingService (Foreground Service)
        scheduler/     ← Operational schedule evaluation
        diagnostics/   ← Startup diagnostics, error reports
        logging/       ← Logging facade (replaces loguru)
      ui/
        compose/       ← Compose screens, theme, navigation
        viewmodel/     ← ViewModels per screen
      security/
        keystore/      ← Keystore wrapper for future credentials
```

This is **planned**. Phase 0 only creates the lab map; no Kotlin code is
written in this phase.

---

## 3. Modules to Migrate (conceptually)

These have well-isolated logic that translates cleanly to Kotlin.

| Windows module                                | Android target                                         | Notes                                                                 |
|-----------------------------------------------|--------------------------------------------------------|-----------------------------------------------------------------------|
| `app/constants.py` (`OperationMode`, labels)  | `core/Constants.kt`, `core/OperationMode.kt`           | 1:1. Enum class.                                                      |
| `app/services/app_state.py`                   | `state/AppState.kt` (singleton with `StateFlow`)       | Keep `realModeLocked = true` invariant.                               |
| `app/services/mode_guard.py`                  | `state/ModeGuard.kt`                                   | Pure function; identical guard semantics.                             |
| `app/data/bar_aggregator.py`                  | `data/market/BarAggregator.kt`                         | Pure logic; switch Qt signals → `SharedFlow`.                         |
| `app/data/feature_engine.py`                  | `data/market/FeatureEngine.kt`                         | Pure logic.                                                           |
| `app/data/signal_engine.py`                   | `data/market/SignalEngine.kt`                          | Pure logic.                                                           |
| `app/data/risk_manager.py`                    | `data/risk/RiskManager.kt`                             | Translate dataclasses → Kotlin data classes.                          |
| `app/data/trade_simulator.py`                 | `data/simulation/TradeSimulator.kt`                    | In-memory simulator. Critical for Phase 1.                            |
| `app/data/simulation_journal.py`              | `data/simulation/SimulationJournal.kt`                 | Persists via Room DAO instead of SQLAlchemy store.                    |
| `app/data/performance_evaluator.py`           | `data/simulation/PerformanceEvaluator.kt`              | Pure logic.                                                           |
| `app/data/operational_schedule.py`            | `services/scheduler/OperationalSchedule.kt`            | Per-symbol windows.                                                   |
| `app/services/operational_scheduler.py`       | `services/scheduler/OperationalScheduler.kt`           | Drives MarketReadingService start/stop.                               |
| `app/data/learning_settings.py`               | `data/learning/LearningSettings.kt`                    | Preferences only in Phase 1; ML separate.                             |

---

## 4. Modules to Rewrite (cannot be ported)

These are tied to the Python/Windows/Qt/FastAPI runtime and must be
re-implemented natively.

| Windows module                              | Android replacement                                              | Reason                                                                 |
|---------------------------------------------|------------------------------------------------------------------|------------------------------------------------------------------------|
| `app/main.py` (PySide6 bootstrap)           | `MainActivity` + `App` Application class                          | PySide6 cannot exist on Android.                                       |
| `app/ui/main_window.py`                     | Compose screen graph (`ui/compose/*`)                             | WinUI / Qt UI is non-portable.                                         |
| `winui/Vela.WinUI/*`                        | Compose screens + ViewModels                                     | WinUI is Windows-only.                                                 |
| `backend_main.py` + `api_manager.py`        | DI graph (Hilt/Koin) wiring inside the app                       | No sidecar process on Android; the "API" is internal calls.            |
| `app/api/fastapi_app.py` (~80 endpoints)    | Repository / use-case interfaces consumed by ViewModels          | No localhost loopback to a Python process.                             |
| `app/data/alpaca_client.py` (via `alpaca-py`) | `data/alpaca/AlpacaClient.kt` (OkHttp/Retrofit + WebSocket)     | `alpaca-py` is Python-only; rewrite against Alpaca v2 HTTP/WS surface. |
| `app/data/stream_manager.py` (Qt poller)    | `data/alpaca/MarketStreamClient.kt` + coroutines                 | Qt threading model is not on Android.                                  |
| `app/data/paper_execution_engine.py`        | `data/paper/PaperExecutionEngine.kt`                              | Same algorithm, no Qt signals; Phase 3 only.                           |
| `app/data/auto_paper_trader.py`             | `data/autopaper/AutoPaperTrader.kt`                               | Large FSM; rewrite carefully with state-machine in Kotlin. Phase 3.    |
| `app/db/*` (SQLAlchemy)                     | `db/room/*` (Room)                                                | Schema mirrors `models.py`; ORM rewrite.                               |
| `app/services/logger_service.py` (loguru)   | `services/logging/Logger.kt` (Timber wrapper)                     | Loguru is Python-only.                                                 |
| `app/services/runtime_paths.py`             | Android scoped storage (`Context.filesDir`, `cacheDir`)           | No `%LOCALAPPDATA%` on Android.                                        |
| `app/services/startup_diagnostics.py`       | `services/diagnostics/StartupDiagnostics.kt`                      | Same checks, native APIs.                                              |
| `app/services/error_reporting.py`           | `services/diagnostics/ErrorReporting.kt`                          | File-based reports under `filesDir/error-reports/`.                    |
| `app/services/health_refresh.py`            | Repository polling + `StateFlow`                                  | Replace Qt timer.                                                      |
| Installer / packaging (`scripts/`, `packaging/`) | Gradle + Play Console (later) / sideload APK (lab)            | Windows installer is irrelevant.                                       |

---

## 5. Modules to Postpone

Deferred until the Phase 1 lab pipeline is green.

- `app/data/market_observer.py` and the entire reports pipeline.
- `app/data/account_snapshot.py` and stores.
- `app/data/alpaca_rules_engine.py` (rule pack version checks).
- Learning sources / web-fetched content (`learning_sources` table).
- Diagnostics export bundles.
- Operational schedule UI (logic can land earlier; UI later).
- Account history charts.
- Multi-language settings.

These exist in the Windows project but are not on the critical path for
proving the Android lab pipeline.

---

## 6. Alpaca Integration Plan (Android)

Phase 0 (now): no Alpaca connection. No credentials. No network calls.

Phase 1 (lab, paper-only, read-only market data):
- Build `data/alpaca/AlpacaClient.kt` against Alpaca REST v2 directly with
  OkHttp + Moshi/kotlinx-serialization. Mirror the Python wrapper's
  surface area only for what the Android lab actually consumes:
  - `GET /v2/account` (paper only, sandbox test).
  - `GET /v2/stocks/{symbol}/trades/latest` (IEX feed).
  - `GET /v2/stocks/bars` and `GET /v2/crypto/us/bars`.
- Crypto symbol normalization matches the Python rule (`BASE/QUOTE`).
- Historical timeframe alias map (1Min … 1Month) ported verbatim.
- All clients enforce `alpacaEnv == "paper"` at the call site; live URL
  constants exist but are unreachable code paths.

Phase 2 (lab, streaming):
- `wss://stream.data.alpaca.markets/v2/iex` (and `/v2/crypto`) via OkHttp
  WebSocket. Authentication frame uses paper key/secret.
- Backpressure handled by coroutines + buffered `SharedFlow`.

Phase 3 (lab, paper order submission):
- `POST /v2/orders` from `PaperExecutionEngine.kt`.
- Hard guard: `Settings.useLiveTrading == false` AND
  `AppState.realModeLocked == true`.

Live (`https://api.alpaca.markets/v2`): **out of scope**, see §12.

---

## 7. Market Data Collection Plan

- Foreground Service (`MarketReadingService`) owns the WebSocket and a
  bounded buffer of recent updates.
- Updates fan out via a single `SharedFlow<MarketUpdate>` consumed by:
  - `BarAggregator` (1-minute OHLCV; identical algorithm to Python).
  - `MarketCandlePersistence` writer (Room).
  - `TradeSimulator.addMarketUpdate(...)`.
- Polling fallback (mirroring `_AlpacaPoller`) when WebSocket is
  unavailable, at the same cadence.
- Symbol set sources, in order: explicit user selection → market universe
  preference → `DEFAULT_SYMBOLS` equivalent.
- The service must survive Doze/App Standby. Use `setForeground` with
  `FOREGROUND_SERVICE_TYPE_DATA_SYNC` and a low-priority notification.
  Document battery-optimization exemption request in the user-facing UI;
  do not request silently.

---

## 8. SQLite / Room Storage Plan

Mirror the SQLAlchemy schema observed in `app/db/models.py`. One Room
database, versioned, with a migration ladder.

| SQLAlchemy table                          | Room entity                              |
|-------------------------------------------|------------------------------------------|
| `assets`                                  | `AssetEntity`                            |
| `market_bars_1m`                          | `MarketBar1mEntity`                      |
| `signals`                                 | `SignalEntity`                           |
| `simulated_orders`                        | `SimulatedOrderEntity`                   |
| `system_logs`                             | `SystemLogEntity` (capped table)         |
| `simulation_journal_events`               | `SimulationJournalEventEntity`           |
| `simulation_journal_metrics`              | `SimulationJournalMetricsEntity`         |
| `learning_configs`                        | `LearningConfigEntity`                   |
| `learning_sources`                        | `LearningSourceEntity`                   |
| `market_observer_candles`                 | `MarketObserverCandleEntity`             |
| `market_observer_daily_snapshots`         | `MarketObserverDailySnapshotEntity`      |
| `market_observer_metrics`                 | `MarketObserverMetricEntity`             |
| `market_observer_reports`                 | `MarketObserverReportEntity`             |
| `account_snapshots`                       | `AccountSnapshotEntity`                  |
| `account_position_snapshots`              | `AccountPositionSnapshotEntity`          |
| `auto_paper_decisions`                    | `AutoPaperDecisionEntity`                |
| `auto_paper_order_attempts`               | `AutoPaperOrderAttemptEntity`            |

Storage location: `Context.filesDir/database/vela-lab.db`. No external DB.
JSON columns map to `String` with a Moshi adapter.

The Windows `schema_migrations` table maps to Room's auto-managed
`room_master_table`; Room migrations replace the Python migration ladder.

---

## 9. Journal / Logging / Cache Plan

- **Logging**: replace loguru with Timber. Sinks:
  - logcat (debug builds).
  - rolling file under `filesDir/logs/vela.log` (release builds).
- **Journal**: `SimulationJournalEventEntity` + metrics rollup, identical
  semantics to the Windows journal. Exports to JSON/CSV land in
  `filesDir/exports/` (Android equivalent of `VELA_EXPORTS_DIR`).
- **Cache**: `cacheDir/market/` for transient market data snapshots that
  the OS may evict. Persistent candle history goes to Room, not cache.
- **No PII/credentials ever logged.** Sanitization rules from
  `_sanitize_feed_error_message` in `alpaca_client.py` carry over.

---

## 10. Machine Learning / Data Collection Plan

Phase 1: no ML. Collect bars and signals into Room; let the journal
accumulate.

Phase 2+: on-device inference candidates:
- TensorFlow Lite or ONNX Runtime Mobile (both supported on Android).
- Models trained offline, shipped as assets in the APK or downloaded once
  and pinned by hash.
- Inference happens inside the same service tier as `SignalEngine`, never
  on the UI thread.
- The `learning_configs` / `learning_sources` tables continue to drive
  preferences. Web-scraped sources stay out of scope for the lab.

No federated learning. No telemetry exfiltration. All inference is local.

---

## 11. Foreground Service (24/7 reading) Plan

- `MarketReadingService` is the only component allowed to hold a wakelock
  pattern for market data.
- Started by an explicit user action (a "Start Reading" button), never on
  boot, never silently.
- Stopped by user action **or** by the operational schedule (mirrors
  `OperationalScheduler` in the Windows backend).
- Notification: persistent, low-priority, with a "Stop" action.
- Type: `dataSync` (Android 14+ requires explicit FGS type declaration).
- Battery optimization: the app surfaces a one-time request screen
  explaining why the user should grant exemption; the request is not
  forced.
- Crash recovery: service uses `START_STICKY` plus a coroutine supervisor;
  WebSocket reconnects with exponential backoff matching the
  `_call_feed_with_timeout_retry` policy in `alpaca_client.py`.

---

## 12. Android Security / Keystore Plan

- No credentials are stored or required in Phase 0–1.
- Phase 3 (when paper order submission is wired):
  - Paper API key + secret stored only in `EncryptedSharedPreferences`
    backed by an Android Keystore-generated AES-256 key.
  - Key alias bound to `setUserAuthenticationRequired(false)` for the lab
    (no biometric prompts in dev); production builds will revisit.
  - Plaintext credentials never written to logs, never to Room, never to
    files outside the EncryptedSharedPreferences blob.
  - First-run flow: user pastes paper key/secret into a dedicated screen;
    nothing is auto-imported from the Windows project.
- Network: TLS-only via OkHttp default config. Certificate pinning to
  Alpaca endpoints is **considered** but deferred to Phase 3 sign-off.
- `android:allowBackup="false"` on the lab manifest to prevent backup
  agent from copying the EncryptedSharedPreferences blob to Google.

---

## 13. Paper-Only Safety Plan

The lab is Paper-only by construction:

- `OperationMode.REAL` exists but is unreachable.
- `AppState.realModeLocked` defaults to `true` and the only public way to
  flip it is a private debug build flag — and even then, the live URL
  constants are referenced from a no-op path (compile-time guard).
- `AlpacaClient` factory enforces the paper base URL. Any attempt to
  build a live trading client throws.
- `PaperExecutionEngine` (Phase 3) is the only order-submitting path. It
  refuses to run if `alpacaEnv != "paper"`.
- `AutoPaperTrader` (Phase 3) is the only automated decision path. It
  refuses to run while `MarketReadingService` is not reading, mirroring
  the `market_reading_active` callback in `backend_main.py`.
- `OperationalSchedule` enforces per-symbol time windows.
- The risk manager applies position size, open-position count, and
  daily-loss caps before any submission. Defaults mirror `RiskLimits`
  in `app/data/risk_manager.py`.
- A visible kill switch in the UI stops all services within one tick.

---

## 14. Future LIVE Safety Plan (not in this phase)

A LIVE phase is **not part of the Android lab**. Documented here only to
prevent accidental shortcuts:

- `Settings.liveRiskPolicyLocked` defaults to `true` and is read from a
  signed remote config the lab does not ship with.
- LIVE unlock requires:
  1. A minimum 14-day Paper track record (`live_min_paper_track_record_days`).
  2. Order reconciliation success rate ≥ 99% (`live_min_order_reconciliation_success_rate`).
  3. Configured max drawdown not exceeded (`live_max_drawdown_allowed`).
  4. Manual confirmation per order (`live_manual_confirmation_required`).
  5. A physical kill switch acknowledgement (`live_kill_switch_required`).
- LIVE entry is `MICRO_SIZE_ONLY` (`live_starting_mode`).
- LIVE caps from `Settings`: `live_max_notional_per_trade`,
  `live_max_daily_loss_usd`, `live_max_daily_loss_pct`,
  `live_max_trades_per_day`, `live_max_trades_per_hour`,
  `live_max_open_positions`.
- Crypto allowed by default in the Windows policy; equities/overnight
  disabled. Carry forward unchanged.
- LIVE is **never enabled by a code change alone**. Two signed artifacts
  are required: app build + remote policy.

None of this is implemented in the Android lab in Phase 0.

---

## 15. Testing Plan

### 15.1 Unit (Phase 1)

- `BarAggregator`, `FeatureEngine`, `SignalEngine`, `RiskManager`,
  `TradeSimulator`, `ModeGuard`, `OperationalSchedule` evaluation —
  pure-Kotlin, no Android dependencies, run on the JVM via JUnit5.
- Port the existing `tests/test_*.py` fixtures conceptually as
  Kotlin equivalents. Do not copy the Python files into the lab; they
  stay in `G:\vela`.

### 15.2 Instrumented (Phase 2)

- Room DAOs and migrations.
- `MarketReadingService` lifecycle (start, stop, reconnect, crash).
- Notification visibility and "Stop" action behavior.
- Foreground service Doze survival.

### 15.3 Network (Phase 2)

- MockWebServer-backed tests for the Alpaca REST client.
- Replay-based tests for the WebSocket client (recorded paper frames).

### 15.4 End-to-end (Phase 3)

- Paper sandbox account: submit, fill, cancel — under `RiskManager` caps.
- AutoPaper FSM transitions exercised against scripted market frames.
- Kill switch latency benchmark (must stop within one tick).

### 15.5 Static / safety

- A CI check that grep-fails on any reference to the live trading base
  URL outside the constants file.
- A CI check that `realModeLocked` defaults stay `true`.
- A CI check that no `.env` or credential file is committed.

---

## 16. Phase Plan (Android lab)

- **Phase 0** (current): map + skeleton. No Kotlin code. No network.
- **Phase 1**: Kotlin project, Compose shell, Room schema, in-memory
  pipeline, `ModeGuard`, simulated journal — all offline.
- **Phase 2**: Alpaca paper market data (REST + WS) inside
  `MarketReadingService`. Read-only.
- **Phase 3**: Paper execution engine + AutoPaper FSM behind kill switch.
- **Phase 4**: Persistent journal exports, market observer reports,
  diagnostics export.
- **LIVE**: not on this roadmap.

---

## 17. What Must Not Be Touched Yet

- `G:\vela` and every file under it.
- Any real Alpaca credentials.
- Any `.env`, `vela.db`, journal, log, cache, export, learning, installer,
  or updater artifact from the Windows tree.
- LIVE trading endpoints anywhere in the Android lab.
- Background order submission of any kind.
- Auto-start on boot.
