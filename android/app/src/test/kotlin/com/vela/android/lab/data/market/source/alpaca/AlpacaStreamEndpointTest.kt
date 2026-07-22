package com.vela.android.lab.data.market.source.alpaca

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class AlpacaStreamEndpointTest {

    @Test
    fun `TEST_STREAM_URL is the documented test stream wss URL`() {
        assertEquals(
            "wss://stream.data.alpaca.markets/v2/test",
            AlpacaStreamEndpoint.TEST_STREAM_URL,
        )
    }

    @Test
    fun `TEST_SYMBOL is FAKEPACA`() {
        assertEquals("FAKEPACA", AlpacaStreamEndpoint.TEST_SYMBOL)
    }

    @Test
    fun `requireSafeReadOnlyEndpoint accepts the test stream URL`() {
        AlpacaStreamEndpoint.requireSafeReadOnlyEndpoint(
            AlpacaStreamEndpoint.TEST_STREAM_URL,
        )
        assertTrue(AlpacaStreamEndpoint.isSafeReadOnlyEndpoint(
            AlpacaStreamEndpoint.TEST_STREAM_URL,
        ))
    }

    @TestFactory
    fun `requireSafeReadOnlyEndpoint rejects forbidden URLs`(): List<DynamicTest> {
        val rejected = listOf(
            "https://api.alpaca.markets/v2/orders",
            "https://paper-api.alpaca.markets/v2/orders",
            "https://api.alpaca.markets/v2/account",
            "wss://stream.data.alpaca.markets/v2/iex",
            "wss://stream.data.alpaca.markets/v2/live",
            "wss://stream.data.alpaca.markets/v2/sip",
            "https://example.com/v2/test",
            "wss://stream.data.alpaca.markets/v2/test/orders",
            "wss://stream.data.alpaca.markets/v2/test/account",
            "wss://stream.data.alpaca.markets/v2/test/trading",
            "wss://stream.data.alpaca.markets/v2/test/positions",
            "wss://stream.data.alpaca.markets/v2/test/portfolio",
        )
        return rejected.map { url ->
            DynamicTest.dynamicTest("rejects: $url") {
                assertFalse(
                    AlpacaStreamEndpoint.isSafeReadOnlyEndpoint(url),
                    "$url must be rejected as unsafe",
                )
                assertThrows(IllegalArgumentException::class.java) {
                    AlpacaStreamEndpoint.requireSafeReadOnlyEndpoint(url)
                }
            }
        }
    }
}
