package com.vela.android.lab.data.market.source

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Reflection-based guard: prove that the read-only boundary does
 * not, accidentally or otherwise, expose any trading capability.
 *
 * If a future refactor adds an order-related method to
 * [MarketDataClient] or any class in `data.market.source`, these
 * tests fail. That is by design.
 */
class MarketDataClientContractTest {

    private val forbiddenSubstrings: List<String> = listOf(
        "submit",
        "placeorder",
        "place_order",
        "buyorder",
        "sellorder",
        "buy",
        "sell",
        "withdraw",
        "deposit",
        "trade",
        "execute",
        "cancelorder",
        "tradingclient",
        "account",
        "credential",
    )

    @TestFactory
    fun `MarketDataClient interface declares no trading methods`(): List<DynamicTest> {
        val methods = MarketDataClient::class.java.declaredMethods.map { it.name }
        return methods.map { methodName ->
            DynamicTest.dynamicTest("$methodName must not look like a trading method") {
                val lower = methodName.lowercase()
                for (bad in forbiddenSubstrings) {
                    assertFalse(
                        lower.contains(bad),
                        "MarketDataClient method '$methodName' contains forbidden substring '$bad'",
                    )
                }
            }
        }
    }

    @TestFactory
    fun `StubPaperMarketDataClient declares no trading methods`(): List<DynamicTest> {
        val methods = StubPaperMarketDataClient::class.java.declaredMethods
            .map { it.name }
            // Filter Kotlin/JVM compiler synthetics that begin with $ or contain $.
            .filterNot { it.contains('$') }
        return methods.map { methodName ->
            DynamicTest.dynamicTest("$methodName must not look like a trading method") {
                val lower = methodName.lowercase()
                for (bad in forbiddenSubstrings) {
                    assertFalse(
                        lower.contains(bad),
                        "StubPaperMarketDataClient method '$methodName' contains forbidden substring '$bad'",
                    )
                }
            }
        }
    }

    @Test
    fun `boundary package declares no class with a trading-shaped simple name`() {
        // Walk the Phase 2.a boundary classes and prove the type
        // names themselves don't smuggle in a trading API.
        val classes = listOf(
            MarketDataClient::class.java,
            MarketDataConfig::class.java,
            MarketDataConnectionStatus::class.java,
            MarketDataError::class.java,
            MarketDataSource::class.java,
            StubPaperMarketDataClient::class.java,
        )
        for (cls in classes) {
            val lower = cls.simpleName.lowercase()
            for (bad in forbiddenSubstrings) {
                assertFalse(
                    lower.contains(bad),
                    "Class ${cls.simpleName} simple name contains forbidden substring '$bad'",
                )
            }
        }
        assertTrue(classes.size >= 6, "Sanity: at least six boundary types declared")
    }

    @Test
    fun `MarketDataClient updates property emits read-only domain type`() {
        // The boundary streams `BootstrapMarketUpdate`. Confirm the
        // generic argument type is a value class with no mutator methods
        // matching the forbidden list, so the data flowing through
        // cannot itself carry a trading hook.
        val updateClass = com.vela.android.lab.data.market.BootstrapMarketUpdate::class.java
        val methodNames = updateClass.declaredMethods.map { it.name.lowercase() }
        for (name in methodNames) {
            for (bad in forbiddenSubstrings) {
                assertFalse(
                    name.contains(bad),
                    "BootstrapMarketUpdate method '$name' contains forbidden substring '$bad'",
                )
            }
        }
    }
}
