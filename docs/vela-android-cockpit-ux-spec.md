# VELA Android — cockpit UX/UI spec (UX-0)

**Docs-only.** This file defines the visual and information-architecture direction for a future VELA Android cockpit. It contains **no code**, changes **no source**, activates **no feature flag**, ships **no build**, and does **not** start Phase 2.w.

It uses `G:\vela` (the Windows VELA workstation) strictly as a **read-only** visual reference. No file was added, moved, deleted, or executed under `G:\vela`. No database, `.env`, secret, credential, token, key, or certificate under `G:\vela` was opened. `vela.db` was located but **not** read.

---

## A. Referencia estética observada en `G:\vela`

### A.1 Inspection scope (read-only)

Inspected (read-only reads and directory listings only):

- `G:\vela\` top-level listing.
- `G:\vela\winui\Vela.WinUI\App.xaml` — WinUI 3 application shell (theme merge only).
- `G:\vela\winui\Vela.WinUI\MainWindow.Content.xml` — declarative XAML tree with theme tokens, styles and structural markup (281 KB, sampled by grep + partial reads for the resource dictionary and card templates).
- `G:\vela\winui\Vela.WinUI\StatusCard.xaml` — status card UserControl stub.
- `G:\vela\winui\Vela.WinUI\Strings\en-US\Resources.resw` — English UI strings (first ~600 lines).
- `G:\vela\winui\Vela.WinUI\Strings\es-ES\Resources.resw` — line count only.
- `G:\vela\winui\Vela.WinUI\Models\` — file list (schema names only).
- `G:\vela\winui\Vela.WinUI\Assets\` — empty directory listing.
- `G:\vela\branding\` — file listing (icons and installer bitmap).
- `G:\vela\vela_logo.svg` — SVG source of the brand mark.
- `G:\vela\app\ui\main_window.py` — first ~120 lines only, to observe candlestick chart tokens.
- `G:\vela\docs\` — file listing (Windows packaging docs, not read in depth).

Explicitly **excluded** from any read, per the UX-0 safety rules:

- `G:\vela\build\desktop-bundle\validation-data\database\vela.db` — the only `.db` located; detected via `Glob`, never opened.
- Every `*.db`, `*.sqlite`, `*.sqlite3`, `*.accdb`, `*.mdb` (only the one above exists).
- `.env`, `.env.example`, and any file with `secret`, `credential`, `token`, `key`, `certificate` in path or content.
- `G:\vela\.venv\`, `G:\vela\logs\`, `G:\vela\build\`, `G:\vela\dist\`, `G:\vela\__pycache__\`.
- All Python source under `G:\vela\app\` other than the ~120-line UI snippet above; no business-logic file was opened.
- All `MainWindow.*.cs` partial C# code-behind files.

### A.2 Palette (verbatim from the WinUI resource dictionary)

The palette is defined as named brushes inside `MainWindow.Content.xml`. It is a **cool dark navy + mint accent** theme.

| Semantic token | Value |
| --- | --- |
| `VelaBackgroundGradient` | linear `#05111C → #071928 → #04101A` (top-left to bottom-right) |
| `VelaBackgroundGlowMint` | radial `#2A2DE2B7 → #001B2A36` (upper-left glow, ~65% opacity) |
| `VelaBackgroundGlowBlue` | radial `#1A1D8FE8 → #0012283A` (bottom-right glow, ~55% opacity) |
| `VelaTextPrimaryBrush` | `#E6FAFF` (icy white) |
| `VelaTextMutedBrush` | `#87AFC0` (muted blue-gray) |
| `VelaSurfaceBrush` | `#0B1A28` (card fill) |
| `VelaSurfaceRaisedBrush` | `#0E2232` (elevated surface) |
| `VelaStrokeBrush` | `#1E4D63` (default stroke) |
| `VelaAccentBrush` | `#2DE2B7` (mint — signature) |
| `VelaAccentMutedBrush` | `#195A59` (muted mint) |
| `VelaDangerBrush` | `#D76A76` (soft red — warnings) |
| Sidebar background | `#060F19` |
| Sidebar stroke (right edge only) | `#1B4255` |
| Brand chip fill | `#0B1E2D` w/ `#1D5468` stroke |
| Chip fill (positive) | `#0C1B29` w/ `#1F4C61` stroke |
| Chip fill (danger) | `#331B1F` w/ danger stroke |
| Chip text (danger) | `#FFB4BA` |
| Chip text (warning) | `#FFD7AC` |

