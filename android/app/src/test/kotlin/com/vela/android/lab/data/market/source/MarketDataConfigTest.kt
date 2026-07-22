package com.vela.android.lab.data.market.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketDataConfigTest {

    @Test
    fun `default config is offline stub with no endpoint or credentials`() {
        val config = MarketDataConfig()
        assertEquals(MarketDataSource.OFFLINE_STUB, config.source)
        assertNull(config.endpoint)
        assertNull(config.credentialsKeyAlias)
        assertTrue(config.symbols.isEmpty())
        assertTrue(config.isOfflineOrStub)
    }

    @Test
    fun `Default companion alias matches the default constructor`() {
        assertEquals(MarketDataConfig(), MarketDataConfig.Default)
    }

    @Test
    fun `rejects endpoint mentioning live`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            MarketDataConfig(
                source = MarketDataSource.ALPACA_PAPER,
                endpoint = "https://live-data.alpaca.markets/v2",
            )
        }
        assertTrue(ex.message!!.contains("LIVE", ignoreCase = true))
    }

    @Test
    fun `rejects endpoint targeting the live Alpaca trading host`() {
        // Even with ALPACA_PAPER selected, a config that names the
        // live host must be rejected at construction time.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            MarketDataConfig(
                source = MarketDataSource.ALPACA_PAPER,
                endpoint = "https://api.alpaca.markets/v2/orders",
            )
        }
        assertTrue(
            ex.message!!.contains("LIVE", ignoreCase = true),
            "Expected LIVE rejection message, got: ${ex.message}",
        )
    }

    @Test
    fun `rejects pairing OFFLINE_STUB source with any endpoint`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            MarketDataConfig(
                source = MarketDataSource.OFFLINE_STUB,
                endpoint = "https://paper-api.alpaca.markets/v2",
            )
        }
        assertTrue(
            ex.message!!.contains("OFFLINE_STUB") ||
                ex.message!!.contains("endpoint must be null"),
            "Expected offline-and-endpoint message, got: ${ex.message}",
        )
    }

    @Test
    fun `accepts paper data endpoint when paired with ALPACA_PAPER`() {
        // This config is valid from a guards standpoint. No
        // implementation is wired to ALPACA_PAPER in Phase 2.a, so
        // the lab still cannot reach Alpaca even if a caller built
        // this object.
        val config = MarketDataConfig(
            source = MarketDataSource.ALPACA_PAPER,
            endpoint = "https://data.alpaca.markets/v2/stocks/bars",
        )
        assertEquals(MarketDataSource.ALPACA_PAPER, config.source)
    }
}
