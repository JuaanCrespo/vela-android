package com.vela.android.lab.data.paper.submit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class AlpacaPaperSubmitEndpointTest {
    @Test
    fun `allows exactly Paper POST orders collection`() {
        assertEquals("POST", AlpacaPaperSubmitEndpoint.METHOD)
        assertEquals(
            "https://paper-api.alpaca.markets/v2/orders",
            AlpacaPaperSubmitEndpoint.ORDERS_URL,
        )
        assertTrue(AlpacaPaperSubmitEndpoint.isSafeManualPaperOrder(
            "POST",
            AlpacaPaperSubmitEndpoint.ORDERS_URL,
        ))
    }

    @TestFactory
    fun `rejects every other method host and mutation path`(): List<DynamicTest> {
        val rejected = listOf(
            "GET" to AlpacaPaperSubmitEndpoint.ORDERS_URL,
            "DELETE" to AlpacaPaperSubmitEndpoint.ORDERS_URL,
            "PATCH" to AlpacaPaperSubmitEndpoint.ORDERS_URL,
            "PUT" to AlpacaPaperSubmitEndpoint.ORDERS_URL,
            "POST" to "https://api.alpaca.markets/v2/orders",
            "POST" to "https://live-api.example/v2/orders",
            "POST" to "https://paper-api.alpaca.markets/v2/orders/id-1",
            "POST" to "https://paper-api.alpaca.markets/v2/orders/id-1/cancel",
            "POST" to "https://paper-api.alpaca.markets/v2/positions/SPY",
            "POST" to "https://paper-api.alpaca.markets/v2/account/configurations",
            "POST" to "https://example.com/v2/orders",
        )
        return rejected.map { (method, url) ->
            DynamicTest.dynamicTest("rejects $method $url") {
                assertFalse(AlpacaPaperSubmitEndpoint.isSafeManualPaperOrder(method, url))
                assertThrows(IllegalArgumentException::class.java) {
                    AlpacaPaperSubmitEndpoint.requireSafeManualPaperOrder(method, url)
                }
            }
        }
    }

    @Test
    fun `submit HTTP interface declares exactly executePostOrder`() {
        val methods = AlpacaPaperOrderSubmitHttpClient::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .map { it.name }
            .toSet()
        assertEquals(setOf("executePostOrder"), methods)
    }
}
