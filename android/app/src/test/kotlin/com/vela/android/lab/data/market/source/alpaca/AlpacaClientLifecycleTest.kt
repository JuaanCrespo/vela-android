@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.market.source.alpaca

import com.vela.android.lab.data.market.BootstrapMarketUpdate
import com.vela.android.lab.data.market.source.MarketDataConnectionStatus
import com.vela.android.lab.data.market.source.MarketDataError
import com.vela.android.lab.data.market.source.StreamHealth
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 2.f lifecycle / robustness contract tests exercised on the
 * real test-stream and stock clients via the in-memory websocket
 * fake. Cross-cutting properties:
 *
 *  - repeated `connect()` opens only one socket
 *  - repeated `disconnect()` is safe
 *  - missing-credentials path does NOT open a socket and does NOT
 *    auto-retry; the user must invoke `connect()` again
 *  - `reconnectAttempts` only advances on user-initiated reconnects
 *  - malformed JSON delivered to the listener does not crash and
 *    does not emit a `BootstrapMarketUpdate`
 *  - the server-sent `subscription` frame transitions the health
 *    phase to SUBSCRIBED
 *  - `onMessage` updates the health `lastMessageAtEpochMillis`
 *  - the credentials never appear in `connectionStatus` or `health`
 *    after a successful auth (only `Credentials configured` was the
 *    UI surface in Phase 2.c.1; this test re-asserts at the client
 *    layer)
 */
class AlpacaClientLifecycleTest {

    private val baseClock: () -> Instant = { Instant.parse("2026-06-11T14:30:00Z") }

    // --- Test stream client --------------------------------------------

