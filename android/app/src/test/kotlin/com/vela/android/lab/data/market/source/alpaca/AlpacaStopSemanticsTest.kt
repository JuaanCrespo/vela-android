@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.market.source.alpaca

import com.vela.android.lab.data.market.source.MarketDataConnectionStatus
import com.vela.android.lab.data.market.source.MarketDataError
import com.vela.android.lab.data.market.source.StreamHealth
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Phase 2.h: clean stop semantics + stale WebSocket callback
 * protection for both Alpaca read-only Market Data clients.
 *
 *  - User-initiated `disconnect()` must end in DISCONNECTED with no
 *    active error and no `lastErrorType` on health.
 *  - OkHttp's post-close `onFailure` / `onClosed` callback that
 *    arrives **after** the user's `disconnect()` must NOT demote
 *    the clean DISCONNECTED back to ERROR / StreamLost.
 *  - A stale callback (from a closed older session) must NOT mutate
 *    the state of a newer session that is currently CONNECTING /
 *    AUTHENTICATED / SUBSCRIBED.
 *  - Unexpected `onFailure` / `onClosed` while running (no prior
 *    user-initiated `disconnect()`) MUST still be observable:
 *    `onFailure` → ERROR / StreamLost, `onClosed` → DISCONNECTED.
 */
class AlpacaStopSemanticsTest {

    private val baseClock: () -> Instant = { Instant.parse("2026-06-11T14:30:00Z") }

    // --- Test stream client --------------------------------------------

    @Test
    fun `test stream - user Stop ends DISCONNECTED with no active error`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.session(0).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(0).deliver("""[{"T":"success","msg":"authenticated"}]""")
            client.disconnect()

