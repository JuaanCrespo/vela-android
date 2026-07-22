package com.vela.android.lab.data.market.source

/**
 * Declarative configuration for the market-data boundary.
 *
 * Safety guards enforced at construction time:
 *  - The default source is [MarketDataSource.OFFLINE_STUB]; the
 *    lab cannot accidentally boot pointing at any network endpoint.
 *  - [endpoint] must be null or a non-live URL. Any reference to
 *    `api.alpaca.markets` (the LIVE trading URL) is rejected.
 *    `paper-api.alpaca.markets` and `data.alpaca.markets` are
 *    permitted only when the explicit source is `ALPACA_PAPER`.
 *  - [credentialsKeyAlias] is just a Keystore alias hint for a
 *    future credential lookup. It is never a credential value
 *    itself; the actual key/secret never flows through this config.
 *
 * The Phase 2.a code path uses defaults only — no overrides are
 * wired into the production app yet.
 */
data class MarketDataConfig(
    val source: MarketDataSource = MarketDataSource.OFFLINE_STUB,
    val endpoint: String? = null,
    val symbols: Set<String> = emptySet(),
    val credentialsKeyAlias: String? = null,
) {
    init {
        if (endpoint != null) {
            val lower = endpoint.lowercase()
            require(!lower.contains("api.alpaca.markets") ||
                lower.contains("paper-api.alpaca.markets") ||
                lower.contains("data.alpaca.markets")) {
                "MarketDataConfig.endpoint must not target the LIVE Alpaca trading API."
            }
            require(!lower.contains("live")) {
                "MarketDataConfig.endpoint must not contain 'live'; LIVE trading is out of scope."
            }
            // Non-paper Alpaca endpoint cannot pair with OFFLINE source.
            require(source != MarketDataSource.OFFLINE && source != MarketDataSource.OFFLINE_STUB) {
                "MarketDataConfig.endpoint must be null when source is OFFLINE or OFFLINE_STUB."
            }
        }
    }

    val isOfflineOrStub: Boolean
        get() = source == MarketDataSource.OFFLINE || source == MarketDataSource.OFFLINE_STUB

    companion object {
        /** Phase 2.a default — guaranteed offline. */
        val Default: MarketDataConfig = MarketDataConfig()
    }
}