    @Test
    fun `test stream client - repeated connect with creds opens only one socket`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.subscribe(setOf("FAKEPACA"))
            client.connect()
            client.connect()
            client.connect()
            assertEquals(1, factory.openCalls)
        }

    @Test
    fun `test stream client - repeated disconnect is idempotent`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            client.disconnect()
            client.disconnect()
            client.disconnect()
            assertEquals(
                MarketDataConnectionStatus.State.DISCONNECTED,
                client.connectionStatus.value.state,
            )
            // close was called only on the live handle; redundant
            // disconnects are no-ops at the OkHttp layer.
            assertTrue(factory.handle.closed)
        }

    @Test
    fun `test stream client - missing credentials never opens the socket and never auto-retries`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = NoAlpacaCredentialsProvider,
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            client.connect()
            client.connect()
            assertEquals(0, factory.openCalls)
            val status = client.connectionStatus.value
            assertEquals(MarketDataConnectionStatus.State.ERROR, status.state)
            assertTrue(status.lastError is MarketDataError.AuthenticationFailed)
            // 3 user-initiated attempts → 2 reconnect ticks.
            assertEquals(2, client.health.value.reconnectAttempts)
            assertEquals(StreamHealth.Phase.ERROR, client.health.value.phase)
        }

    @Test
    fun `test stream client - malformed JSON does not crash and emits no update`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )

            val captured = CopyOnWriteArrayList<BootstrapMarketUpdate>()
            val collector = launch { client.updates.toList(captured) }

            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")

            // These all must be safely swallowed by the parser:
            factory.deliver("this is not json")
            factory.deliver("""{"not":"an","array":true}""")
            factory.deliver("""[{"T":"b","S":"FAKEPACA"}]""")    // missing required fields
            factory.deliver("")
            factory.deliver("[]")

            assertEquals(0, captured.size)
            assertEquals(
                MarketDataConnectionStatus.State.CONNECTED,
                client.connectionStatus.value.state,
            )
            collector.cancel()
        }

    @Test
    fun `test stream client - subscription frame transitions health to SUBSCRIBED`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.subscribe(setOf("FAKEPACA"))
            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            assertEquals(StreamHealth.Phase.AUTHENTICATED, client.health.value.phase)

            factory.deliver(
                """[{"T":"subscription","trades":[],"quotes":["FAKEPACA"],"bars":["FAKEPACA"]}]""",
            )

            val h = client.health.value
            assertEquals(StreamHealth.Phase.SUBSCRIBED, h.phase)
            assertEquals(setOf("FAKEPACA"), h.subscribed)
        }

    @Test
    fun `test stream client - onMessage advances lastMessageAt`() =
        runTest(UnconfinedTestDispatcher()) {
            var nowMs = 1_000L
            val factory = LifecycleFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = { Instant.ofEpochMilli(nowMs) },
            )
            client.connect()
            nowMs = 2_000L
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            val firstMsgTs = client.health.value.lastMessageAtEpochMillis
            assertNotNull(firstMsgTs)
            assertEquals(2_000L, firstMsgTs)

            nowMs = 5_000L
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            assertEquals(5_000L, client.health.value.lastMessageAtEpochMillis)
        }

    @Test
    fun `test stream client - credentials never appear in connectionStatus or health after auth`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC1234", "topsecretvalue") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")

            val statusStr = client.connectionStatus.value.toString()
            val healthStr = client.health.value.toString()
            assertFalse(statusStr.contains("topsecretvalue"))
            assertFalse(statusStr.contains("PKABC1234"))
            assertFalse(healthStr.contains("topsecretvalue"))
            assertFalse(healthStr.contains("PKABC1234"))
        }

    @Test
    fun `test stream client - REAL endpoint is still rejected at construction time`() {
        for (forbidden in listOf(
            "wss://stream.data.alpaca.markets/v2/live",
            "https://api.alpaca.markets/v2/orders",
            "https://paper-api.alpaca.markets/v2/account",
        )) {
            try {
                AlpacaTestStreamMarketDataClient(
                    credentialsProvider = NoAlpacaCredentialsProvider,
                    webSocketFactory = LifecycleFactory(),
                    clock = baseClock,
                    endpoint = forbidden,
                )
                error("Expected IllegalArgumentException for $forbidden")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    // --- Stock IEX client ----------------------------------------------

    @Test
    fun `stock client - repeated connect with creds opens only one socket`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.subscribe(setOf("SPY"))
            client.connect()
            client.connect()
            client.connect()
            assertEquals(1, factory.openCalls)
        }

    @Test
    fun `stock client - repeated disconnect is idempotent`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            client.disconnect()
            client.disconnect()
            client.disconnect()
            assertEquals(
                MarketDataConnectionStatus.State.DISCONNECTED,
                client.connectionStatus.value.state,
            )
            assertTrue(factory.handle.closed)
        }

    @Test
    fun `stock client - missing credentials never opens the socket and never auto-retries`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = NoAlpacaCredentialsProvider,
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            client.connect()
            client.connect()
            assertEquals(0, factory.openCalls)
            assertTrue(client.connectionStatus.value.lastError is MarketDataError.AuthenticationFailed)
            assertEquals(2, client.health.value.reconnectAttempts)
            assertEquals(StreamHealth.Phase.ERROR, client.health.value.phase)
        }

    @Test
    fun `stock client - malformed JSON does not crash and emits no update`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )

            val captured = CopyOnWriteArrayList<BootstrapMarketUpdate>()
            val collector = launch { client.updates.toList(captured) }

            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")

            factory.deliver("this is not json")
            factory.deliver("""{"not":"an","array":true}""")
            factory.deliver("""[{"T":"b","S":"SPY"}]""")  // missing required fields
            factory.deliver("")
            factory.deliver("[]")

            assertEquals(0, captured.size)
            assertEquals(
                MarketDataConnectionStatus.State.CONNECTED,
                client.connectionStatus.value.state,
            )
            collector.cancel()
        }

    @Test
    fun `stock client - subscription frame transitions health to SUBSCRIBED for SPY`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.subscribe(setOf("SPY"))
            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.deliver(
                """[{"T":"subscription","trades":[],"quotes":["SPY"],"bars":["SPY"]}]""",
            )
            assertEquals(StreamHealth.Phase.SUBSCRIBED, client.health.value.phase)
            assertEquals(setOf("SPY"), client.health.value.subscribed)
        }

    @Test
    fun `stock client - SPY bar still flows end-to-end after lifecycle hardening`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            val captured = CopyOnWriteArrayList<BootstrapMarketUpdate>()
            val collector = launch { client.updates.toList(captured) }

            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.deliver(
                """[{"T":"b","S":"SPY","o":520.10,"h":521.40,"l":519.80,"c":520.95,"v":12500,"t":"2026-06-11T14:31:00Z"}]""",
            )

            assertEquals(1, captured.size)
            assertEquals("SPY", captured.single().symbol)
            assertEquals("alpaca-iex-stream", captured.single().source)
            collector.cancel()
        }

    @Test
    fun `stock client - REAL and trading endpoints rejected at construction time`() {
        for (forbidden in listOf(
            "wss://stream.data.alpaca.markets/v2/live",
            "wss://stream.data.alpaca.markets/v2/sip",
            "wss://stream.data.alpaca.markets/v2/delayed_sip",
            "https://api.alpaca.markets/v2/orders",
            "https://paper-api.alpaca.markets/v2/account",
            "wss://stream.data.alpaca.markets/v2/iex/orders",
        )) {
            try {
                AlpacaStockMarketDataClient(
                    credentialsProvider = NoAlpacaCredentialsProvider,
                    webSocketFactory = LifecycleFactory(),
                    clock = baseClock,
                    endpoint = forbidden,
                )
                error("Expected IllegalArgumentException for $forbidden")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `stock client - reconnect after disconnect increments counter exactly once`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            assertEquals(0, client.health.value.reconnectAttempts)

            client.disconnect()
            client.connect()
            // 2nd attempt → reconnectAttempts = 1
            assertEquals(1, client.health.value.reconnectAttempts)

            client.disconnect()
            client.connect()
            // 3rd attempt → reconnectAttempts = 2
            assertEquals(2, client.health.value.reconnectAttempts)
        }

    @Test
    fun `stock client - lastError type is exposed on health after a 401`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"error","code":401,"msg":"auth failed"}]""")
            val h = client.health.value
            assertEquals(StreamHealth.Phase.ERROR, h.phase)
            assertEquals("AuthenticationFailed", h.lastErrorType)
            assertNotNull(h.lastErrorMessage)
        }

    @Test
    fun `stock client - onFailure surfaces StreamLost on health`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.listener.onFailure(RuntimeException("boom"), null)
            assertEquals(StreamHealth.Phase.ERROR, client.health.value.phase)
            assertEquals("StreamLost", client.health.value.lastErrorType)
        }

    @Test
    fun `stock client - server onClosed transitions health to DISCONNECTED`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = LifecycleFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.listener.onClosed(1000, "server closed")
            assertEquals(StreamHealth.Phase.DISCONNECTED, client.health.value.phase)
            assertNull(client.health.value.lastErrorType)
        }
}

// --- Test doubles ----------------------------------------------------

private class LifecycleFactory : AlpacaWebSocketFactory {
    var openCalls: Int = 0; private set
    lateinit var listener: AlpacaWebSocketListener; private set
    val handle: LifecycleHandle = LifecycleHandle()

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

private class LifecycleHandle : AlpacaWebSocketHandle {
    val sent: MutableList<String> = CopyOnWriteArrayList()
    @Volatile var closed: Boolean = false
    override fun send(text: String): Boolean { sent += text; return true }
    override fun close(code: Int, reason: String) { closed = true }
}
