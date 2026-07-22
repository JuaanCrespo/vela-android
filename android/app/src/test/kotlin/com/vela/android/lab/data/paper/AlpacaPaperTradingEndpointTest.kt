package com.vela.android.lab.data.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class AlpacaPaperTradingEndpointTest {

    @Test
    fun `paper base URL is the documented one`() {
        assertEquals(
            "https://paper-api.alpaca.markets/v2",
            AlpacaPaperTradingEndpoint.PAPER_BASE_URL,
        )
    }

    @Test
    fun `account, clock, positions URLs are exactly three`() {
        assertEquals(3, AlpacaPaperTradingEndpoint.ALLOWED_READ_ONLY_URLS.size)
        assertEquals(
            setOf(
                "https://paper-api.alpaca.markets/v2/account",
                "https://paper-api.alpaca.markets/v2/clock",
                "https://paper-api.alpaca.markets/v2/positions",
            ),
            AlpacaPaperTradingEndpoint.ALLOWED_READ_ONLY_URLS,
        )
    }

    @Test
    fun `the three allowed URLs all pass the guard`() {
        for (url in AlpacaPaperTradingEndpoint.ALLOWED_READ_ONLY_URLS) {
            AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
            assertTrue(AlpacaPaperTradingEndpoint.isSafePaperReadOnlyGet(url))
        }
    }

    @TestFactory
    fun `forbidden URLs are rejected`(): List<DynamicTest> {
        val rejected = listOf(
            // LIVE host
            "https://api.alpaca.markets/v2/account",
            "https://api.alpaca.markets/v2/clock",
            "https://api.alpaca.markets/v2/positions",
            "https://api.alpaca.markets/v2/orders",
            // Mutation-shape paths on paper host
            "https://paper-api.alpaca.markets/v2/orders",
            "https://paper-api.alpaca.markets/v2/orders/abc-123",
            "https://paper-api.alpaca.markets/v2/positions/AAPL",
            "https://paper-api.alpaca.markets/v2/account/configurations",
            "https://paper-api.alpaca.markets/v2/account/activities",
            "https://paper-api.alpaca.markets/v2/portfolio/history",
            // Live substring
            "https://paper-api.alpaca.markets/v2/livecheck",
            // Random other hosts
            "https://example.com/v2/account",
            "https://stream.data.alpaca.markets/v2/account",
            // Empty / nonsense
            "",
            "not a url",
        )
        return rejected.map { url ->
            DynamicTest.dynamicTest("rejects: $url") {
                assertFalse(
                    AlpacaPaperTradingEndpoint.isSafePaperReadOnlyGet(url),
                    "$url must be rejected by the paper guard",
                )
                assertThrows(IllegalArgumentException::class.java) {
                    AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
                }
            }
        }
    }

    @Test
    fun `the LIVE host string by itself does not sneak through via substring overlap`() {
        // The paper host CONTAINS the substring "api.alpaca.markets",
        // but the guard must still reject the LIVE host that *starts with*
        // "https://api.alpaca.markets" (no "paper-" prefix).
        assertTrue(AlpacaPaperTradingEndpoint.isSafePaperReadOnlyGet(
            "https://paper-api.alpaca.markets/v2/account",
        ))
        assertFalse(AlpacaPaperTradingEndpoint.isSafePaperReadOnlyGet(
            "https://api.alpaca.markets/v2/account",
        ))
    }
}
