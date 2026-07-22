@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.market.source.alpaca

import com.vela.android.lab.data.market.tick.MarketTick
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlpacaStockQuoteEmissionTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-06-11T14:30:00.500Z") }

    @Test
    fun `quote frame emits a MarketTick on the quotes flow with correct fields`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = QuoteRecordingFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = fixedClock,
            )
            val captured = CopyOnWriteArrayList<MarketTick>()
            val collector = launch { client.quotes.toList(captured) }

            client.subscribe(setOf("SPY"))
            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.deliver(
                """[{"T":"q","S":"SPY","bp":520.10,"ap":520.20,"t":"2026-06-11T14:30:00.250Z"}]""",
            )

            assertEquals(1, captured.size)
            val tick = captured.single()
            assertEquals("SPY", tick.symbol)
            assertEquals(520.10, tick.bidPrice)
            assertEquals(520.20, tick.askPrice)
            assertEquals(520.20 - 520.10, tick.spread, 1e-9)
            // marketTs = 2026-06-11T14:30:00.250Z, receivedAt = clock = 2026-06-11T14:30:00.500Z
            assertEquals(250L, tick.latencyMillis)
            assertEquals("alpaca-iex-stream", tick.source)
            collector.cancel()
        }

    @Test
    fun `batched array carrying a quote and a bar processes both safely`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = QuoteRecordingFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = fixedClock,
            )
            val capturedQuotes = CopyOnWriteArrayList<MarketTick>()
            val quoteCollector = launch { client.quotes.toList(capturedQuotes) }
            val capturedBars = CopyOnWriteArrayList<com.vela.android.lab.data.market.BootstrapMarketUpdate>()
            val barCollector = launch { client.updates.toList(capturedBars) }

            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.deliver(
                """[
                  {"T":"q","S":"SPY","bp":520.10,"ap":520.20,"t":"2026-06-11T14:30:00.250Z"},
                  {"T":"b","S":"SPY","o":520.10,"h":521.40,"l":519.80,"c":520.95,"v":12500,"t":"2026-06-11T14:31:00Z"}
                ]""".trimIndent(),
            )

            assertEquals(1, capturedQuotes.size)
            assertEquals(1, capturedBars.size)
            assertEquals("SPY", capturedQuotes.single().symbol)
            assertEquals("SPY", capturedBars.single().symbol)
            quoteCollector.cancel()
            barCollector.cancel()
        }

    @Test
    fun `malformed quote payload does not crash and emits nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = QuoteRecordingFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = fixedClock,
            )
            val captured = CopyOnWriteArrayList<MarketTick>()
            val collector = launch { client.quotes.toList(captured) }

            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            // Each of these must be silently swallowed by the parser.
            factory.deliver("not json")
            factory.deliver("""[{"T":"q","S":"SPY"}]""")  // missing required ts
            factory.deliver("""[{"T":"q"}]""")
            factory.deliver("[]")
            factory.deliver("")

            assertEquals(0, captured.size)
            collector.cancel()
        }

    @Test
    fun `multi-symbol quotes route to distinct ticks`() =
        runTest(UnconfinedTestDispatcher()) {
            val factory = QuoteRecordingFactory()
            val client = AlpacaStockMarketDataClient(
                credentialsProvider = { AlpacaCredentials("PKABC", "secret") },
                webSocketFactory = factory,
                clock = fixedClock,
            )
            val captured = CopyOnWriteArrayList<MarketTick>()
            val collector = launch { client.quotes.toList(captured) }

            client.connect()
            factory.deliver("""[{"T":"success","msg":"connected"}]""")
            factory.deliver("""[{"T":"success","msg":"authenticated"}]""")
            factory.deliver(
                """[
                  {"T":"q","S":"SPY","bp":520.10,"ap":520.20,"t":"2026-06-11T14:30:00.250Z"},
                  {"T":"q","S":"QQQ","bp":430.10,"ap":430.20,"t":"2026-06-11T14:30:00.260Z"},
                  {"T":"q","S":"AAPL","bp":188.10,"ap":188.20,"t":"2026-06-11T14:30:00.270Z"}
                ]""".trimIndent(),
            )

            assertEquals(3, captured.size)
            val symbols = captured.map { it.symbol }.toSet()
            assertEquals(setOf("SPY", "QQQ", "AAPL"), symbols)
            // All received from one server message but each tick carries its own
            // market timestamp.
            assertNotNull(captured.firstOrNull { it.symbol == "SPY" })
            assertNotNull(captured.firstOrNull { it.symbol == "QQQ" })
            assertNotNull(captured.firstOrNull { it.symbol == "AAPL" })
            collector.cancel()
        }

    @Test
    fun `quotes flow stays read-only - no order or trading shapes on the client`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "place_order", "buyorder", "sellorder",
            "trading", "executeorder", "executetrade", "cancelorder",
            "getaccount", "updateaccount", "openposition", "closeposition",
            "getportfolio", "setbalance", "transferfund",
        )
        val methods = AlpacaStockMarketDataClient::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        for (name in methods) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertTrue(
                    !lower.contains(bad),
                    "Stock client method '$name' contains forbidden substring '$bad'",
                )
            }
        }
    }
}

// --- Test doubles ----------------------------------------------------

private class QuoteRecordingFactory : AlpacaWebSocketFactory {
    var openCalls: Int = 0; private set
    lateinit var listener: AlpacaWebSocketListener; private set
    val handle: QuoteRecordingHandle = QuoteRecordingHandle()
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

private class QuoteRecordingHandle : AlpacaWebSocketHandle {
    val sent: MutableList<String> = CopyOnWriteArrayList()
    @Volatile var closed: Boolean = false
    override fun send(text: String): Boolean { sent += text; return true }
    override fun close(code: Int, reason: String) { closed = true }
}
