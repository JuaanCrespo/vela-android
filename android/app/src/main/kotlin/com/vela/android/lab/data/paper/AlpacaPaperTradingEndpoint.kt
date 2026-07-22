package com.vela.android.lab.data.paper

/**
 * Phase 2.k hard-coded allow-list for the **Paper Trading API**.
 *
 * Only three read-only GET URLs are permitted:
 *
 *  - `GET https://paper-api.alpaca.markets/v2/account`
 *  - `GET https://paper-api.alpaca.markets/v2/clock`
 *  - `GET https://paper-api.alpaca.markets/v2/positions`
 *
 * Every other URL is rejected at runtime by [requireSafePaperReadOnlyGet].
 * This includes:
 *
 *  - the LIVE Trading host `https://api.alpaca.markets/v2` — `api.alpaca.markets`
 *    is **distinct** from `paper-api.alpaca.markets` and must never
 *    be reached.
 *  - any URL containing the substring `live` (case-insensitive).
 *  - `/orders`, `/orders/{id}`, `/account/configurations`,
 *    `/positions/{symbol}` (close-position), and any other path
 *    that would be a mutation surface even on the Paper host.
 *
 * This object never opens a network connection; it only validates
 * URL strings before another component issues a request.
 */
object AlpacaPaperTradingEndpoint {

    const val PAPER_BASE_URL: String = "https://paper-api.alpaca.markets/v2"

    const val ACCOUNT_URL: String = "$PAPER_BASE_URL/account"
    const val CLOCK_URL: String = "$PAPER_BASE_URL/clock"
    const val POSITIONS_URL: String = "$PAPER_BASE_URL/positions"

    /** The full set of URLs the Phase 2.k client is permitted to GET. */
    val ALLOWED_READ_ONLY_URLS: Set<String> = setOf(
        ACCOUNT_URL,
        CLOCK_URL,
        POSITIONS_URL,
    )

    /** Strings that must never appear in a URL passed to the HTTP boundary. */
    private val FORBIDDEN_FRAGMENTS: List<String> = listOf(
        "/orders",
        "/account/configurations",
        "/positions/",  // close-position is /positions/{symbol}
        "/account/activities",
        "/portfolio/history",
    )

    /**
     * Throws [IllegalArgumentException] unless [url] is exactly one
     * of the three URLs in [ALLOWED_READ_ONLY_URLS]. The
     * defense-in-depth checks (live / mutation path / live-host
     * substring) all run **before** the allow-list check so the
     * thrown error message is informative.
     */
    fun requireSafePaperReadOnlyGet(url: String) {
        val lower = url.lowercase()
        require(!lower.contains("live")) {
            "Paper Trading URL must not contain 'live': $url"
        }
        // The LIVE host is `api.alpaca.markets`; the Paper host is
        // `paper-api.alpaca.markets`. The substring `api.alpaca.markets`
        // appears in both, so we test the LIVE host by host-segment
        // equality: anything that *starts with* `https://api.alpaca.markets`
        // (without the `paper-` prefix) is the LIVE host.
        require(!lower.startsWith("https://api.alpaca.markets")) {
            "Paper Trading URL must not target the LIVE host: $url"
        }
        for (frag in FORBIDDEN_FRAGMENTS) {
            require(!lower.contains(frag)) {
                "Paper Trading URL must not contain mutation-shape fragment '$frag': $url"
            }
        }
        require(url in ALLOWED_READ_ONLY_URLS) {
            "Paper Trading URL is hard-locked to one of $ALLOWED_READ_ONLY_URLS (got: $url)"
        }
    }

    /** True iff [url] passes [requireSafePaperReadOnlyGet] without throwing. */
    fun isSafePaperReadOnlyGet(url: String): Boolean = try {
        requireSafePaperReadOnlyGet(url); true
    } catch (_: IllegalArgumentException) {
        false
    }
}
