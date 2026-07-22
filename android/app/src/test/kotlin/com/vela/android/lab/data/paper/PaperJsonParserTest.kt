package com.vela.android.lab.data.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperJsonParserTest {

    private val parser = PaperJsonParser()

    @Test
    fun `parseAccount handles canonical alpaca paper response`() {
        val json = """{
            "id":"abc-123",
            "status":"ACTIVE",
            "currency":"USD",
            "cash":"100000.50",
            "buying_power":"400000.00",
            "equity":"100123.45",
            "portfolio_value":"100123.45",
            "trading_blocked":false,
            "account_blocked":false,
            "pattern_day_trader":false
        }""".trimIndent()
        val result = parser.parseAccount(json)
        assertTrue(result is PaperJsonParser.ParseResult.Ok)
        val snap = (result as PaperJsonParser.ParseResult.Ok).value
        assertEquals(100000.50, snap.cashUsd)
        assertEquals(400000.00, snap.buyingPowerUsd)
        assertEquals(100123.45, snap.equityUsd)
        assertEquals(100123.45, snap.portfolioValueUsd)
        assertFalse(snap.tradingBlocked)
        assertFalse(snap.accountBlocked)
        assertFalse(snap.patternDayTrader)
        assertEquals("USD", snap.currency)
        assertEquals("ACTIVE", snap.status)
    }

    @Test
    fun `parseAccount snapshot never carries the account id`() {
        val json = """{"id":"super-secret-acct-id","cash":"5","status":"ACTIVE"}"""
        val result = parser.parseAccount(json)
        val snap = (result as PaperJsonParser.ParseResult.Ok).value
        assertFalse(snap.toString().contains("super-secret-acct-id"))
    }

    @Test
    fun `parseClock handles canonical alpaca paper response`() {
        val json = """{
            "timestamp":"2026-06-13T14:30:00Z",
            "is_open":true,
            "next_open":"2026-06-13T13:30:00Z",
            "next_close":"2026-06-13T20:00:00Z"
        }""".trimIndent()
        val result = parser.parseClock(json)
        val snap = (result as PaperJsonParser.ParseResult.Ok).value
        assertTrue(snap.isOpen)
        assertEquals("2026-06-13T13:30:00Z", snap.nextOpenIso)
        assertEquals("2026-06-13T20:00:00Z", snap.nextCloseIso)
        assertEquals("2026-06-13T14:30:00Z", snap.timestampIso)
    }

    @Test
    fun `parseClock handles missing fields as nulls or false`() {
        val json = "{}"
        val result = parser.parseClock(json)
        val snap = (result as PaperJsonParser.ParseResult.Ok).value
        assertFalse(snap.isOpen)
        assertNull(snap.nextOpenIso)
        assertNull(snap.nextCloseIso)
        assertNull(snap.timestampIso)
    }

    @Test
    fun `parsePositions handles canonical alpaca paper response`() {
        val json = """[
            {"symbol":"AAPL","qty":"10","market_value":"2902.00","unrealized_pl":"5.50","side":"long"},
            {"symbol":"SPY","qty":"5","market_value":"3650.00","unrealized_pl":"-12.00","side":"long"}
        ]"""
        val result = parser.parsePositions(json)
        val positions = (result as PaperJsonParser.ParseResult.Ok).value
        assertEquals(2, positions.size)
        val aapl = positions[0]
        assertEquals("AAPL", aapl.symbol)
        assertEquals(10.0, aapl.qty)
        assertEquals(2902.00, aapl.marketValueUsd)
        assertEquals(5.50, aapl.unrealizedPlUsd)
        assertEquals("long", aapl.side)
    }

    @Test
    fun `parsePositions handles empty array`() {
        val result = parser.parsePositions("[]")
        val positions = (result as PaperJsonParser.ParseResult.Ok).value
        assertEquals(0, positions.size)
    }

    @Test
    fun `parseAccount handles malformed JSON`() {
        val result = parser.parseAccount("not json")
        assertTrue(result is PaperJsonParser.ParseResult.Err)
    }

    @Test
    fun `parseClock handles malformed JSON`() {
        val result = parser.parseClock("xxx")
        assertTrue(result is PaperJsonParser.ParseResult.Err)
    }

    @Test
    fun `parsePositions handles empty string`() {
        val result = parser.parsePositions("")
        val positions = (result as PaperJsonParser.ParseResult.Ok).value
        assertEquals(0, positions.size)
    }

    @Test
    fun `parsePositions handles malformed JSON`() {
        val result = parser.parsePositions("{ not an array }")
        assertTrue(result is PaperJsonParser.ParseResult.Err)
    }

    @Test
    fun `numeric fields tolerate numeric JSON in addition to strings`() {
        val json = """{"cash":5.5,"buying_power":10,"equity":15,"portfolio_value":20,"status":"ACTIVE"}"""
        val result = parser.parseAccount(json)
        val snap = (result as PaperJsonParser.ParseResult.Ok).value
        assertEquals(5.5, snap.cashUsd)
        assertEquals(10.0, snap.buyingPowerUsd)
        assertEquals(15.0, snap.equityUsd)
        assertEquals(20.0, snap.portfolioValueUsd)
    }

    @Test
    fun `parser has no method shaped like a trading or order action`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "trading", "executeorder",
            "cancelorder", "openposition", "closeposition", "getaccount",
            "post", "put", "patch", "delete",
        )
        val methods = PaperJsonParser::class.java.declaredMethods.map { it.name }
        for (name in methods) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertTrue(
                    !lower.contains(bad),
                    "Parser method '$name' contains forbidden substring '$bad'",
                )
            }
        }
        assertNotNull(methods.firstOrNull { it == "parseAccount" })
    }
}
