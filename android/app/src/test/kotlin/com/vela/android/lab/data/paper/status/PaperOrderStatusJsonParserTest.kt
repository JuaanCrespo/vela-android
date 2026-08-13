package com.vela.android.lab.data.paper.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class PaperOrderStatusJsonParserTest {

    private val parser = PaperOrderStatusJsonParser()
    private val id = "4b60549d-6dab-47d8-93eb-382ed1eed108"

    @Test
    fun `FILLED parses quantity average price and timestamp`() {
        val result = parser.parse(
            """{
              "id":"$id",
              "status":"filled",
              "filled_qty":"1",
              "filled_avg_price":"773.49",
              "filled_at":"2026-08-07T19:31:02.123456Z"
            }""",
        )

        assertTrue(result is PaperOrderStatusJsonParser.ParseResult.Ok)
        val snapshot = (result as PaperOrderStatusJsonParser.ParseResult.Ok).value
        assertEquals(id, snapshot.orderId)
        assertEquals(PaperOrderLifecycleStatus.FILLED, snapshot.status)
        assertEquals("FILLED", snapshot.displayStatus)
        assertEquals(1.0, snapshot.filledQuantity)
        assertEquals(773.49, snapshot.filledAveragePriceUsd)
        assertEquals("2026-08-07T19:31:02.123456Z", snapshot.filledAtIso)
    }

    @Test
    fun `NEW accepts zero quantity and null fill details`() {
        val result = parser.parse(
            """{
              "id":"$id",
              "status":"new",
              "filled_qty":"0",
              "filled_avg_price":null,
              "filled_at":null
            }""",
        ) as PaperOrderStatusJsonParser.ParseResult.Ok

        assertEquals(PaperOrderLifecycleStatus.NEW, result.value.status)
        assertEquals(0.0, result.value.filledQuantity)
        assertEquals(null, result.value.filledAveragePriceUsd)
        assertEquals(null, result.value.filledAtIso)
    }

    @Test
    fun `unknown safe lifecycle remains visible without widening behavior`() {
        val result = parser.parse(
            """{
              "id":"$id",
              "status":"future_status",
              "filled_qty":0,
              "filled_avg_price":null,
              "filled_at":null
            }""",
        ) as PaperOrderStatusJsonParser.ParseResult.Ok

        assertEquals(PaperOrderLifecycleStatus.UNKNOWN, result.value.status)
        assertEquals("FUTURE_STATUS", result.value.displayStatus)
    }

    @TestFactory
    fun `invalid or inconsistent responses fail with safe messages`(): List<DynamicTest> = listOf(
        "" to "empty",
        "not-json APCA-API-SECRET-KEY=very-secret" to "invalid JSON",
        """{"status":"filled","filled_qty":"1","filled_avg_price":"2","filled_at":"2026-08-07T19:31:02Z"}""" to "valid id",
        """{"id":"$id","status":"","filled_qty":"0"}""" to "valid status",
        """{"id":"$id","status":"new","filled_qty":"NaN"}""" to "filled quantity",
        """{"id":"$id","status":"new","filled_qty":"0","filled_avg_price":"0"}""" to "average price",
        """{"id":"$id","status":"new","filled_qty":"0","filled_at":"tomorrow"}""" to "filled timestamp",
        """{"id":"$id","status":"filled","filled_qty":"0","filled_avg_price":null,"filled_at":null}""" to "internally inconsistent",
    ).map { (body, expectedFragment) ->
        DynamicTest.dynamicTest("rejects $expectedFragment") {
            val result = parser.parse(body)
            assertTrue(result is PaperOrderStatusJsonParser.ParseResult.Err)
            val message = (result as PaperOrderStatusJsonParser.ParseResult.Err).safeMessage
            assertTrue(message.contains(expectedFragment, ignoreCase = true), message)
            assertTrue(!message.contains("very-secret"))
        }
    }
}