From the Python UI (`app/ui/main_window.py`) candlestick tokens observed for the desktop chart:

| Token | Value |
| --- | --- |
| Candle body up | `#34D399` (green) |
| Candle body down | `#F87171` (red) |
| Candle wick / axis | `#4A5568` |
| Chart background | `#111827` |

From the SVG mark (`vela_logo.svg`):

| Token | Value |
| --- | --- |
| Mark background | linear `#08224f → #061a40` (deep navy) |
| Flame gradient | linear `#8bf6d5 → #2e79ff` (mint to blue) with a Gaussian glow filter |
| Flame outline / wordmark | `#f3f6ff` (near-white) |
| Wordmark | `Arial 800`, letter-spacing 8 px, sole word `VELA` |

### A.3 Typography

- Primary UI face on Windows: **Segoe UI Variable** (declared as the `FontFamily` on every `Button`, `ComboBox`, `TextBox`, `PasswordBox`, `TextBlock` style).
- Wordmark on the brand asset: **Arial 800**, letter-spaced.
- Observed sizes in the header/hero region: **24 pt** for the brand mark and section title, **13 pt** for subtitles, **11–12 pt** for chip labels and helper text.

### A.4 Shape and elevation grammar

- **Corner radius**: `12` for content cards, `10` for buttons and inputs, `8` for secondary containers, `6–7` for chips and pills.
- **Padding**: `18–24` px for card interiors, `10–15` px for chips, `11,7` for buttons.
- **Border thickness**: `1` px everywhere; strokes are always the low-saturation `VelaStrokeBrush` family.
- **Background layering**: the app root is the tri-stop dark gradient; on top of it, two large radial glows (mint upper-left, blue bottom-right) create the "signal in dark space" mood. Cards sit as subtle raised rectangles with 1 px stroke, never a hard shadow.

### A.5 Layout observed

- Two-column shell: **264 px left sidebar** (brand + mode picker + navigation + language) and a fluid main region.
- **36 px bottom status bar** for footer chips (`Local service:`, `Local data:`, `Alpaca:`, `VELA WinUI shell v0.1`).
- Main area is a scroll of section titles + card grids: **hero header** (`Operations dashboard` + subtitle + status chip), **KPI row** of `StatusCard` tiles, then a stack of larger interactive cards (Paper Portfolio, Market chart, Live activity, Recent Activity, Operational Control, Simulation, Auto Paper, Real Money Operation).

### A.6 Semantic vocabulary observed

Extracted from `Strings\en-US\Resources.resw`. This is the tone of voice the desktop app already uses; it will guide the Android string set.

- **Modes**: `Read only`, `Simulated`, `REAL trading locked`.
- **Safety badges**: `REAL locked`, `REAL mode is locked for safety.`, `Live trading remains locked.`, `Emergency stop`.
- **Navigation**: `Dashboard`, `Signals`, `Simulation`, `Journal`, `Settings`, `About`.
- **KPI cards**: `Application`, `Alpaca`, `Local data`, `Market source`, `Total PnL`, `Trades`, `Win rate`, `Simulator`.
- **Paper Portfolio**: `Portfolio value`, `Daily Change ($)`, `Daily Change (%)`, `Cash (actual available)`, `Buying power (simulated margin)`, `Open position value`, `Unrealized P/L`, `Last updated`, `Buying Power may be higher than Cash in Alpaca Paper because margin buying power is simulated. It is not additional cash.`
- **Market**: `Market chart`, `Recent 1-minute market candles`, `Market Reading`, `Start reading`, `Stop reading`, `Waiting for recent candle data.`, `Market universe`, `Active symbols`, `Asset class`, `Market-hours behavior`.
- **Live activity**: `Signal`, `Last Action`, `Reason`, `Active Symbol`, `Open Position`, `Unrealized PnL`, `Session Result`, `Market Source`.
- **Recent Activity log**: `Time`, `Event`, `Symbol`, `Result`, `Waiting for recent market activity.`
- **Operational Control**: `Start or pause market reading and review simulation and real-money readiness.`
- **Auto Paper**: `Requested notional`, `Buying power cap`, `Risk-base cap`, `Paper positions`, `Latest order`, `Start Auto Paper`, `Stop Auto Paper`.
- **Real Money Operation**: `Start real operation`, `Stop real operation`, `Emergency stop`.
- **Settings**: `Language`, `Learning`, `Operational schedule`, `Application and local connection`, `Alpaca Connection` (with `Connect Alpaca paper credentials for market data checks. Live trading remains locked.`), `Diagnostics export`.
- **Journal**: `Auto Paper decisions, order attempts, reconciliation, and strategy reasons.`

