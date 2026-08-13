package com.vela.android.lab.data.paper.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class AlpacaPaperOrderStatusEndpointTest {

    private val id = "4b60549d-6dab-47d8-93eb-382ed1eed108"

    @Test
    fun `canonical Paper UUID builds the exact lifecycle URL`() {
        val url = AlpacaPaperOrderStatusEndpoint.urlFor(id)

        assertEquals("GET", AlpacaPaperOrderStatusEndpoint.METHOD)
        assertEquals("https://paper-api.alpaca.markets/v2/orders/$id", url)
        AlpacaPaperOrderStatusEndpoint.requireSafeGet(url)
        assertTrue(AlpacaPaperOrderStatusEndpoint.isSafeGet(url))
    }

    @TestFactory
    fun `non-canonical order ids are rejected`(): List<DynamicTest> = listOf(
        "",
        "abc-123",
        id.uppercase(),
        " $id",
        "$id ",
        "$id/extra",
        "$id?nested=true",
        "$id#fragment",
        "../../account",
    ).map { value ->
        DynamicTest.dynamicTest("reject id: $value") {
            assertThrows(IllegalArgumentException::class.java) {
                AlpacaPaperOrderStatusEndpoint.urlFor(value)
            }
        }
    }

    @TestFactory
    fun `all hosts collections and widened paths are rejected`(): List<DynamicTest> = listOf(
        "https://paper-api.alpaca.markets/v2/orders",
        "https://paper-api.alpaca.markets/v2/orders/",
        "https://paper-api.alpaca.markets/v2/orders/abc-123",
        "https://paper-api.alpaca.markets/v2/orders/$id/",
        "https://paper-api.alpaca.markets/v2/orders/$id/cancel",
        "https://paper-api.alpaca.markets/v2/orders/$id?status=filled",
        "https://paper-api.alpaca.markets/v2/orders/$id#fragment",
        "https://paper-api.alpaca.markets:443/v2/orders/$id",
        "http://paper-api.alpaca.markets/v2/orders/$id",
        "https://api.alpaca.markets/v2/orders/$id",
        "https://example.com/v2/orders/$id",
        "https://paper-api.alpaca.markets/v2/account",
        "",
    ).map { url ->
        DynamicTest.dynamicTest("reject url: $url") {
            assertFalse(AlpacaPaperOrderStatusEndpoint.isSafeGet(url))
            assertThrows(IllegalArgumentException::class.java) {
                AlpacaPaperOrderStatusEndpoint.requireSafeGet(url)
            }
        }
    }
}
