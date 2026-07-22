@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.market.source

import com.vela.android.lab.data.market.BootstrapMarketUpdate
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StubPaperMarketDataClientTest {

    private val fixedClock: () -> Instant = { Instant.parse("2026-01-01T14:30:00Z") }

    @Test
    fun `source is OFFLINE_STUB, never any live source`() {
        val client = StubPaperMarketDataClient(clock = fixedClock)
        assertEquals(MarketDataSource.OFFLINE_STUB, client.source)
        assertFalse(client.source.name.contains("LIVE"))
    }

    @Test
    fun `initial connection status is disconnected`() = runTest(UnconfinedTestDispatcher()) {
        val client = StubPaperMarketDataClient(clock = fixedClock)
        val status = client.connectionStatus.value
        assertEquals(MarketDataConnectionStatus.State.DISCONNECTED, status.state)
        assertEquals(MarketDataSource.OFFLINE_STUB, status.source)
    }

    @Test
    fun `connect flips status to CONNECTED, disconnect flips back`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = StubPaperMarketDataClient(clock = fixedClock)

            client.connect()
            assertEquals(
                MarketDataConnectionStatus.State.CONNECTED,
                client.connectionStatus.value.state,
            )

            client.disconnect()
            assertEquals(
                MarketDataConnectionStatus.State.DISCONNECTED,
                client.connectionStatus.value.state,
            )
        }

    @Test
    fun `subscribe normalizes symbols and unsubscribe removes them`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = StubPaperMarketDataClient(clock = fixedClock)

            client.subscribe(setOf("btcusd", "SPY", "  ", ""))
            assertEquals(setOf("BTC/USD", "SPY"), client.subscribedSymbols())

            client.unsubscribe(setOf("btc/usd"))
            assertEquals(setOf("SPY"), client.subscribedSymbols())
        }

    @Test
    fun `emitDemoUpdate produces deterministic BTC and SPY price ticks`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = StubPaperMarketDataClient(clock = fixedClock)

            val collected = mutableListOf<BootstrapMarketUpdate>()
            val collector = launch {
                client.updates.toList(collected)
            }

            val btc1 = client.emitDemoUpdate("btcusd")
            val btc2 = client.emitDemoUpdate("BTC/USD")
            val spy1 = client.emitDemoUpdate("SPY")

            assertEquals("BTC/USD", btc1.symbol)
            assertEquals(50_005.0, btc1.price)
            assertEquals("BTC/USD", btc2.symbol)
            assertEquals(50_010.0, btc2.price)
            assertEquals("SPY", spy1.symbol)
            assertEquals(400.25, spy1.price)

            // Sequence is monotonically increasing across all calls.
            assertEquals(1, btc1.sequence)
            assertEquals(2, btc2.sequence)
            assertEquals(3, spy1.sequence)

            // Source string identifies stub origin.
            assertEquals("offline-stub", btc1.source)

            collector.cancel()
        }

    @Test
    fun `updates SharedFlow does not replay old emissions to new collectors`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = StubPaperMarketDataClient(clock = fixedClock)

            // Emit before anyone collects — the SharedFlow has no
            // replay buffer, so this update should not be observed
            // by a subscriber that joins afterwards.
            client.emitDemoUpdate("BTC/USD")

            val seen = mutableListOf<BootstrapMarketUpdate>()
            val collector = launch {
                client.updates.toList(seen)
            }

            // No new emission → collector sees nothing.
            assertTrue(seen.isEmpty())

            // Now emit one more — the late subscriber sees this.
            client.emitDemoUpdate("SPY")
            // Allow the launched collector to observe the emission.
            assertEquals(1, seen.size)
            assertEquals("SPY", seen[0].symbol)

            collector.cancel()
        }

    @Test
    fun `updates emits timestamps from the injected clock`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = StubPaperMarketDataClient(clock = fixedClock)
            val update = client.emitDemoUpdate("BTC/USD")
            assertEquals(Instant.parse("2026-01-01T14:30:00Z"), update.timestamp)
        }

    @Test
    fun `connection-status flow surfaces the initial disconnected state`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = StubPaperMarketDataClient(clock = fixedClock)
            val initial = client.connectionStatus.first()
            assertEquals(MarketDataConnectionStatus.State.DISCONNECTED, initial.state)
            assertEquals(MarketDataSource.OFFLINE_STUB, initial.source)
        }
}
