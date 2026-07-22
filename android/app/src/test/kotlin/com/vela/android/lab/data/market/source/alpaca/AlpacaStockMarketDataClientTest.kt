@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.market.source.alpaca

import com.vela.android.lab.data.market.BootstrapMarketUpdate
import com.vela.android.lab.data.market.source.MarketDataConnectionStatus
import com.vela.android.lab.data.market.source.MarketDataError
import com.vela.android.lab.data.market.source.MarketDataSource
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class AlpacaStockMarketDataClientTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-06-03T14:30:00Z") }

    @Test
    fun `source is ALPACA_STOCK_IEX`() {
        val client = AlpacaStockMarketDataClient(
            credentialsProvider = NoAlpacaCredentialsProvider,
            webSocketFactory = StockNoOpWebSocketFactory(),
            clock = fixedClock,
        )
        assertEquals(MarketDataSource.ALPACA_STOCK_IEX, client.source)
        assertEquals(AlpacaStreamEndpoint.IEX_STREAM_URL, client.feedUrl)
    }

    @Test
    fun `default feed is IEX, not SIP or delayed_sip or live`() {
        val client = AlpacaStockMarketDataClient(
            credentialsProvider = NoAlpacaCredentialsProvider,
            webSocketFactory = StockNoOpWebSocketFactory(),
            clock = fixedClock,
        )
        assertEquals(AlpacaStreamEndpoint.IEX_STREAM_URL, client.feedUrl)
        assertFalse(client.feedUrl.contains("sip"))
        assertFalse(client.feedUrl.lowercase().contains("live"))
        assertFalse(client.feedUrl.contains("api.alpaca.markets"))
        assertFalse(client.feedUrl.contains("paper-api"))
    }

    @Test
    fun `constructor rejects unsafe endpoints`() {
        for (bad in listOf(
            "https://api.alpaca.markets/v2/orders",
            "https://paper-api.alpaca.markets/v2/account",
            "wss://stream.data.alpaca.markets/v2/live",
            "wss://stream.data.alpaca.markets/v2/sip",
            "wss://stream.data.alpaca.markets/v2/delayed_sip",
            "wss://stream.data.alpaca.markets/v2/iex/orders",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                AlpacaStockMarketDataClient(
                    credentialsProvider = NoAlpacaCredentialsProvider,
                    webSocketFactory = StockNoOpWebSocketFactory(),
                    clock = fixedClock,
                    endpoint = bad,
                )
            }
        }
    }

    @Test
    fun `initial status is disconnected`() = runTest(UnconfinedTestDispatcher()) {
        val client = AlpacaStockMarketDataClient(
            credentialsProvider = NoAlpacaCredentialsProvider,
            webSocketFactory = StockNoOpWebSocketFactory(),
            clock = fixedClock,
        )
        val status = client.connectionStatus.value
        assertEquals(MarketDataConnectionStatus.State.DISCONNECTED, status.state)
        assertEquals(MarketDataSource.ALPACA_STOCK_IEX, status.source)
    }

    @Test
    fun `connect with no credentials moves status to ERROR and does not open the socket`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StockRecordingWebSocketFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = NoAlpacaCredentialsProvider,
                webSocketFactory = factory,
                clock = fixedClock,
            )
            client.connect()

            val status = client.connectionStatus.value
            assertEquals(MarketDataConnectionStatus.State.ERROR, status.state)
            assertTrue(status.lastError is MarketDataError.AuthenticationFailed)
            assertEquals(0, factory.openCalls)
        }

    @Test
    fun `full happy-path subscribes to SPY only and never sends trades channel`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StockRecordingWebSocketFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("ABCDE", "secret") },
                webSocketFactory = factory,
                clock = fixedClock,
            )
            client.subscribe(setOf(AlpacaStreamEndpoint.STOCK_PRIMARY_SYMBOL))
            client.connect()

            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            val authMsg = factory.handle.sent.firstOrNull { it.contains("\"action\":\"auth\"") }
            assertNotNull(authMsg)
            val authJson = JSONObject(authMsg!!)
            assertEquals("auth", authJson.getString("action"))
            assertEquals("ABCDE", authJson.getString("key"))

            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            assertEquals(
                MarketDataConnectionStatus.State.CONNECTED,
                client.connectionStatus.value.state,
            )
            val subMsg = factory.handle.sent.firstOrNull { it.contains("\"action\":\"subscribe\"") }
            assertNotNull(subMsg)
            val subJson = JSONObject(subMsg!!)
            assertEquals("subscribe", subJson.getString("action"))
            assertEquals(1, subJson.getJSONArray("bars").length())
            assertEquals("SPY", subJson.getJSONArray("bars").getString(0))
            assertEquals("SPY", subJson.getJSONArray("quotes").getString(0))
            assertFalse(
                subJson.has("trades"),
                "subscribe message must not request the trade-execution channel",
            )
        }

    @Test
    fun `bar messages flow through updates with alpaca-iex-stream source`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StockRecordingWebSocketFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("ABCDE", "secret") },
                webSocketFactory = factory,
                clock = fixedClock,
            )

            val captured = CopyOnWriteArrayList<BootstrapMarketUpdate>()
            val collector = launch { client.updates.toList(captured) }

            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.deliver(
                """[{"T":"b","S":"SPY","o":520.10,"h":521.40,"l":519.80,"c":520.95,"v":12500,"t":"2026-06-03T14:31:00Z"}]""",
            )

            assertEquals(1, captured.size)
            val update = captured.single()
            assertEquals("SPY", update.symbol)
            assertEquals(520.95, update.price)
            assertEquals(520.10, update.open)
            assertEquals(521.40, update.high)
            assertEquals(519.80, update.low)
            assertEquals(520.95, update.close)
            assertEquals(12500.0, update.volume)
            assertEquals("alpaca-iex-stream", update.source)

            collector.cancel()
        }

    @Test
    fun `error 401 maps to AuthenticationFailed`() = runTest(UnconfinedTestDispatcher()) {
        val factory = StockRecordingWebSocketFactory()
        val client = AlpacaStockMarketDataClient(
            credentialsProvider = { AlpacaCredentials("ABCDE", "secret") },
            webSocketFactory = factory,
            clock = fixedClock,
        )
        client.connect()
        factory.deliver("""[{"T":"success","msg":"connected"}]""")
        factory.deliver("""[{"T":"error","code":401,"msg":"auth failed"}]""")

        val status = client.connectionStatus.value
        assertEquals(MarketDataConnectionStatus.State.ERROR, status.state)
        assertTrue(status.lastError is MarketDataError.AuthenticationFailed)
    }

    @Test
    fun `error 406 maps to SubscriptionRejected (insufficient subscription)`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StockRecordingWebSocketFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("ABCDE", "secret") },
                webSocketFactory = factory,
                clock = fixedClock,
            )
            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.deliver("""[{"T":"error","code":406,"msg":"connection limit exceeded"}]""")

            val status = client.connectionStatus.value
            assertEquals(MarketDataConnectionStatus.State.ERROR, status.state)
            assertTrue(status.lastError is MarketDataError.SubscriptionRejected)
        }

    @Test
    fun `subscription message is acknowledged without crashing`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StockRecordingWebSocketFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("ABCDE", "secret") },
                webSocketFactory = factory,
                clock = fixedClock,
            )
            client.subscribe(setOf("SPY"))
            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.deliver(
                """[{"T":"subscription","trades":[],"quotes":["SPY"],"bars":["SPY"]}]""",
            )
            assertEquals(
                MarketDataConnectionStatus.State.CONNECTED,
                client.connectionStatus.value.state,
            )
        }

    @Test
    fun `onFailure maps to StreamLost`() = runTest(UnconfinedTestDispatcher()) {
        val factory = StockRecordingWebSocketFactory()
        val client = AlpacaStockMarketDataClient(
            credentialsProvider = { AlpacaCredentials("ABCDE", "secret") },
            webSocketFactory = factory,
            clock = fixedClock,
        )
        client.connect()
        factory.listener.onFailure(RuntimeException("boom"), null)
        val status = client.connectionStatus.value
        assertEquals(MarketDataConnectionStatus.State.ERROR, status.state)
        assertTrue(status.lastError is MarketDataError.StreamLost)
    }

    @Test
    fun `disconnect closes the handle and resets status`() = runTest(UnconfinedTestDispatcher()) {
        val factory = StockRecordingWebSocketFactory()
        val client = AlpacaStockMarketDataClient(
            credentialsProvider = { AlpacaCredentials("ABCDE", "secret") },
            webSocketFactory = factory,
            clock = fixedClock,
        )
        client.connect()
        factory.deliver("""[{"T":"success","msg":"connected"}]""")
        factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
        client.disconnect()
        assertEquals(
            MarketDataConnectionStatus.State.DISCONNECTED,
            client.connectionStatus.value.state,
        )
        assertTrue(factory.handle.closed)
    }

    @Test
    fun `subscribe normalizes symbols`() = runTest(UnconfinedTestDispatcher()) {
        val client = AlpacaStockMarketDataClient(
            credentialsProvider = NoAlpacaCredentialsProvider,
            webSocketFactory = StockNoOpWebSocketFactory(),
            clock = fixedClock,
        )
        client.subscribe(setOf("  spy  ", "qqq", ""))
        assertEquals(setOf("SPY", "QQQ"), client.subscribedSymbols())
    }

    // --- Reflection contract: no trading methods on the stock client

    @TestFactory
    fun `AlpacaStockMarketDataClient declares no trading methods`(): List<DynamicTest> {
        val forbidden = listOf(
            "submitorder", "placeorder", "place_order", "buyorder", "sellorder",
            "withdraw", "deposit", "trading", "executeorder", "executetrade",
            "cancelorder", "getaccount", "updateaccount", "openposition",
            "closeposition", "getportfolio", "setbalance", "transferfund",
        )
        val methods = AlpacaStockMarketDataClient::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        return methods.map { methodName ->
            DynamicTest.dynamicTest("$methodName must not look like a trading method") {
                val lower = methodName.lowercase()
                for (bad in forbidden) {
                    assertFalse(
                        lower.contains(bad),
                        "Stock client method '$methodName' contains forbidden substring '$bad'",
                    )
                }
            }
        }
    }
}

// --- Test doubles ----------------------------------------------------

private class StockNoOpWebSocketFactory : AlpacaWebSocketFactory {
    override fun open(
        url: String,
        listener: AlpacaWebSocketListener,
    ): AlpacaWebSocketHandle = object : AlpacaWebSocketHandle {
        override fun send(text: String): Boolean = true
        override fun close(code: Int, reason: String) = Unit
    }
}

private class StockRecordingWebSocketFactory : AlpacaWebSocketFactory {
    var openCalls: Int = 0; private set
    lateinit var listener: AlpacaWebSocketListener; private set
    val handle: StockRecordingHandle = StockRecordingHandle()
    override fun open(
        url: String,
        listener: AlpacaWebSocketListener,
    ): AlpacaWebSocketHandle {
        openCalls += 1
        this.listener = listener
        listener.onOpen()
        return handle
    }
    fun deliver(payload: String) = listener.onMessage(payload)
}

private class StockRecordingHandle : AlpacaWebSocketHandle {
    val sent: MutableList<String> = CopyOnWriteArrayList()
    @Volatile var closed: Boolean = false
    override fun send(text: String): Boolean { sent += text; return true }
    override fun close(code: Int, reason: String) { closed = true }
}
