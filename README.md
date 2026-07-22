# VELA Android

Native Android cockpit for the isolated VELA migration lab. The current UX-2 build is organized into five phone destinations: **Inicio**, **Mercado**, **Velas**, **Paper**, and **Más**. Más contains Riesgo, Historial y auditoría, Configuración, and Diagnóstico.

## Current capabilities

- Jetpack Compose dark cockpit with a persistent six-state safety header.
- Read-only IEX market stream controls, watchlist, ticks, local signals, and Room history.
- Read-only 1-minute OHLC candlestick chart drawn with Compose Canvas.
- Read-only Alpaca Paper account and portfolio-risk views.
- Local dry-run, draft, preview, readiness, queue, and append-only audit views.
- DataStore preferences limited to visual/experience settings.
- Keystore-backed credential entry; saved values are never rendered.

## Safety defaults

- `Mode = READ_ONLY`
- `REAL locked = true`
- Paper-only; no LIVE trading endpoint.
- Auto Paper disabled.
- Manual Paper submit boundary compiled off by default in debug and always off in release.
- No background trading, cancel, replace, or close-position capability.

The repository contains the separately frozen Phase 2.v one-shot Paper boundary, but ordinary builds cannot arm it. Do not add local flags, endpoints, or mutations without the documented approval flow.

## Project

The Gradle project lives in [`android/`](android/). From that directory:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
.\scripts\safety-scan.ps1
.\gradlew.bat :app:assembleDebug --console=plain --no-daemon --no-build-cache --rerun-tasks
```

Visual development uses the `VELA_Lite` AVD. Runtime Paper validation is a separate, explicitly authorized procedure and is not part of UX-2.

## Documentation

- [`docs/vela-android-information-architecture.md`](docs/vela-android-information-architecture.md)
- [`docs/vela-android-candles-ux.md`](docs/vela-android-candles-ux.md)
- [`docs/vela-android-cockpit-ux-spec.md`](docs/vela-android-cockpit-ux-spec.md)
- [`docs/vela-android-cockpit-ux-implementation-notes.md`](docs/vela-android-cockpit-ux-implementation-notes.md)
- [`docs/phase-1-progress.md`](docs/phase-1-progress.md)

`G:\vela` remains outside this workspace and must not be modified or copied into this repository.
