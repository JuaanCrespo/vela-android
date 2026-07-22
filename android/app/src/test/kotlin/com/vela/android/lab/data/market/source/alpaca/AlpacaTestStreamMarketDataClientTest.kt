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
import org.junit.jupiter.api.Test

class AlpacaTestStreamMarketDataClientTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-01-01T14:30:00Z") }

    @Test
    fun `source is ALPACA_TEST_STREAM`() {
        val client = AlpacaTestStreamMarketDataClient(
            credentialsProvider = NoAlpacaCredentialsProvider,
            webSocketFactory = NoOpWebSocketFactory(),
            clock = fixedClock,
        )
        assertEquals(MarketDataSource.ALPACA_TEST_STREAM, client.source)
    }

    @Test
    fun `constructor rejects unsafe endpoints`() {
        assertThrows(IllegalArgumentException::class.java) {
            AlpacaTestStreamMarketDataClient(
                credentialsProvider = NoAlpacaCredentialsProvider,
                webSocketFactory = NoOpWebSocketFactory(),
                clock = fixedClock,
                endpoint = "https://api.alpaca.markets/v2/orders",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AlpacaTestStreamMarketDataClient(
                credentialsProvider = NoAlpacaCredentialsProvider,
                webSocketFactory = NoOpWebSocketFactory(),
                clock = fixedClock,
                endpoint = "wss://stream.data.alpaca.markets/v2/iex",
            )
        }
    }

    @Test
    fun `initial status is disconnected`() = runTest(UnconfinedTestDispatcher()) {
        val client = AlpacaTestStreamMarketDataClient(
            credentialsProvider = NoAlpacaCredentialsProvider,
            webSocketFactory = NoOpWebSocketFactory(),
            clock = fixedClock,
        )
        val status = client.connectionStatus.value
        assertEquals(MarketDataConnectionStatus.State.DISCONNECTED, status.state)
        assertEquals(MarketDataSource.ALPACA_TEST_STREAM, status.source)
    }

    @Test
    fun `connect with no credentials moves status to ERROR with AuthenticationFailed and does not open the socket`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = RecordingWebSocketFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = NoAlpacaCredentialsProvider,
                webSocketFactory = factory,
                clock = fixedClock,
            )

            client.connect()

            val status = client.connectionStatus.value
            assertEquals(MarketDataConnectionStatus.State.ERROR, status.state)
            assertTrue(status.lastError is MarketDataError.AuthenticationFailed)
            assertEquals(
                0,
                factory.openCalls,
                "WebSocket factory must not be invoked when credentials are missing",
            )
        }

    @Test
    fun `full happy-path drives status to CONNECTED and sends auth then subscribe`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = RecordingWebSocketFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("ABCDE", "secret") },
                webSocketFactory = factory,
                clock = fixedClock,
            )
            client.subscribe(setOf("FAKEPACA"))

            client.connect()

            assertEquals(1, factory.openCalls)
            assertEquals(
                MarketDataConnectionStatus.State.CONNECTING,
                client.connectionStatus.value.state,
            )

            // Server: connected → client should send auth.
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            val authMsg = factory.handle.sent.firstOrNull { it.contains("\"action\":\"auth\"") }
            assertNotNull(authMsg)
            val authJson = JSONObject(authMsg!!)
            assertEquals("auth", authJson.getString("action"))
            assertEquals("ABCDE", authJson.getString("key"))
            assertEquals("secret", authJson.getString("secret"))

            // Server: authenticated → client status CONNECTED and sends subscribe.
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
            assertEquals("FAKEPACA", subJson.getJSONArray("bars").getString(0))
            assertEquals("FAKEPACA", subJson.getJSONArray("quotes").getString(0))
            assertFalse(subJson.has("trades"), "subscribe message must not request trade execution channel")
        }

    @Test
    fun `bar messages flow through updates as BootstrapMarketUpdate values`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = RecordingWebSocketFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("ABCDE", "secret") },
                webSocketFactory = factory,
                clock = fixedClock,
            )

            val captured = CopyOnWriteArrayList<BootstrapMarketUpdate>()
            val collector = launch {
                client.updates.toList(captured)
            }

            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.deliver(
                """[{"T":"b","S":"FAKEPACA","o":1.0,"h":1.2,"l":0.9,"c":1.1,"v":250,"t":"2026-01-01T14:31:00Z"}]""",
            )

            assertEquals(1, captured.size)
            val update = captured.single()
            assertEquals("FAKEPACA", update.symbol)
            assertEquals(1.1, update.price)
            assertEquals(1.0, update.open)
            assertEquals(1.2, update.high)
            assertEquals(0.9, update.low)
            assertEquals(1.1, update.close)
            assertEquals(250.0, update.volume)
            assertEquals("alpaca-test-stream", update.source)

            collector.cancel()
        }

    @Test
    fun `server error 401 maps to AuthenticationFailed status`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = RecordingWebSocketFactory()
            val client = AlpacaTestStreamMarketDataClient(
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
    fun `onFailure maps to StreamLost status`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = RecordingWebSocketFactory()
            val client = AlpacaTestStreamMarketDataClient(
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
    fun `disconnect closes the handle and resets status`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = RecordingWebSocketFactory()
            val client = AlpacaTestStreamMarketDataClient(
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
        val client = AlpacaTestStreamMarketDataClient(
            credentialsProvider = NoAlpacaCredentialsProvider,
            webSocketFactory = NoOpWebSocketFactory(),
            clock = fixedClock,
        )
        client.subscribe(setOf("  fakepaca  ", "btcusd", ""))
        assertEquals(setOf("FAKEPACA", "BTC/USD"), client.subscribedSymbols())
    }
}

// --- Test doubles ----------------------------------------------------

private class NoOpWebSocketFactory : AlpacaWebSocketFactory {
    override fun open(
        url: String,
        listener: AlpacaWebSocketListener,
    ): AlpacaWebSocketHandle {
        return object : AlpacaWebSocketHandle {
            override fun send(text: String): Boolean = true
            override fun close(code: Int, reason: String) = Unit
        }
    }
}

private class RecordingWebSocketFactory : AlpacaWebSocketFactory {

    var openCalls: Int = 0
        private set

    lateinit var listener: AlpacaWebSocketListener
        private set

    val handle: RecordingHandle = RecordingHandle()

    override fun open(
        url: String,
        listener: AlpacaWebSocketListener,
    ): AlpacaWebSocketHandle {
        openCalls += 1
        this.listener = listener
        // Mimic the WebSocket lifecycle: onOpen fires immediately.
        listener.onOpen()
        return handle
    }

    /** Inject a server-side message into the client's listener. */
    fun deliver(payload: String) {
        listener.onMessage(payload)
    }
}

private class RecordingHandle : AlpacaWebSocketHandle {
    val sent: MutableList<String> = CopyOnWriteArrayList()
    @Volatile var closed: Boolean = false

    override fun send(text: String): Boolean {
        sent += text
        return true
    }

    override fun close(code: Int, reason: String) {
        closed = true
    }
}
