package com.vela.android.lab.data.market.source.alpaca

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Phase 2.e endpoint guard for the real-stock IEX feed.
 *
 *  - `IEX_STREAM_URL` is the documented IEX wss URL.
 *  - `requireSafeMarketDataEndpoint` accepts both test and IEX.
 *  - `requireSafeMarketDataEndpoint` rejects every trading-host URL
 *    and every `/orders|/positions|/account|/trading|/portfolio`
 *    fragment.
 *  - `requireSafeMarketDataEndpoint` rejects SIP / delayed_sip /
 *    "live" variants — the feed default stays on IEX.
 *  - The strict Phase 2.b `requireSafeReadOnlyEndpoint` still
 *    rejects everything except the test URL, including IEX.
 *  - `STOCK_PRIMARY_SYMBOL` is the documented seed symbol (SPY).
 */
class AlpacaIexEndpointTest {

    @Test
    fun `IEX_STREAM_URL is the documented IEX wss URL`() {
        assertEquals(
            "wss://stream.data.alpaca.markets/v2/iex",
            AlpacaStreamEndpoint.IEX_STREAM_URL,
        )
    }

    @Test
    fun `STOCK_PRIMARY_SYMBOL is SPY`() {
        assertEquals("SPY", AlpacaStreamEndpoint.STOCK_PRIMARY_SYMBOL)
    }

    @Test
    fun `IEX is not the same URL as TEST or SIP variants`() {
        assertNotEquals(
            AlpacaStreamEndpoint.IEX_STREAM_URL,
            AlpacaStreamEndpoint.TEST_STREAM_URL,
        )
        assertNotEquals(
            AlpacaStreamEndpoint.IEX_STREAM_URL,
            AlpacaStreamEndpoint.SIP_STREAM_URL,
        )
        assertNotEquals(
            AlpacaStreamEndpoint.IEX_STREAM_URL,
            AlpacaStreamEndpoint.DELAYED_SIP_STREAM_URL,
        )
    }

    @Test
    fun `ALLOWED_MARKET_DATA_URLS is exactly {test, iex}`() {
        assertEquals(
            setOf(
                AlpacaStreamEndpoint.TEST_STREAM_URL,
                AlpacaStreamEndpoint.IEX_STREAM_URL,
            ),
            AlpacaStreamEndpoint.ALLOWED_MARKET_DATA_URLS,
        )
    }

    @Test
    fun `requireSafeMarketDataEndpoint accepts test and IEX`() {
        AlpacaStreamEndpoint.requireSafeMarketDataEndpoint(
            AlpacaStreamEndpoint.TEST_STREAM_URL,
        )
        AlpacaStreamEndpoint.requireSafeMarketDataEndpoint(
            AlpacaStreamEndpoint.IEX_STREAM_URL,
        )
        assertTrue(
            AlpacaStreamEndpoint.isSafeMarketDataEndpoint(AlpacaStreamEndpoint.TEST_STREAM_URL),
        )
        assertTrue(
            AlpacaStreamEndpoint.isSafeMarketDataEndpoint(AlpacaStreamEndpoint.IEX_STREAM_URL),
        )
    }

    @Test
    fun `requireSafeReadOnlyEndpoint still rejects IEX so the test-stream client cannot accidentally point at it`() {
        assertFalse(
            AlpacaStreamEndpoint.isSafeReadOnlyEndpoint(AlpacaStreamEndpoint.IEX_STREAM_URL),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AlpacaStreamEndpoint.requireSafeReadOnlyEndpoint(
                AlpacaStreamEndpoint.IEX_STREAM_URL,
            )
        }
    }

    @TestFactory
    fun `requireSafeMarketDataEndpoint rejects forbidden URLs`(): List<DynamicTest> {
        val rejected = listOf(
            "https://api.alpaca.markets/v2/orders",
            "https://paper-api.alpaca.markets/v2/orders",
            "https://api.alpaca.markets/v2/account",
            "https://api.alpaca.markets/v2/positions",
            "https://api.alpaca.markets/v2/trading",
            "wss://stream.data.alpaca.markets/v2/live",
            "wss://stream.data.alpaca.markets/v2/sip",
            "wss://stream.data.alpaca.markets/v2/delayed_sip",
            "https://example.com/v2/iex",
            "wss://stream.data.alpaca.markets/v2/iex/orders",
            "wss://stream.data.alpaca.markets/v2/iex/account",
            "wss://stream.data.alpaca.markets/v2/iex/trading",
            "wss://stream.data.alpaca.markets/v2/iex/positions",
            "wss://stream.data.alpaca.markets/v2/iex/portfolio",
        )
        return rejected.map { url ->
            DynamicTest.dynamicTest("rejects: $url") {
                assertFalse(
                    AlpacaStreamEndpoint.isSafeMarketDataEndpoint(url),
                    "$url must be rejected by the market-data guard",
                )
                assertThrows(IllegalArgumentException::class.java) {
                    AlpacaStreamEndpoint.requireSafeMarketDataEndpoint(url)
                }
            }
        }
    }
}