---

## B. Traducción a VELA Android

The Android cockpit takes the **mood, palette, and semantic vocabulary** of the desktop, but not the layout: a phone is portrait, single-column, thumb-driven. No pixel, asset, or piece of code is copied.

### B.1 Design-token mapping (Material 3 semantic → VELA)

The Android app currently ships a Material 3 dark palette. UX-0 proposes to **re-theme** Material 3 tokens with the desktop's VELA palette, without touching Compose components' structural code:

| Material 3 token | VELA Android value | Source token |
| --- | --- | --- |
| `md.sys.color.background` | `#04101A` (radial gradient base) | `VelaBackgroundGradient` bottom stop |
| `md.sys.color.surface` | `#0B1A28` | `VelaSurfaceBrush` |
| `md.sys.color.surface-variant` | `#0E2232` | `VelaSurfaceRaisedBrush` |
| `md.sys.color.on-surface` | `#E6FAFF` | `VelaTextPrimaryBrush` |
| `md.sys.color.on-surface-variant` | `#87AFC0` | `VelaTextMutedBrush` |
| `md.sys.color.outline` | `#1E4D63` | `VelaStrokeBrush` |
| `md.sys.color.primary` | `#2DE2B7` | `VelaAccentBrush` |
| `md.sys.color.primary-container` | `#195A59` | `VelaAccentMutedBrush` |
| `md.sys.color.error` | `#D76A76` | `VelaDangerBrush` |
| `md.sys.color.error-container` | `#331B1F` | Chip fill (danger) |
| `md.sys.color.warning` (custom) | `#FFD7AC` | Chip text (warning) |

The scrim gradient behind the whole screen becomes an Android `Brush.linearGradient(#05111C → #071928 → #04101A)` drawn once at the root, with the two soft radial glows implemented as `RadialGradient` `Modifier.drawBehind` blobs on the top scaffold. On phones, the glows are smaller and off-screen at the corners so they read as ambient atmosphere without stealing scroll performance.

### B.2 Shape and spacing scale

- Cards: `Modifier.clip(RoundedCornerShape(12.dp))` on top of `Modifier.background(surface)` with `Modifier.border(1.dp, outline, RoundedCornerShape(12.dp))`.
- Buttons: `RoundedCornerShape(10.dp)`, `Modifier.padding(horizontal = 11.dp, vertical = 7.dp)`.
- Chips: `RoundedCornerShape(6.dp)` to `RoundedCornerShape(7.dp)`.
- Card content padding: `20.dp`.
- Row-to-row vertical rhythm: `8.dp` (labeled row), `4.dp` (helper text).
- No hard drop shadows. Elevation is expressed by the `SurfaceRaised` colour, not by `Modifier.shadow`.

### B.3 Typography plan

- App font family: system default (**Roboto Flex** on Android 12+ as it maps to the same "variable geometric humanist" mood as Segoe UI Variable), no external font file to avoid asset copying.
- Title Large: 24 sp, weight 700.
- Title Medium: 18 sp, weight 600.
- Body Large: 15 sp.
- Body Small: 13 sp.
- Label Small (chip): 11 sp, letter-spacing +0.4.

### B.4 Interaction grammar

