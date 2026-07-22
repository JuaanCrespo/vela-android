package com.vela.android.lab.data.watchlist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class WatchlistConfigTest {

    @Test
    fun `default symbols are the documented Phase 2-g seed list`() {
        assertEquals(listOf("SPY", "QQQ", "AAPL", "MSFT", "NVDA"), WatchlistConfig.DEFAULT_SYMBOLS)
    }

    @Test
    fun `max symbols cap is small`() {
        assertEquals(10, WatchlistConfig.MAX_SYMBOLS)
    }

    @Test
    fun `normalize accepts SPY`() {
        assertEquals("SPY", WatchlistConfig.normalize("SPY"))
    }

    @Test
    fun `normalize trims and uppercases`() {
        assertEquals("SPY", WatchlistConfig.normalize("  spy  "))
        assertEquals("AAPL", WatchlistConfig.normalize("aapl"))
    }

    @Test
    fun `normalize accepts BRK dot B (dot is a real Alpaca shape)`() {
        assertEquals("BRK.B", WatchlistConfig.normalize("brk.b"))
    }

    @TestFactory
    fun `normalize rejects invalid inputs`(): List<DynamicTest> {
        val invalid = listOf(
            "" to "empty",
            "   " to "whitespace only",
            "BTC/USD" to "crypto slash",
            "ETH/USD" to "crypto slash",
            "spy spy" to "embedded space",
            "1SPY" to "starts with digit",
            ".SPY" to "starts with dot",
            "TOOLONGSYMBOL" to "exceeds 10 char shape",
            "SPY!" to "punctuation",
            "SPY@123" to "at sign",
        )
        return invalid.map { (raw, why) ->
            DynamicTest.dynamicTest("rejects '$raw' ($why)") {
                assertNull(WatchlistConfig.normalize(raw), "expected null for '$raw'")
                assertFalse(WatchlistConfig.isValid(raw), "expected isValid=false for '$raw'")
            }
        }
    }

    @Test
    fun `isValid agrees with normalize`() {
        assertTrue(WatchlistConfig.isValid("SPY"))
        assertTrue(WatchlistConfig.isValid("nvda"))
        assertFalse(WatchlistConfig.isValid(""))
        assertFalse(WatchlistConfig.isValid("BTC/USD"))
    }
}
