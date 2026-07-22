package com.vela.android.lab.data.paper.preflight

/**
 * Phase 2.m placeholder execution guard.
 *
 * **This object exposes one and only one public surface:
 * [canExecuteOrders], which is hard-coded to `false`.**
 *
 * It deliberately has no `submitOrder`, `cancelOrder`, `replaceOrder`,
 * `closePosition`, or any other mutation method. The Phase 2.m
 * reflection contract test scans every declared method on this
 * object and asserts no mutation-shaped name exists.
 *
 * Future phases that wish to enable real Paper order submission
 * **must** introduce a new class with a new contract review — they
 * cannot turn this guard into an executor by adding methods, because
 * the contract test would fail.
 */
object PaperTradingExecutionGuard {

    /**
     * Hard-coded `false`. The lab has no execution surface for
     * orders, cancels, replaces, or position closes — by design.
     */
    const val canExecuteOrders: Boolean = false

    /**
     * Human-readable rationale, for the dashboard footer and for
     * the reflection-contract documentation.
     */
    const val rationale: String =
        "Phase 2.m allows local dry-run preflight only. No execution path exists. " +
            "AlpacaHttpClient exposes only executeGet. REAL remains locked."
}