- Read-only content on the dashboard uses **passive cards** (no primary button, no chevron). Buttons are reserved for `Refresh`, `Start`, `Stop`, `Save`, `Emergency stop` — the same verbs used by the desktop.
- Every mutating action (there is only one in Phase 2.v: **`Submit Paper order once`**) is placed in a **visually isolated card** with its own outline, mandatory checkbox and typed confirmation, mirroring the desktop's "Real Money Operation" isolation but scoped to the manual Paper submit.
- `Emergency stop` on the desktop maps to the app-level `PaperManualExecutionFeatureGate.activateEmergencyDisable` and lives inside the same isolated card, not elsewhere on the screen.

---

## C. Estructura futura sugerida (bottom-nav)

Android reads best as a **bottom-nav cockpit** with five destinations. Each destination is a scroll of the current OfflineDashboard cards, re-grouped by concern. Nothing changes today; this is the target arrangement.

| Nav item | Icon idea | Contents (existing cards, re-parented) |
| --- | --- | --- |
| **Dashboard** | Filled candle | `Status`, `Last pipeline step`, KPI tiles derived from `Persistence`, hero header (`Operations dashboard`) |
| **Mercado** | Line-chart | `Alpaca real market data — read only`, `Watchlist — read only`, `Tick / quote diagnostics`, `Recent market data`, `Alpaca test stream` (behind "Developer" chevron) |
| **Paper Trading** | Envelope with lock | `Alpaca Paper account — read only`, `Paper order preflight — dry run only`, `Paper execution readiness — disabled`, **isolated** `Manual Paper submit — one-shot` |
| **Riesgo** | Shield | `Paper portfolio risk — read only`, forward-looking safety chips, planned risk limits |
| **Historial / Auditoría** | Timeline | `Payload review queue — local only`, `Dry-run audit — local only`, future submit audit |
| **Ajustes / Seguridad** | Gear + shield (single tile at top) | Credentials card (Keystore-only inputs, values never rendered), REAL locked state, LIVE-forbidden statement, safety-scan status, per-build BuildConfig booleans, "About / diagnostics export" analogue |

`Demo controls` (`Generate demo BTC/USD update`, `Generate demo SPY update`, `Clear local demo state`) moves under **Ajustes / Seguridad → Developer**, guarded by a `debug`-only visibility flag consistent with the existing `BuildConfig.DEBUG` check.

The bottom-nav bar itself is a `NavigationBar` at 80 dp height, using `VelaSurfaceRaisedBrush` as background and `VelaAccentBrush` for the selected pill.

---

## D. Cards actuales a reorganizar

Mapping the current `OfflineDashboardScreen.kt` sections onto the future destinations. **This is a plan of record only — no card is moved by this document.**

| Current card (existing) | Target destination | Notes |
| --- | --- | --- |
| Status | Dashboard | Becomes a permanent header chip strip: `Mode`, `REAL locked`, `Pipeline`. |
| Last pipeline step | Dashboard | KPI grid (Symbol / Price / Bar close / Feature direction / Signal state / Signal score) rendered as 2×3 tile grid. |
| Persistence | Dashboard | Two numeric tiles (`Persisted bars`, `Journal events`). |
| Demo controls | Ajustes → Developer | Debug-only visibility; unchanged behavior. |
| Alpaca test stream (`Alpaca Paper Credentials` FAKEPACA card) | Ajustes → Developer | Same reason. |
| Alpaca real market data — read only | Mercado (top) | Contains `Start real market data stream` / `Stop real market data stream` (unchanged verbs). |
| Watchlist — read only | Mercado | Same list of `AAPL/MSFT/NVDA/QQQ/SPY` with `received/persisted/last`. |
| Tick / quote diagnostics | Mercado | Detail row expandable; keeps `No ticks yet.` empty state. |
| Recent market data | Mercado | Bottom card; `Refresh` button preserved. |
| Alpaca Paper account — read only | Paper Trading (top) | Keeps `Refresh Paper Account`; the `Credentials configured: true` row remains but never renders the credential itself. |
| Paper portfolio risk — read only | Riesgo | Aggregated view; `Refresh portfolio risk` preserved. |
| Paper order preflight — dry run only | Paper Trading | The `Run dry-run preflight` button and its "No order will be sent." helper are kept verbatim. |
| Paper execution readiness — disabled | Paper Trading | The negative fact list (`executionEnabled=false`, `REAL locked=true`, `Paper POST /orders allowed=false`, `LIVE endpoint allowed=false`, `Auto Paper=false`, `Foreground service=false`) stays as-is — it is the safety-forward summary. |
| **Manual Paper submit — one-shot** | Paper Trading (isolated card at the very bottom, framed by `VelaDangerBrush` when armed) | The Phase 2.v boundary is unchanged in code; only the visual isolation changes. Verbatim rows kept: `Manual Paper submit compiled`, `Manual Paper submit session`, `Paper-only`, `REAL locked`, `LIVE`, `Auto Paper`, `Submit method`, `Submit endpoint`, `Required confirmation`, `Type exact one-shot confirmation`, `Submit Paper order once`. |
| Payload review queue — local only | Historial | Immutable append-only list with `Refresh preview queue`. |
| Dry-run audit — local only | Historial | Same list model with `Refresh audit`. |

