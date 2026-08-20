package com.vela.android.lab.data.paper.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperOrderStatusJsonParserTest {
    private val parser = PaperOrderStatusJsonParser()
    private val id = "4b60549d-6dab-47d8-93eb-382ed1eed108"

    @Test
    fun filledParsesCompleteIdentityAndExecutionFields() {
        val result = parser.parse(response("filled", "1", "773.49", FILLED_AT))
            as PaperOrderStatusJsonParser.ParseResult.Ok

        with(result.value) {
            assertEquals(id, orderId)
            assertEquals("client-1", clientOrderId)
            assertEquals("SPY", symbol)
            assertEquals("BUY", side)
            assertEquals(1.0, quantity)
            assertEquals("MARKET", orderType)
            assertEquals("DAY", timeInForce)
            assertEquals(PaperOrderLifecycleStatus.FILLED, status)
            assertEquals(1.0, filledQuantity)
            assertEquals(773.49, filledAveragePriceUsd)
            assertEquals(FILLED_AT, filledAtIso)
            assertTrue(terminalForNewPreparation)
        }
    }

    @Test
    fun nonTerminalAndUnknownStatusesRemainBlocking() {
        val fresh = parser.parse(response("new", "0", "null", null))
            as PaperOrderStatusJsonParser.ParseResult.Ok
        val unknown = parser.parse(response("future_status", "0", "null", null))
            as PaperOrderStatusJsonParser.ParseResult.Ok

        assertEquals(PaperOrderLifecycleStatus.NEW, fresh.value.status)
        assertTrue(!fresh.value.terminalForNewPreparation)
        assertEquals(PaperOrderLifecycleStatus.UNKNOWN, unknown.value.status)
        assertEquals("FUTURE_STATUS", unknown.value.displayStatus)
        assertTrue(!unknown.value.terminalForNewPreparation)
    }

    @Test
    fun invalidFilledOrIdentityFailsClosedWithSafeMessage() {
        val invalidBodies = listOf(
            response("filled", "0", "null", null),
            response("filled", "1", "773.49", "tomorrow"),
            response("new", "0", "null", null, clientOrderId = ""),
            response("new", "0", "null", null, quantity = "0"),
            response("new", "0", "null", null, symbol = "?"),
        )

        invalidBodies.forEach { body ->
            val result = parser.parse(body)
            assertTrue(result is PaperOrderStatusJsonParser.ParseResult.Err)
            val message = (result as PaperOrderStatusJsonParser.ParseResult.Err).safeMessage
            assertTrue(!message.contains("APCA-API"))
        }
    }

    private fun response(
        status: String,
        filledQty: String,
        average: String,
        filledAt: String?,
        clientOrderId: String = "client-1",
        quantity: String = "1",
        symbol: String = "SPY",
    ): String = """
        {
          "id":"$id",
          "client_order_id":"$clientOrderId",
          "symbol":"$symbol",
          "side":"buy",
          "qty":"$quantity",
          "type":"market",
          "time_in_force":"day",
          "status":"$status",
          "filled_qty":"$filledQty",
          "filled_avg_price":$average,
          "filled_at":${filledAt?.let { "\"$it\"" } ?: "null"}
        }
    """.trimIndent()

    companion object {
        private const val FILLED_AT = "2026-08-07T19:31:02.123456Z"
    }
}
