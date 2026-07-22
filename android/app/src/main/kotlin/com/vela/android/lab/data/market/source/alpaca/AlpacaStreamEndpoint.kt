package com.vela.android.lab.data.market.source.alpaca

/**
 * Hard-coded gate for the Alpaca Market Data WebSocket endpoints
 * the lab is allowed to open. **Only** read-only market-data feeds
 * are permitted; every trading-host URL and trading-path fragment
 * is rejected at construction time.
 *
 * Two feeds are recognised:
 *
 *  - [TEST_STREAM_URL] — `wss://stream.data.alpaca.markets/v2/test`,
 *    the synthetic FAKEPACA feed introduced in Phase 2.b.
 *  - [IEX_STREAM_URL] — `wss://stream.data.alpaca.markets/v2/iex`,
 *    the read-only real-stock IEX feed used by Phase 2.e for SPY.
 *
 * `v2/delayed_sip` and `v2/sip` are *named* here so the test suite
 * can lock the default away from them (some Alpaca accounts can
 * subscribe to SIP; we deliberately stay on IEX), but they are
 * **not** registered as allowed endpoints by the helpers below.
 *
 * Live trading hosts (`api.alpaca.markets`), the paper-trading host
 * (`paper-api.alpaca.markets`), and any path containing
 * `/orders`, `/positions`, `/account`, `/trading`, or `/portfolio`
 * are explicitly refused so that a future refactor cannot
 * accidentally repoint a stream client at a trading API.
 */
object AlpacaStreamEndpoint {

    /** Phase 2.b test stream URL (FAKEPACA). */
    const val TEST_STREAM_URL: String = "wss://stream.data.alpaca.markets/v2/test"

    /** Phase 2.e real-stock IEX feed URL. */
    const val IEX_STREAM_URL: String = "wss://stream.data.alpaca.markets/v2/iex"

    /** Synthetic symbol exposed by [TEST_STREAM_URL]. */
    const val TEST_SYMBOL: String = "FAKEPACA"

    /** Phase 2.e initial real symbol. */
    const val STOCK_PRIMARY_SYMBOL: String = "SPY"

    /**
     * SIP / delayed_sip URLs that this build is **not** allowed to
     * use, even though some Alpaca subscriptions could permit them.
     * We keep them here so tests can pin the default to IEX.
     */
    const val SIP_STREAM_URL: String = "wss://stream.data.alpaca.markets/v2/sip"
    const val DELAYED_SIP_STREAM_URL: String =
        "wss://stream.data.alpaca.markets/v2/delayed_sip"

    /** Every URL the lab is permitted to open at construction time. */
    val ALLOWED_MARKET_DATA_URLS: Set<String> = setOf(
        TEST_STREAM_URL,
        IEX_STREAM_URL,
    )

    private val FORBIDDEN_HOSTS: List<String> = listOf(
        "api.alpaca.markets",
        "paper-api.alpaca.markets",
    )

    private val FORBIDDEN_PATH_FRAGMENTS: List<String> = listOf(
        "/orders",
        "/positions",
        "/account",
        "/trading",
        "/portfolio",
    )

    /**
     * Strict guard for the Phase 2.b test-stream client: rejects
     * every URL except [TEST_STREAM_URL]. Preserved verbatim so the
     * test-stream contract tests continue to enforce single-URL
     * locking.
     */
    fun requireSafeReadOnlyEndpoint(url: String) {
        rejectTradingShapes(url)
        require(url == TEST_STREAM_URL) {
            "Alpaca test stream URL is hard-locked to $TEST_STREAM_URL (got: $url)"
        }
    }

    /** True iff [url] satisfies [requireSafeReadOnlyEndpoint] without throwing. */
    fun isSafeReadOnlyEndpoint(url: String): Boolean = try {
        requireSafeReadOnlyEndpoint(url); true
    } catch (_: IllegalArgumentException) {
        false
    }

    /**
     * Phase 2.e guard for the real-stock stream client: accepts
     * [TEST_STREAM_URL] or [IEX_STREAM_URL] and rejects every other
     * URL — including the SIP variants, the trading hosts, and any
     * `/orders|/positions|/account|/trading|/portfolio` fragment.
     */
    fun requireSafeMarketDataEndpoint(url: String) {
        rejectTradingShapes(url)
        require(url in ALLOWED_MARKET_DATA_URLS) {
            "Alpaca market-data URL is hard-locked to one of " +
                "$ALLOWED_MARKET_DATA_URLS (got: $url)"
        }
    }

    /** True iff [url] satisfies [requireSafeMarketDataEndpoint] without throwing. */
    fun isSafeMarketDataEndpoint(url: String): Boolean = try {
        requireSafeMarketDataEndpoint(url); true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun rejectTradingShapes(url: String) {
        val lower = url.lowercase()
        require(!lower.contains("live")) {
            "Alpaca market-data URL must not contain 'live': $url"
        }
        for (host in FORBIDDEN_HOSTS) {
            require(!lower.contains(host)) {
                "Alpaca market-data URL must not target trading host '$host': $url"
            }
        }
        for (fragment in FORBIDDEN_PATH_FRAGMENTS) {
            require(!lower.contains(fragment)) {
                "Alpaca market-data URL must not contain trading path fragment '$fragment': $url"
            }
        }
    }
}