---

## E. Reglas visuales de seguridad

These are **non-negotiable** and must survive every future visual iteration. They map the safety invariants of `docs/paper-execution-safety-freeze.md` into UI shape.

1. **`REAL locked` is always visible on-screen**, either as a top-strip chip (`RealLockedBadge.Text = "REAL locked"` from the desktop) or as the leading label of the current tab's status header. It never fits behind a scroll or a fold.
2. **`No LIVE endpoint` is stated explicitly** in the Alpaca card ("Live trading remains locked." — kept verbatim from the desktop) and in the Manual Paper submit — one-shot card description ("No LIVE, REAL, Auto Paper, retry, cancel, replace, or close-position action."). Both strings are hard-coded, not computed.
3. **`Auto Paper=false` is a visible row**, both in Paper execution readiness and inside the Manual Paper submit — one-shot card. It is never hidden even when the value is falsy, because visible-false is the safety statement.
4. **`Paper-only` label is unambiguous**: the isolated card starts with the description "Paper-only. One manually confirmed attempt. No LIVE, REAL, Auto Paper, retry, cancel, replace, or close-position action." — text unchanged from the current implementation.
5. **Read actions and mutating actions are visually distinct.** Read buttons (`Refresh`, `Start real market data stream`, `Stop real market data stream`, `Refresh Paper Account`, `Refresh portfolio risk`, `Run dry-run preflight`, `Check readiness`) are the primary accent color. The single mutating button (`Submit Paper order once`) uses `VelaDangerBrush` outline, is disabled until `gateAllowed = true`, and is placed on a distinct card that is scroll-adjacent to the confirmation input.
6. **Confirmation must be typed by Juan, in-app.** The `Type exact one-shot confirmation` field is a plain `OutlinedTextField` with no autofill, no clipboard suggestion enhancement, no long-press insert helper. Rationale: the operator promise `SUBMIT PAPER {symbol} {SIDE} {qty}` must be a deliberate physical act.
7. **Credentials are never rendered.** The Alpaca card shows `Credentials configured: true|false` only; the Key ID/Secret input on the Alpaca Paper Credentials card renders placeholders only when empty, and the fields are `PasswordVisualTransformation` regardless. No logcat, no error toast, no screenshot capture path may spill credentials.
8. **Emergency stop is always one tap away** when a session is armed. It maps to the desktop `Emergency stop` button and toggles the app-level `PaperManualExecutionFeatureGate.activateEmergencyDisable`. It must be at the top of the Manual Paper submit — one-shot card whenever `sessionArmed = true`.
9. **State chips carry the safety semantics, not just cosmetic status.** `SAFE` chips use `VelaAccentBrush`, `READY` uses accent-muted, `BLOCKED` uses `VelaDangerBrush`. Never re-use accent for warning; never re-use warning for blocked.
10. **The dashboard's default landing tab is `Dashboard`, not `Paper Trading`.** The mutating card is reachable only after an explicit tab switch. This mirrors the desktop's decision to place `Real Money Operation` below the fold, guarded by `REAL locked` state.
11. **No screenshots leak safety-critical state.** Cards showing session arm, token issuance, or a raw confirmation string must respect `FLAG_SECURE` on the window; when armed, this flag is set until the executor finishes and the session disarms.

