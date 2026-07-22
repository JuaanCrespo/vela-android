package com.vela.android.lab.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers behaviors of [normalizeMarketSymbol], [isCryptoSymbol], and
 * [compactMarketSymbol] that the Python `normalize_market_symbol` in
 * `app/data/alpaca_client.py` exhibits. There is no Python test file
 * dedicated to these helpers in `G:\vela\tests`, but the rules are
 * called out in the alpaca_client docstrings and used throughout the
 * pipeline, so they're worth pinning down here.
 */
class SymbolsTest {

    @Test
    fun `empty input normalizes to empty`() {
        assertEquals("", normalizeMarketSymbol(""))
        assertEquals("", normalizeMarketSymbol(null))
        assertEquals("", normalizeMarketSymbol("   "))
    }

    @Test
    fun `equity symbols uppercased and whitespace-stripped`() {
        assertEquals("SPY", normalizeMarketSymbol("spy"))
        assertEquals("SPY", normalizeMarketSymbol("  SPY  "))
        assertEquals("SPY", normalizeMarketSymbol("s p y"))
    }

    @Test
    fun `known crypto pair gets BASE slash QUOTE form`() {
        assertEquals("BTC/USD", normalizeMarketSymbol("BTCUSD"))
        assertEquals("ETH/USDT", normalizeMarketSymbol("ethusdt"))
        assertEquals("DOGE/USDC", normalizeMarketSymbol("DOGEUSDC"))
    }

    @Test
    fun `already-canonical crypto pair is preserved`() {
        assertEquals("BTC/USD", normalizeMarketSymbol("btc/usd"))
        assertEquals("ETH/USD", normalizeMarketSymbol("ETH / USD"))
    }

    @Test
    fun `unknown bases are not reformatted`() {
        // ZZZUSD is not in the crypto base set, so it stays compact.
        assertEquals("ZZZUSD", normalizeMarketSymbol("zzzusd"))
    }

    @Test
    fun `isCryptoSymbol identifies BASE slash QUOTE form`() {
        assertTrue(isCryptoSymbol("BTC/USD"))
        assertTrue(isCryptoSymbol("ethusdt"))
        assertFalse(isCryptoSymbol("SPY"))
        assertFalse(isCryptoSymbol(""))
        assertFalse(isCryptoSymbol("ZZZUSD"))
    }

    @Test
    fun `compactMarketSymbol strips the slash`() {
        assertEquals("BTCUSD", compactMarketSymbol("BTC/USD"))
        assertEquals("SPY", compactMarketSymbol("spy"))
    }
}
