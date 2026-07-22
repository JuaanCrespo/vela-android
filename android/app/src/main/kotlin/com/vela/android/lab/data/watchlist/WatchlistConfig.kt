package com.vela.android.lab.data.watchlist

/**
 * Phase 2.g read-only watchlist policy. Pure-Kotlin; no Android imports.
 *
 *  - [DEFAULT_SYMBOLS] is the seed list used the first time the
 *    watchlist storage is empty. Stocks only — no FAKEPACA, no crypto.
 *  - [MAX_SYMBOLS] caps the watchlist so a runaway add cannot widen
 *    the IEX subscription beyond a small, observable surface.
 *  - [normalize] is the gatekeeper: trim → uppercase → reject empty
 *    → reject crypto-slash pairs (e.g. `BTC/USD`) → enforce a
 *    conservative `[A-Z][A-Z0-9.]{0,9}` shape that matches the way
 *    Alpaca exposes US-listed equities (e.g. `BRK.B`).
 *
 * No method on this object opens a network, mutates account state,
 * or performs any trading action.
 */
object WatchlistConfig {

    val DEFAULT_SYMBOLS: List<String> = listOf("SPY", "QQQ", "AAPL", "MSFT", "NVDA")

    const val MAX_SYMBOLS: Int = 10

    private val SYMBOL_PATTERN: Regex = Regex("^[A-Z][A-Z0-9.]{0,9}$")

    /**
     * Returns the canonical, uppercase symbol if [input] is a valid
     * read-only stock symbol; `null` otherwise.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val upper = trimmed.uppercase()
        if ('/' in upper) return null
        if (' ' in upper) return null
        if (!SYMBOL_PATTERN.matches(upper)) return null
        return upper
    }

    /** True iff [input] normalizes to a non-null symbol. */
    fun isValid(input: String): Boolean = normalize(input) != null
}
