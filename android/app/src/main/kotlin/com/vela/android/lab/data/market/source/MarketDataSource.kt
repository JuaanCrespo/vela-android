package com.vela.android.lab.data.market.source

/**
 * Enumeration of market-data sources the lab is allowed to use.
 *
 * **`ALPACA_LIVE` is intentionally not present in this enum.** Live
 * trading is out of scope for the entire Android lab roadmap; making
 * the live case un-representable in the type system is a stronger
 * guarantee than a runtime boolean flag would be.
 *
 * `OFFLINE` and `OFFLINE_STUB` are read-only by construction. The
 * `ALPACA_PAPER` value exists to document the intended Phase 2.b
 * target; **no implementation is wired to it in Phase 2.a**.
 */
enum class MarketDataSource(val displayLabel: String) {
    /** No data, no network. Used as the safe default at boot. */
    OFFLINE("Offline"),

    /** Deterministic local-only stub used for tests and the demo UI. */
    OFFLINE_STUB("Offline stub"),

    /**
     * Alpaca Market Data **test stream** at
     * `wss://stream.data.alpaca.markets/v2/test`. Read-only. The
     * only Phase 2.b client that connects to a real Alpaca endpoint.
     * Subscribes to the synthetic test symbol `FAKEPACA`.
     */
    ALPACA_TEST_STREAM("Alpaca test stream"),

    /**
     * Alpaca Market Data **real stock stream** at
     * `wss://stream.data.alpaca.markets/v2/iex`. Read-only IEX feed.
     * Subscribes to real symbols (SPY first); never reaches a
     * trading host, never opens an order, never reads account state.
     */
    ALPACA_STOCK_IEX("Alpaca stock (IEX)"),

    /** Alpaca Paper market data — future target, not yet implemented. */
    ALPACA_PAPER("Alpaca Paper"),
}
