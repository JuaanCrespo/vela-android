# VELA Android — UX-1 cockpit visual pass implementation notes

**Visual-only refactor.** No trading logic changed. No data-layer file touched. No gate, submit client, endpoint allowlist, or feature flag altered. No runtime submit was performed and no POST was fired. Phase 2.w was **not** started.

Source of design intent: [`docs/vela-android-cockpit-ux-spec.md`](vela-android-cockpit-ux-spec.md) (UX-0). No file under `G:\vela` was read or referenced during this pass — the spec is self-contained.

---

## 1. What changed visually

### 1.1 Theme

- [`ui/theme/Theme.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/theme/Theme.kt) replaced its previous "cool blue" Material 3 dark palette with the **VELA cockpit dark palette** from the UX-0 spec: surface `#0B1A28`, surface-variant `#0E2232`, background `#04101A`, on-surface `#E6FAFF`, on-surface-variant `#87AFC0`, outline `#1E4D63`, primary `#2DE2B7` (mint accent), error `#D76A76`, error-container `#331B1F`.
- Typography scale added: title-large 24 sp / title-medium 18 sp / body-small 13 sp / label-small 11 sp, with weights matching the desktop cockpit's `Segoe UI Variable SemiBold` and `Bold` mood.
- `VelaLabTheme` is now **always dark**, matching the desktop's explicit `RequestedTheme="Dark"`. The `darkTheme` parameter is kept for API compatibility and marked `@Suppress("UNUSED_PARAMETER")`; system light-mode toggles do not weaken the cockpit aesthetic.

### 1.2 New visual helper components

New file [`ui/theme/VelaComponents.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/theme/VelaComponents.kt) adds:

| Component | Role |
| --- | --- |
| `VelaExtendedColors` | Immutable extension palette (safe / warning / blocked + containers, card stroke, cockpit gradient brush). Provided via a `LocalVelaColors` composition local. |
| `VelaPillTone` | Enum: `Safe`, `Warning`, `Blocked`, `Neutral`. |
| `VelaStatusPill` | Compact rounded chip with tone-aware fg / bg / border. |
| `VelaSafetyBanner` | Full-width safety strip at the very top of the dashboard. Renders `VELA · cockpit`, the `Read-only lab · Paper-only · No LIVE` header, and six chips: `Mode · {label}`, `REAL locked | REAL UNLOCKED`, `Paper-only`, `No LIVE endpoint`, `Auto Paper disabled`, `Manual submit compiled={true|false}`. |
| `VelaSectionHeader` | Uppercase mint-accent group header + optional subtitle + optional trailing pill. |
| `VelaActionZone` | Bordered isolation container that wraps the Manual Paper submit card. Its border becomes danger-tinted when `armed=true`. |
| `VelaBlockedReasonList` | Presentational chip list for gate reasons (unused today but available for future integration). |
| `VelaMetricCard` | Small key/value tile for the safety strip (unused in this pass; reserved for the Dashboard KPI redesign). |

### 1.3 Dashboard reorganisation (visual only)

[`ui/dashboard/OfflineDashboardScreen.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) received a minimally-invasive edit:

- Added imports for the new theme components.
- Inserted `VelaSafetyBanner` immediately below the `TopAppBar` — it reads `state.modeLabel`, `state.realLocked`, and `manualSubmitState?.compileTimeEnabled`. **No new state was introduced.**
- Inserted `VelaSectionHeader` between logical card groups:
  1. **Estado del sistema** — Status, Last pipeline step, Persistence.
  2. **Demo / diagnóstico** — Demo controls, Alpaca Paper (test) credentials.
  3. **Mercado** — Alpaca real market data, Watchlist, Tick diagnostics, Recent market data.
  4. **Paper account · Riesgo** — Alpaca Paper account, Paper portfolio risk.
  5. **Paper preflight · dry-run** — Preflight card + Paper execution readiness.
  6. **Manual Paper submit · zona protegida** — the isolated one-shot card, wrapped in `VelaActionZone` with an `ARMED / SAFE` trailing pill on the header.
  7. **Auditoría local** — Payload review queue + Dry-run audit.
- Wrapped `PaperManualSubmitCard` inside `VelaActionZone(...) { PaperManualSubmitCard(...) }`. The inner card's rows, buttons, gate logic, and text are **byte-for-byte unchanged**.

### 1.4 Safety strings preserved verbatim

Every safety-forward label from the current build is preserved character-for-character:

- `Mode`, `REAL locked`, `Pipeline`, `Offline demo`
- `Manual Paper submit compiled`, `Manual Paper submit session`, `Paper-only`, `LIVE`, `Auto Paper`
- `Submit method`, `Submit endpoint`, `Required confirmation`, `Type exact one-shot confirmation`, `Submit Paper order once`
- `Arm manual Paper submit for this session`, `Disarm manual Paper submit`, `Refresh submit gates`
- `Manual Paper submit is OFF for this build. …` (error text kept)
- `Paper-only. One manually confirmed attempt. No LIVE, REAL, Auto Paper, retry, cancel, replace, or close-position action.` (card subtitle kept, also reused inside `VelaActionZone`)

Only the container was retinted; no user-visible safety text was reworded.

### 1.5 Safety-scan surface — one adjustment

The scanner regex flags any use of the literal `Auto Paper` outside its file allowlist. The pill originally read `Auto Paper OFF`, which does not contain the words `disabled|false|no order|no auto` that the scanner's fallback allowlist looks for. The label was changed to `Auto Paper disabled` — semantically identical from the user's perspective, and it now matches the `(?i)disabled` fallback so the scan returns `allowed_phase2v_submit=11 suspicious=0 forbidden=0`.

---

## 2. Files touched

| File | Type of change |
| --- | --- |
| [`ui/theme/Theme.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/theme/Theme.kt) | Rewritten: VELA palette + typography + forced dark. |
| [`ui/theme/VelaComponents.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/theme/VelaComponents.kt) | **New**. Visual helpers only. |
| [`ui/dashboard/OfflineDashboardScreen.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) | Imports added; safety banner + section headers inserted; Manual Paper submit card wrapped in `VelaActionZone`. No card body was rewritten. |
| [`docs/vela-android-cockpit-ux-implementation-notes.md`](vela-android-cockpit-ux-implementation-notes.md) | **New** — this document. |
| [`docs/phase-1-progress.md`](phase-1-progress.md) | Appended UX-1 summary block. |

---

## 3. Files deliberately not touched

- Everything under `data/paper/*` (submit / preflight / readiness / gates).
- `PaperManualExecutionFeatureGate`, `PaperFinalPriceStabilityPolicy`, `PaperManualSubmitGate`, `PaperManualSubmitExecutor`, `PaperManualSubmitTokenStore`, `PaperManualOrderSubmitClient`, `OkHttpAlpacaPaperOrderSubmitHttpClient`, `AlpacaPaperSubmitEndpoint`.
- `AlpacaHttpClient`, `AlpacaPaperReadOnlyClient`, `AlpacaPaperTradingEndpoint`.
- Every ViewModel (`OfflineDashboardViewModel`, `PaperManualSubmitViewModel`, and all the other `*ViewModel`s used by the dashboard) — no method was added, moved, or renamed. UI-state fields are consumed as-is.
- Room database, DAOs, migrations, entities.
- `BuildConfig` flags. `build.gradle.kts` untouched. `local.properties` remains clean (no `MANUAL_PAPER_SUBMIT_COMPILED` line).
- Freeze tests (`PaperExecutionSafetyFreezeTest`) and their invariants.
- `scripts/safety-scan.ps1` and `scripts/Check-EmulatorClock.ps1`.
- `G:\vela` was not touched in any way during UX-1.

---

## 4. Confirmation of no trading changes

- No new endpoint, no new HTTP verb, no new call site of `executePostOrder` / `submitOnce`.
- No new state exposed by any ViewModel; only existing `state.modeLabel`, `state.realLocked`, and `manualSubmitState.compileTimeEnabled` are consumed by the safety banner.
- No new arm / confirm / submit surface. The `Submit Paper order once` button remains disabled unless `state.gateAllowed && ...` — that expression is unchanged.
- No credential rendering path was added; the Alpaca Paper Credentials card is unchanged.
- No feature flag toggled at compile time or runtime; the debug and release BuildConfig both continue to emit `MANUAL_PAPER_SUBMIT_COMPILED = false`.
- No LIVE endpoint referenced. No REAL unlock code. No Auto Paper enablement. No cancel / replace / close-position surface.

---

## 5. Validation performed

| Gate | Result |
| --- | --- |
| `local.properties` grep of `MANUAL_PAPER_SUBMIT_COMPILED` | no matches |
| `scripts/safety-scan.ps1` (after the `Auto Paper OFF → Auto Paper disabled` label tweak) | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`, aggregated XML totals `tests=1516 failures=0 errors=0 skipped=0` |
| `:app:assembleDebug --no-build-cache --rerun-tasks` (final, forced-dark theme) | `BUILD SUCCESSFUL in 1m 24s`, 37 tasks executed. Safe APK SHA-256 `af3647739bcb435aebdc9085ba5cda8080a1ca4d0d5c4a9ccc3bf701dc7331a4`. Debug + release `BuildConfig` both emit `MANUAL_PAPER_SUBMIT_COMPILED = false`. |
| `adb install -r app-debug.apk` on VELA_Lite | `Success` |
| `am start com.vela.android.lab/.MainActivity` | app opens without crash; splash → dashboard. |
| Visual verification (screenshot `.ux1-05-dark.png`) | Dashboard renders on dark cockpit surface. `VELA · cockpit` header visible in mint. Six safety pills visible: `Mode · READ_ONLY`, `REAL locked`, `Paper-only`, `No LIVE endpoint`, `Auto Paper disabled`, `Manual submit compiled=false`. `ESTADO DEL SISTEMA` section header renders in uppercase mint. Status card shows `Mode = READ_ONLY`, `REAL locked = true`, `Pipeline = Offline demo`. `Last pipeline step` all `—`. `Persistence 0/0`. No credential fields exposed. No live endpoint text present. `Submit Paper order once` disabled (compile-time flag false; hidden inside the isolated action zone further down the scroll). |

---

## 6. How to continue

1. **Do not** activate `MANUAL_PAPER_SUBMIT_COMPILED` for the purpose of visually testing the armed state. The correct flow is still: separate approval → clock PASS → market open → paso-a-paso arm.
2. If a future runtime attempt lands and audits clean, the isolated `VelaActionZone` container can be promoted to its own destination in a full bottom-nav (spec §C). Until then, keep the container inline as it is today.
3. If any additional UI text is added, run `scripts/safety-scan.ps1` before committing. Any file under `app/src/main` that references the literal `Auto Paper` must contain one of `disabled|false|rejected|reject|forbidden|no order|no auto` on the same line, or be added to the scanner's file allowlist alongside `OfflineDashboardScreen`.
4. If the palette needs a light-mode option later (e.g. for kiosk mode), unfreeze `VelaLabTheme(darkTheme:)` to honor the system value again and fill in the `VelaLightColors` scheme completely. Today the light scheme is a stub for `@Preview` only.

---

## 7. Post-conditions

| Item | Value |
| --- | --- |
| Trading logic modified | **NO** |
| Data-layer files modified | **NO** |
| ViewModels modified | **NO** |
| Submit gates / feature gates / token store / executor / endpoint allowlist modified | **NO** |
| Room schema / DAOs / migrations modified | **NO** |
| `MANUAL_PAPER_SUBMIT_COMPILED` toggled | **NO** — remains `false` in debug and release |
| `local.properties` touched | **NO** (verified clean before and after) |
| `G:\vela` touched | **NO** |
| Runtime submit attempted | **NO** |
| Real Paper `POST /v2/orders` fired | **`0`** |
| REAL locked | **true** |
| LIVE used | **NO** |
| Auto Paper enabled | **NO** |
| Cancel / replace / close introduced | **NO** |
| Foreground service added | **NO** |
| ML introduced | **NO** |
| Freeze-test-relevant contract broken | **NO** (no touched file is asserted against by `PaperExecutionSafetyFreezeTest`) |
| Phase 2.w started | **NO** |

---

## UX-2 — sections, settings and read-only candlestick experience

### Architecture implemented

The production dashboard now renders through a state-only `VelaAppShell`. The bottom bar has exactly five destinations in this order: Inicio, Mercado, Velas, Paper and Más. Más owns Riesgo, Historial y auditoría, Configuración and Diagnóstico. Destinations are a closed enum allowlist; there is no Submit route or deep link.

All pre-existing operational ViewModels remain Activity-scoped. Changing destinations does not recreate them, start a stream, refresh an account, request readiness or change Manual Paper state. `rememberSaveableStateHolder` preserves per-destination UI state. An allowlisted last destination can be restored only when the local visual preference is enabled.

The six-state safety banner is outside each destination's scroll and remains visible on every section:

- Mode READ_ONLY;
- REAL locked;
- Paper-only;
- No LIVE endpoint;
- Auto Paper disabled;
- Manual submit compiled=false for the safe build.

The banner was adjusted after the first Pixel 5 capture so the compiled state occupies its own compact line instead of wrapping into a narrow third column.

### Screen responsibilities

- Inicio: priority summaries for system state, selected market symbol, Paper account, risk and recent local activity. It has no submit action.
- Mercado: visual symbol selector, watchlist, existing IEX controls and compact symbol detail. Tick/history diagnostics are locally collapsible and navigation never starts the stream.
- Velas: local Room-backed 1m OHLC chart and candle detail.
- Paper: the existing account, preflight/draft/preview, readiness, protected Manual Paper card, preview queue and dry-run audit in their required order.
- Riesgo: existing portfolio projection plus explicit information/warning/account-blocker counts; no new rule was created.
- Historial y auditoría: Mercado, Dry-runs, Previews and Submit audit local tabs, with no delete/clear mutation.
- Configuración: visual preferences and locked safety values, plus the existing secure credential editor.
- Diagnóstico: demo generators, pipeline/DB counters, FAKEPACA diagnostics and tick buffer, without credential input fields.

The credential UI is visually split without duplicating its ViewModel or storage logic. Configuración owns Key ID/Secret entry plus Save/Clear and the configured boolean. Diagnóstico owns FAKEPACA telemetry and controls. Saved secret text is never rendered.

### Read-only candles

`CandlesViewModel` depends only on `WatchlistRepository`, the existing Room-backed `MarketDataRepository`, and a clock. Its initialization and Refresh action perform local reads only; no HTTP client, WebSocket or endpoint is present in the candles package.

The source model is the existing `OneMinuteBar`: `symbol`, `bucketStart`, `open`, `high`, `low`, `close`, `syntheticVolume`, `updateCount` and `lastUpdateTime`. The mapper rejects non-finite, non-positive or incoherent OHLC and never manufactures candles from a single price. Bars are sorted chronologically and limited to 30, 50 or 100. The only real timeframe shown is 1m.

The Compose Canvas renders bullish, bearish and doji bodies, wicks, a subtle grid, simplified time/price axes and the latest-price guide. A tap selects a candle and exposes timestamp, OHLC, recorded synthetic pipeline volume, direction and high-low range. The source label is honest: `Room local · pipeline 1m · origen no persistido`. Freshness becomes stale after 120 seconds. Loading, empty, insufficient OHLC, stale, market-closed, connected/disconnected and read-only error presentations are explicit.

### Visual preferences

A dedicated DataStore Preferences file persists only:

- compact/comfortable density;
- default candle count 30/50/100;
- default visual symbol allowlisted against the watchlist;
- advanced-diagnostics visibility;
- remember-last-section;
- allowlisted last destination;
- local/UTC time format.

No credential, account, endpoint, gate, token, order or confirmation key exists in that schema. Safety settings are displayed read-only and there are no REAL, LIVE, Auto Paper or compile-flag toggles.

### Manual Paper preservation and privacy

The Manual Paper card body remains the existing implementation. Every row, gate reason, final/raw age, future-skew tolerance, drift, endpoint/method, confirmation label and button remains present. The frozen enabled expressions are unchanged, including Arm, Disarm, Refresh gates and the one-shot Submit condition. `VelaActionZone` remains around the card.

The only UX-2 addition related to an armed session is window privacy: `FLAG_SECURE` is applied while the existing state reports `sessionArmed=true` and cleared on disarm/disposal. It does not arm, issue a token, request confirmation or alter any gate.

### Final validation

| Gate | Result |
| --- | --- |
| `local.properties` | compile flag count 0; residual override count 0 |
| Unit/source-contract tests | 1,552 tests; 0 failures; 0 errors; 0 skipped |
| Final safety scan | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| Forced safe build | `:app:assembleDebug --no-build-cache --rerun-tasks`; BUILD SUCCESSFUL; 39/39 tasks executed |
| Debug BuildConfig | `MANUAL_PAPER_SUBMIT_COMPILED=false` |
| Release BuildConfig | `MANUAL_PAPER_SUBMIT_COMPILED=false` |
| FeatureGate call site | still receives `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` |
| Safe APK SHA-256 | `F99A0023C1815F92A387E373B07F5CBF17842560BA6D648BD7C7972C3CBE2830` |
| Install target | unique `VELA_Lite`, emulator-5556, API 37; `adb install -r` Success |
| Runtime | app alive; all nine destinations opened; no app crash |
| Responsive | portrait PASS; landscape rotation=1 PASS; font scale 1.30 PASS and restored to 1.0 |
| Paper safe UI | compiled=false; session OFF; Arm disabled; Submit not visible while unarmed |
| Screenshots | `docs/screenshots/ux2/01-inicio.png` through `05-configuracion.png`; no credential value captured |

### Safety post-conditions

Trading logic modified: NO. Submit gates, TTL, drift, clock tolerance and endpoint allowlists modified: NO. `data/paper/**` modified: NO. New network calls: NO. Paper POST executed during UX-2: 0. REAL remains locked, LIVE remains absent and Auto Paper remains disabled. No cancel, replace or close-position capability was added. Phase 2.w was not started. `G:\vela` and the Windows database were not opened, copied or modified.