---

## F. No implementar todavía

Explicit non-goals of UX-0:

- **No Phase 2.w.** Nothing in this document authorizes it.
- **No code changes.** No file in `android/app/src/main/**` is edited by UX-0.
- **No new trading capabilities.** No new endpoint, no new HTTP verb, no new mutation surface.
- **No Auto Paper.** The desktop has one; the Android cockpit continues to show `Auto Paper=false` as a safety invariant.
- **No REAL unlock.** `AppState.unlockRealMode()` remains unreachable from any production callsite.
- **No LIVE endpoint.** `api.alpaca.markets` remains only in rejection guards and the `Live trading remains locked` copy.
- **No cancel / replace / close.** No design element in this spec creates room for those verbs.
- **No runtime submit action** is implied by adopting this spec. Building the future UI does not itself submit anything.
- **No POST.** UX-0 changes zero HTTP surface.
- **No background/foreground service.** The Android cockpit is foreground-only.
- **No ML.** No inference, no learning path, no on-device model.
- **No copying of `G:\vela` assets or code into `G:\vela-android`.** The only artifacts that migrate are the color hex values, the naming vocabulary, and the layout intuition — all documented above.

---

## G. Cómo continuar después del runtime Paper exitoso

1. **First, complete the controlled Paper runtime attempt** already gated by clock + `marketOpen` + Juan's manual confirmation. This spec is dormant until that attempt lands.
2. **Then audit the real Paper submit result**: exactly one `POST /v2/orders`, one audit row, no credential leak, token consumed, REAL still locked, `MANUAL_PAPER_SUBMIT_COMPILED` restored to `false`. This audit becomes the reference point for any UI work.
3. **Only after those two milestones**, promote this document to an implementation phase (`UX-1` or similar). That implementation phase would:
   - introduce a `VelaTheme` Compose theme with the palette above, without removing existing Material 3 fallbacks;
   - refactor `OfflineDashboardScreen.kt` into a `Scaffold` with `NavigationBar` and five destination composables, keeping every currently-visible row verbatim;
   - preserve every safety string byte-for-byte (`Manual Paper submit compiled`, `Manual Paper submit session`, `Paper-only`, `REAL locked`, `LIVE`, `Auto Paper`, `Required confirmation: SUBMIT PAPER SPY BUY 1`, etc.);
   - run the safety-scan and freeze-test invariants against the new tree before merging (`allowed_phase2v_submit=11 suspicious=0 forbidden=0` remains the merge gate).
4. **Keep the freeze tests green** at every iteration. `PaperExecutionSafetyFreezeTest` INV1–INV18, the `AlpacaPaperSubmitEndpoint` guard, and the `MANUAL_PAPER_SUBMIT_COMPILED` release-hard-coded `false` invariant survive re-theming untouched.

---

## Post-conditions of UX-0

| Item | Value |
| --- | --- |
| Files created under `G:\vela` | **NONE** |
| Files modified under `G:\vela` | **NONE** |
| Files deleted / moved under `G:\vela` | **NONE** |
| `vela.db` opened / copied / read | **NO** |
| Any `.env`, secret, credential, token, key read | **NO** |
| Business-logic Python code read | **NO** (only ~120 lines of UI file for candle tokens) |
| Files created under `G:\vela-android` | this doc + a summary note in `phase-1-progress.md` |
| Files modified under `G:\vela-android` (code) | **NONE** |
| `MANUAL_PAPER_SUBMIT_COMPILED` toggled | **NO** — still `false` |
| Controlled APK built | **NO** |
| Runtime submit attempted | **NO** |
| POST executed | **`0`** |
| REAL locked | **true** |
| LIVE used | **NO** |
| Auto Paper enabled | **NO** |
| Phase 2.w started | **NO** |
