# Paper execution safety freeze (Phase 2.s)

This document is the authoritative safety boundary for the VELA Android lab. Phase 2.s froze the pre-execution state; Phase 2.v deliberately amends it with exactly one default-off, session-armed, one-shot Paper orders POST after Juan's written approval.

The companion machine-checked invariants live in [`PaperExecutionSafetyFreezeTest`](../android/app/src/test/kotlin/com/vela/android/lab/safety/PaperExecutionSafetyFreezeTest.kt). The companion static scan lives in [`android/scripts/safety-scan.ps1`](../android/scripts/safety-scan.ps1).

## 1. Current status

**Default state: execution disabled.** The legacy disabled path remains unchanged. A separate Phase 2.v manual Paper submit path exists, but the compile-time build flag defaults OFF, the session arm defaults OFF, and every gate fails closed. No cancellation, replacement, close-position, LIVE, REAL, Auto Paper, retry, or background path exists.

## 2. What exists

These local-only surfaces have been built across Phases 2.a–2.r and are exercised by the test suite + runtime cards:

| Surface | Phase | Behavior |
| --- | --- | --- |
| Read-only Paper account / clock / positions GETs | 2.k | Three URLs only. No mutation. |
| Paper portfolio + risk read view | 2.l | Read-only aggregation of the three GETs. |
| Paper order **preflight engine** | 2.m | Pure-function evaluation. Returns `ALLOWED_DRY_RUN` / `WARNING_ONLY` / `BLOCKED`. Never sends. |
| Paper dry-run **audit journal** | 2.n | Append-only Room table; no credentials persisted. |
| **Price snapshot + freshness gate** | 2.o | Local-only price-source resolver feeding the engine. |
| Local **order request draft** | 2.p | In-memory only; constructor rejects `executionEnabled = true`. |
| Theoretical **payload preview** + **review queue** | 2.q | Immutable preview + Room v4 append-only queue. Constructor enforces `endpointPreview = "DISABLED"`, `httpMethodPreview = "POST_DISABLED"`. |
| **Execution readiness** + **disabled executor** | 2.r | Readiness checker is pure local. Disabled executor's single method always returns `EXECUTION_DISABLED`. |
| **Manual Paper submit boundary** | 2.v | Separate one-method HTTP client, exact Paper endpoint guard, expiring single-use confirmation, fail-closed gate, one-shot executor, append-only Room audit, and session-only UI. Default OFF. |

## 3. What does NOT exist

The following are explicitly absent. Any future phase that adds them must satisfy Section 5 first.

- `DELETE /v2/orders`
- `PATCH /v2/orders` (or any PATCH endpoint)
- order cancellation
- order replacement
- close-position endpoint call
- LIVE Trading API endpoint (`api.alpaca.markets`) as an allowed host
- Auto Paper
- foreground service
- ML / inference dependency
- credential persistence outside the existing Keystore-backed `EncryptedSharedPreferences` store
- Alpaca account id persistence in any Room table
- API key / API header persistence in any Room table

## 4. Frozen invariants

These are the invariants the test suite enforces. Each is a one-line, machine-checkable statement.

1. `state.AppState().realModeLocked == true` by default.
2. `PaperTradingExecutionGuard.canExecuteOrders` is the compile-time constant `false`.
3. `AlpacaHttpClient::class.java.declaredMethods` non-synthetic name set equals exactly `{"executeGet"}`.
4. `AlpacaPaperTradingEndpoint.ALLOWED_READ_ONLY_URLS` equals exactly the three documented GET URLs.
5. `AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet` rejects every URL whose host starts with `https://api.alpaca.markets` (the LIVE host).
6. `AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet` rejects every URL containing `/orders`, `/positions/`, `/account/configurations`, `/account/activities`, `/portfolio/history`, or the case-insensitive `live` substring.
7. `PaperOrderPayloadPreview.ENDPOINT_DISABLED == "DISABLED"` and `PaperOrderPayloadPreview.HTTP_METHOD_POST_DISABLED == "POST_DISABLED"`. The data class `init` block enforces these on every constructor / `copy` call.
8. `PaperOrderDryRunAuditEntity`, `PaperOrderPayloadPreviewEntity`, and `PaperOrderSubmitAuditEntity` declare no field name containing `secret`, `apikey`, `apca`, `accountid`, `credential`, `password`, `bearer`, `authorization`, or `header` (case-insensitive).
9. `IntentSource` enum has exactly one value: `MANUAL_DRY_RUN` (no `AUTO_PAPER`, no `BACKGROUND`).
10. `PaperDisabledOrderExecutor.attemptDisabledExecution(preview).result == DisabledExecutionStatus.EXECUTION_DISABLED` for every input.
11. None of these production classes declares a method whose name (case-insensitively) contains `submitorder`, `placeorder`, `cancelorder`, `replaceorder`, `closeposition`, `executeorder`, `executetrade`, `openposition`:
    - `AlpacaHttpClient`
    - `PaperTradingExecutionGuard`
    - `PaperDisabledOrderExecutor`
    - `PaperExecutionReadinessChecker`
    - `PaperOrderPayloadPreviewBuilder`
    - `PaperOrderPayloadPreviewRepository`
    - `PaperOrderRequestDraftBuilder`
    - `PaperOrderPreflightEngine`
    - `AlpacaPaperReadOnlyClient`
    - `AlpacaPaperTradingEndpoint`
