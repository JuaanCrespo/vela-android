package com.vela.android.lab.data.market.source.alpaca

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Reflection-based guard: prove the Phase 2.b production client and
 * its collaborators do not, by accident or otherwise, expose a
 * trading capability.
 */
class AlpacaTestStreamClientContractTest {

    // Specific dangerous-method patterns. Generic words like "trade",
    // "account", or "position" appear in legitimate market-data field
    // names (e.g. `Subscription.trades` echoes which symbols were
    // subscribed to the trades feed — it does not execute a trade),
    // so the forbidden list is narrowed to multi-token patterns that
    // unambiguously denote trading actions.
    private val forbiddenSubstrings: List<String> = listOf(
        "submitorder",
        "placeorder",
        "place_order",
        "buyorder",
        "sellorder",
        "buyshare",
        "sellshare",
        "withdraw",
        "deposit",
        "trading",        // catches `TradingClient` and any Alpaca trading SDK
        "executeorder",
        "executetrade",
        "cancelorder",
        "getaccount",
        "updateaccount",
        "openposition",
        "closeposition",
        "getportfolio",
        "setbalance",
        "transferfund",
    )

    @TestFactory
    fun `AlpacaTestStreamMarketDataClient declares no trading methods`(): List<DynamicTest> {
        val methods = AlpacaTestStreamMarketDataClient::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        return methods.map { methodName ->
            DynamicTest.dynamicTest("$methodName must not look like a trading method") {
                val lower = methodName.lowercase()
                for (bad in forbiddenSubstrings) {
                    assertFalse(
                        lower.contains(bad),
                        "AlpacaTestStreamMarketDataClient method '$methodName' contains forbidden substring '$bad'",
                    )
                }
            }
        }
    }

    @TestFactory
    fun `OkHttpAlpacaWebSocketFactory declares no trading methods`(): List<DynamicTest> {
        val methods = OkHttpAlpacaWebSocketFactory::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        return methods.map { methodName ->
            DynamicTest.dynamicTest("$methodName must not look like a trading method") {
                val lower = methodName.lowercase()
                for (bad in forbiddenSubstrings) {
                    assertFalse(
                        lower.contains(bad),
                        "OkHttpAlpacaWebSocketFactory method '$methodName' contains forbidden substring '$bad'",
                    )
                }
            }
        }
    }

    @TestFactory
    fun `AlpacaStreamMessage hierarchy carries no trading payload`(): List<DynamicTest> {
        // Walk every method of every sealed subtype.
        val classes = listOf(
            AlpacaStreamMessage::class.java,
            AlpacaStreamMessage.Connected::class.java,
            AlpacaStreamMessage.Authenticated::class.java,
            AlpacaStreamMessage.Subscription::class.java,
            AlpacaStreamMessage.Quote::class.java,
            AlpacaStreamMessage.Bar::class.java,
            AlpacaStreamMessage.StreamError::class.java,
            AlpacaStreamMessage.Unknown::class.java,
        )
        val cases = mutableListOf<DynamicTest>()
        for (cls in classes) {
            for (method in cls.declaredMethods) {
                if (method.name.contains('$')) continue
                val name = method.name
                cases += DynamicTest.dynamicTest("${cls.simpleName}.$name must not look like a trading method") {
                    val lower = name.lowercase()
                    for (bad in forbiddenSubstrings) {
                        assertFalse(
                            lower.contains(bad),
                            "${cls.simpleName}.$name contains forbidden substring '$bad'",
                        )
                    }
                }
            }
        }
        return cases
    }
}