            val status = client.connectionStatus.value
            val health = client.health.value
            assertEquals(MarketDataConnectionStatus.State.DISCONNECTED, status.state)
            assertNull(status.lastError, "status.lastError must be null after user Stop")
            assertEquals(StreamHealth.Phase.DISCONNECTED, health.phase)
            assertNull(health.lastErrorType)
            assertNull(health.lastErrorMessage)
        }

    @Test
    fun `test stream - post-stop onFailure is treated as clean DISCONNECTED, not ERROR`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.session(0).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(0).deliver("""[{"T":"success","msg":"authenticated"}]""")
            client.disconnect()

            // OkHttp post-close failure fires AFTER the user stopped.
            factory.session(0).listener.onFailure(RuntimeException("late ok-http failure"), null)

            val status = client.connectionStatus.value
            val health = client.health.value
            assertEquals(MarketDataConnectionStatus.State.DISCONNECTED, status.state)
            assertNull(status.lastError)
            assertEquals(StreamHealth.Phase.DISCONNECTED, health.phase)
            assertNull(health.lastErrorType)
        }

    @Test
    fun `test stream - post-stop onClosed is treated as clean DISCONNECTED`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.session(0).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(0).deliver("""[{"T":"success","msg":"authenticated"}]""")
            client.disconnect()
            factory.session(0).listener.onClosed(1000, "late server-side close")

            val status = client.connectionStatus.value
            assertEquals(MarketDataConnectionStatus.State.DISCONNECTED, status.state)
            assertNull(status.lastError)
        }

    @Test
    fun `test stream - unexpected onFailure while running becomes ERROR StreamLost`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.session(0).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(0).deliver("""[{"T":"success","msg":"authenticated"}]""")
            // No disconnect: this is an unexpected network failure.
            factory.session(0).listener.onFailure(RuntimeException("boom"), null)

            val status = client.connectionStatus.value
            val health = client.health.value
            assertEquals(MarketDataConnectionStatus.State.ERROR, status.state)
            assertNotNull(status.lastError)
            assertEquals(StreamHealth.Phase.ERROR, health.phase)
            assertEquals("StreamLost", health.lastErrorType)
        }

    @Test
    fun `test stream - prior 401 error is cleared on user Stop`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaTestStreamMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.session(0).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(0).deliver("""[{"T":"error","code":401,"msg":"auth failed"}]""")
            assertEquals(StreamHealth.Phase.ERROR, client.health.value.phase)
            assertEquals("AuthenticationFailed", client.health.value.lastErrorType)

            client.disconnect()

            assertEquals(MarketDataConnectionStatus.State.DISCONNECTED, client.connectionStatus.value.state)
            assertNull(client.connectionStatus.value.lastError)
            assertEquals(StreamHealth.Phase.DISCONNECTED, client.health.value.phase)
            assertNull(client.health.value.lastErrorType)
        }

    // --- Stock IEX client ---------------------------------------------

    @Test
    fun `stock - user Stop ends DISCONNECTED with no active error`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.subscribe(setOf("SPY"))
            client.connect()
            factory.session(0).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(0).deliver("""[{"T":"success","msg":"authenticated"}]""")
            client.disconnect()

            val status = client.connectionStatus.value
            val health = client.health.value
            assertEquals(MarketDataConnectionStatus.State.DISCONNECTED, status.state)
            assertNull(status.lastError)
            assertEquals(StreamHealth.Phase.DISCONNECTED, health.phase)
            assertNull(health.lastErrorType)
        }

    @Test
    fun `stock - post-stop onFailure is treated as clean DISCONNECTED, not ERROR`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.session(0).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(0).deliver("""[{"T":"success","msg":"authenticated"}]""")
            client.disconnect()
            factory.session(0).listener.onFailure(RuntimeException("late ok-http failure"), null)

            val status = client.connectionStatus.value
            val health = client.health.value
            assertEquals(MarketDataConnectionStatus.State.DISCONNECTED, status.state)
            assertNull(status.lastError)
            assertEquals(StreamHealth.Phase.DISCONNECTED, health.phase)
            assertNull(health.lastErrorType)
        }

    @Test
    fun `stock - post-stop onClosed produces clean DISCONNECTED`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.session(0).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(0).deliver("""[{"T":"success","msg":"authenticated"}]""")
            client.disconnect()
            factory.session(0).listener.onClosed(1000, "late server-side close")

            assertEquals(MarketDataConnectionStatus.State.DISCONNECTED, client.connectionStatus.value.state)
            assertNull(client.connectionStatus.value.lastError)
        }

    @Test
    fun `stock - unexpected onFailure while running becomes ERROR StreamLost`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()
            factory.session(0).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(0).deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.session(0).listener.onFailure(RuntimeException("boom"), null)

            assertEquals(MarketDataConnectionStatus.State.ERROR, client.connectionStatus.value.state)
            assertEquals("StreamLost", client.health.value.lastErrorType)
        }

    // --- Stale-callback protection ------------------------------------

    @Test
    fun `stock - stale onFailure from session 0 does not mutate session 1 state`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            // Session 0
            client.connect()
            factory.session(0).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(0).deliver("""[{"T":"success","msg":"authenticated"}]""")
            client.disconnect()
            // Session 1 — opens a new socket
            client.connect()
            factory.session(1).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(1).deliver("""[{"T":"success","msg":"authenticated"}]""")
            assertEquals(
                MarketDataConnectionStatus.State.CONNECTED,
                client.connectionStatus.value.state,
            )
            assertEquals(StreamHealth.Phase.AUTHENTICATED, client.health.value.phase)

            // Stale callback from session 0 arrives late.
            factory.session(0).listener.onFailure(RuntimeException("late stale boom"), null)
            // State must remain session 1's CONNECTED — not flipped to ERROR.
            assertEquals(
                MarketDataConnectionStatus.State.CONNECTED,
                client.connectionStatus.value.state,
            )
            assertNull(client.connectionStatus.value.lastError)
            assertEquals(StreamHealth.Phase.AUTHENTICATED, client.health.value.phase)
            assertNull(client.health.value.lastErrorType)
        }

    @Test
    fun `stock - stale onClosed from session 0 does not mutate session 1 state`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()  // session 0
            factory.session(0).deliver("""[{"T":"success","msg":"connected"}]""")
            client.disconnect()
            client.connect()  // session 1
            factory.session(1).deliver("""[{"T":"success","msg":"connected"}]""")
            factory.session(1).deliver("""[{"T":"success","msg":"authenticated"}]""")
            assertEquals(
                MarketDataConnectionStatus.State.CONNECTED,
                client.connectionStatus.value.state,
            )

            // Late stale onClosed from session 0
            factory.session(0).listener.onClosed(1006, "stale")

            assertEquals(
                MarketDataConnectionStatus.State.CONNECTED,
                client.connectionStatus.value.state,
            )
        }

    @Test
    fun `stock - stale onMessage from session 0 cannot drive session 1 messages`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = StopMultiSessionFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = baseClock,
            )
            client.connect()  // session 0
            client.disconnect()
            client.connect()  // session 1
            val lastMsgBefore = client.health.value.lastMessageAtEpochMillis

            // Late stale onMessage from session 0 — should be ignored.
            factory.session(0).listener.onMessage("""[{"T":"success","msg":"connected"}]""")

            assertEquals(lastMsgBefore, client.health.value.lastMessageAtEpochMillis)
        }
}

// --- Test double that retains every session's listener and handle.

private class StopMultiSessionFactory : AlpacaWebSocketFactory {
    private val sessions: MutableList<StopRecordedSession> = CopyOnWriteArrayList()

    override fun open(
        url: String,
        listener: AlpacaWebSocketListener,
    ): AlpacaWebSocketHandle {
        val handle = StopRecordedHandle()
        val session = StopRecordedSession(listener, handle)
        sessions += session
        listener.onOpen()
        return handle
    }

    fun session(index: Int): StopRecordedSession = sessions[index]
    val openCalls: Int get() = sessions.size
}

private class StopRecordedSession(
    val listener: AlpacaWebSocketListener,
    val handle: StopRecordedHandle,
) {
    fun deliver(payload: String) = listener.onMessage(payload)
}

private class StopRecordedHandle : AlpacaWebSocketHandle {
    val sent: MutableList<String> = CopyOnWriteArrayList()
    @Volatile var closed: Boolean = false
    override fun send(text: String): Boolean { sent += text; return true }
    override fun close(code: Int, reason: String) { closed = true }
}