12. `MarketDataSource` enum has no `ALPACA_LIVE` value (already covered by the Phase 2.e architectural test; re-asserted here).
13. `PaperExecutionReadinessSnapshot` constructor rejects `executionEnabled = true`, `liveEndpointAllowed = true`, `paperPostOrdersAllowed = true`, `autoPaperEnabled = true`, `foregroundServiceEnabled = true` — including via `.copy(...)`.
14. A source-wide scan of every production Kotlin file rejects any declared method whose name contains `submitOrder`, `placeOrder`, `cancelOrder`, `replaceOrder`, `closePosition`, or `executeOrder` (case-insensitive).
15. A source-wide scan permits the one exact Phase 2.v Paper orders POST builder/literal and rejects every other HTTP mutation builder/annotation or executable Paper mutation endpoint literal. Local Room cleanup SQL is not an HTTP endpoint and remains outside this invariant.
16. Production source contains no call to `AppState.unlockRealMode()`, no execution-related assignment to `true`, and no `AUTO_PAPER` state; the negative-only `AUTO_PAPER_DISABLED` readiness reason remains allowed.
17. `AlpacaPaperOrderSubmitHttpClient` declares exactly `executePostOrder`; `AlpacaPaperSubmitEndpoint` allows exactly `POST https://paper-api.alpaca.markets/v2/orders`; every other method, host, or path is rejected.
18. The production call graph has exactly one caller of `executePostOrder` (`PaperManualOrderSubmitClient`) and exactly one caller of that client's `submitOnce` (`PaperManualSubmitExecutor`). `AlpacaHttpClient` and `AlpacaPaperTradingEndpoint` remain GET-only and `PaperTradingExecutionGuard.canExecuteOrders` remains false.
19. Manual submission requires both the default-off compile flag and an in-memory session arm, a fresh matching snapshot, an expiring single-use confirmation, and a durable audit-start event before I/O.
20. The submit audit DAO is append-only and its entity stores no credential, account id, API header, or raw body.

## 5. Phase 2.v approval checklist and continuing exclusions

Juan's written approval satisfied the Phase 2.u human gate. Phase 2.v meets the implementation prerequisites as follows:

### 5.1 Mandatory controls (satisfied)

- [x] `PaperExecutionSafetyFreezeTest` remains green with the amended exact Paper POST boundary.
- [x] The dashboard requires a default-off compile flag, an in-memory session arm, and exact typed confirmation tied to the current preview.
- [x] The legacy `PaperTradingExecutionGuard.canExecuteOrders` remains false; the approved path is a separate fail-closed executor rather than a weakened legacy guard.
- [x] `AlpacaHttpClient` remains GET-only. A separate one-method `AlpacaPaperOrderSubmitHttpClient` owns the only POST.
- [x] `AlpacaPaperSubmitEndpoint` allows only `POST https://paper-api.alpaca.markets/v2/orders` and rejects LIVE, other verbs, other hosts, and every other path.
- [x] `PaperOrderSubmitAuditEntity` is separate and stores no credential, API header, account id, or raw body.
- [x] The Phase 2.q immutable review queue still has no `update` / `delete` / `clear` method.
- [x] REAL stays locked and the production scan finds no call to `AppState.unlockRealMode()`.
- [x] Freeze tests constrain both the one-method interface and its production call graph.
- [x] No request is sent until a durable `ATTEMPT_STARTED` audit row exists; no automatic retry exists.

### 5.2 Continuing exclusions

- Auto Paper.
- Foreground service.
- LIVE Trading endpoint.
- ML / inference.
- Backup / cloud sync of any Room table.

## 6. Where the contract is enforced

- `app/src/test/kotlin/com/vela/android/lab/safety/PaperExecutionSafetyFreezeTest.kt` — machine-checked invariants from Section 4.
- `app/src/test/kotlin/com/vela/android/lab/data/paper/preflight/*Test.kt` — phase-specific reflection contracts (Phases 2.k–2.r).
- `android/scripts/safety-scan.ps1` — static categorized scan over `app/src/main` for dangerous substrings; classifies hits as `ALLOWED_PHASE_2V_PAPER_SUBMIT_BOUNDARY`, `ALLOWED_NEGATIVE_DOC_OR_GUARD`, `SUSPICIOUS_PRODUCTION_HIT`, or `FORBIDDEN_HIT`. Intended as a CI smoke-check on top of the unit tests, never as a replacement for them.

## 7. Revision history

- 2026-06-20 — Phase 2.s. Initial freeze.
- 2026-06-20 — Phase 2.v. Amended for the exact default-off one-shot Paper submit boundary.
