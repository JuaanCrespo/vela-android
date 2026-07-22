# VELA Android app

Kotlin/Jetpack Compose application for the VELA Android migration lab.

## Stack

- Kotlin, coroutines, and `StateFlow`
- Jetpack Compose Material 3
- Room for app-private market and audit persistence
- DataStore Preferences for visual settings only
- Android Keystore-backed encrypted credential storage
- OkHttp WebSocket for the existing read-only market feeds
- JUnit 5 unit and source-contract tests

## UX-2 structure

- **Inicio** — priority market, Paper account, risk, and activity summaries.
- **Mercado** — symbol selector, watchlist, IEX stream controls, and optional diagnostics.
- **Velas** — local Room-backed 1m OHLC chart; no history download or trading linkage.
- **Paper** — existing account, dry-run, preview, readiness, protected manual boundary, and local audit cards.
- **Más** — Riesgo, Historial y auditoría, Configuración, and Diagnóstico.

Activity-scoped ViewModels preserve forms and stream state while state-only navigation avoids automatic refreshes or network calls.

## Safe validation

```powershell
.\scripts\safety-scan.ps1
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
.\gradlew.bat :app:assembleDebug --console=plain --no-daemon --no-build-cache --rerun-tasks
```

Before building, keep `MANUAL_PAPER_SUBMIT_COMPILED` absent from `local.properties`. Debug then resolves to `false`; release is hard-coded to `false`.

Do not commit `local.properties`, credentials, signing material, APKs, databases, logs, or emulator state.
