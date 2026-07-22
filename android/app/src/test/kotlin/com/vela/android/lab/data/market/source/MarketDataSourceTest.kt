package com.vela.android.lab.data.market.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketDataSourceTest {

    @Test
    fun `enum does not declare an ALPACA_LIVE value`() {
        // Hard architectural invariant: live trading must be
        // un-representable, not just unimplemented.
        val names = MarketDataSource.entries.map { it.name }
        assertFalse(names.contains("ALPACA_LIVE"),
            "MarketDataSource must NOT contain an ALPACA_LIVE entry, but had: $names")
        assertFalse(names.any { it.contains("LIVE") },
            "MarketDataSource must NOT contain any entry whose name includes 'LIVE'; got: $names")
    }

    @Test
    fun `enum contains the documented offline and paper values`() {
        val names = MarketDataSource.entries.map { it.name }
        assertTrue("OFFLINE" in names)
        assertTrue("OFFLINE_STUB" in names)
        assertTrue("ALPACA_TEST_STREAM" in names)
        assertTrue("ALPACA_STOCK_IEX" in names)
        assertTrue("ALPACA_PAPER" in names)
        assertEquals(5, MarketDataSource.entries.size,
            "Adding a new source requires a paired safety review.")
    }
}
