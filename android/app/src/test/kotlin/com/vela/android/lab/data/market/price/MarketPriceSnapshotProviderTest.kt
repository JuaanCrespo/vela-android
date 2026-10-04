@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.market.price

import com.vela.android.lab.data.market.tick.MarketDataProvenance
import com.vela.android.lab.data.market.tick.MarketTick
import com.vela.android.lab.data.market.tick.MarketTickBuffer
import com.vela.android.lab.data.repository.MarketDataRepository
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 3.a.1-C execution-authority resolver. The provider reads only the in-memory tick buffer.
 * Every price below is a deterministic, credential-free fixture.
 */
class MarketPriceSnapshotProviderTest {

    private val nowMs = 100_000L

    private fun newProvider(tickBuffer: MarketTickBuffer): MarketPriceSnapshotProvider =
        MarketPriceSnapshotProvider(
            tickBuffer = tickBuffer,
            clock = { Instant.ofEpochMilli(nowMs) },
        )

    private fun tick(
        symbol: String = "SPY",
        bid: Double = 520.10,
        ask: Double = 520.20,
        eventAgeMillis: Long = 500L,
        receivedAgeMillis: Long = eventAgeMillis,
        provenance: MarketDataProvenance = MarketDataProvenance.ALPACA_IEX_REAL_TIME,
    ): MarketTick = MarketTick(
        symbol = symbol,
        bidPrice = bid,
        askPrice = ask,
        marketTimestampMillis = nowMs - eventAgeMillis,
        receivedAtMillis = nowMs - receivedAgeMillis,
        source = "alpaca-iex-stream",
        provenance = provenance,
    )

    private fun bufferWith(vararg ticks: MarketTick): MarketTickBuffer =
        MarketTickBuffer().also { buffer -> ticks.forEach(buffer::pushQuote) }

    @Test
    fun `fresh real-feed IEX quote is the only execution authority and resolves to its mid`() = runBlocking {
        val reference = newProvider(bufferWith(tick())).executionReferenceFor("SPY")
        val trusted = reference as ExecutionReferencePrice.Trusted
        assertEquals("SPY", trusted.symbol)
        assertEquals(MarketPriceSource.LIVE_QUOTE_MID, trusted.source)
        assertEquals(MarketDataProvenance.ALPACA_IEX_REAL_TIME, trusted.provenance)
        assertEquals(520.15, trusted.price, 1e-9)
        assertEquals(500L, trusted.ageMillis)
    }

    @Test
    fun `no live quote yields NO_LIVE_QUOTE even if a persisted Room store holds a recent bar`() = runBlocking {
        // The provider has no MarketDataRepository (see the structural test below), so a persisted
        // bar, legacy or demo, cannot answer this call. Nothing is seeded into the provider.
        val reference = newProvider(MarketTickBuffer()).executionReferenceFor("SPY")
        assertEquals(
            ExecutionPriceRejection.NO_LIVE_QUOTE,
            (reference as ExecutionReferencePrice.Rejected).reason,
        )
    }

    @Test
    fun `provider has no dependency on the persisted market store`() {
        val constructorTypes = MarketPriceSnapshotProvider::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }
        val fieldTypes = MarketPriceSnapshotProvider::class.java.declaredFields.map { it.type }
        assertFalse(constructorTypes.any { it == MarketDataRepository::class.java })
        assertFalse(fieldTypes.any { it == MarketDataRepository::class.java })
    }

    @Test
    fun `synthetic FAKEPACA test-feed quote is rejected even when fresh`() = runBlocking {
        val reference = newProvider(
            bufferWith(tick(provenance = MarketDataProvenance.ALPACA_TEST_SYNTHETIC)),
        ).executionReferenceFor("SPY")
        val rejected = reference as ExecutionReferencePrice.Rejected
        assertEquals(ExecutionPriceRejection.PROVENANCE_NOT_REAL_TIME, rejected.reason)
        assertEquals(MarketDataProvenance.ALPACA_TEST_SYNTHETIC, rejected.provenance)
    }

    @Test
    fun `demo in-memory quote is execution ineligible`() = runBlocking {
        val reference = newProvider(
            bufferWith(tick(provenance = MarketDataProvenance.LOCAL_DEMO_SYNTHETIC)),
        ).executionReferenceFor("SPY")
        assertEquals(
            ExecutionPriceRejection.PROVENANCE_NOT_REAL_TIME,
            (reference as ExecutionReferencePrice.Rejected).reason,
        )
    }

    @Test
    fun `quote with undeclared provenance is execution ineligible`() = runBlocking {
        val undeclared = MarketTick(
            symbol = "SPY",
            bidPrice = 520.10,
            askPrice = 520.20,
            marketTimestampMillis = nowMs - 500L,
            receivedAtMillis = nowMs - 500L,
            source = "alpaca-iex-stream",
        )
        val reference = newProvider(bufferWith(undeclared)).executionReferenceFor("SPY")
        assertEquals(
            ExecutionPriceRejection.PROVENANCE_NOT_REAL_TIME,
            (reference as ExecutionReferencePrice.Rejected).reason,
        )
    }

    @Test
    fun `stale IEX quote is rejected as STALE and no other source rescues it`() = runBlocking {
        val reference = newProvider(
            bufferWith(tick(eventAgeMillis = 60_000L)),
        ).executionReferenceFor("SPY")
        assertEquals(
            ExecutionPriceRejection.STALE,
            (reference as ExecutionReferencePrice.Rejected).reason,
        )
    }

    @Test
    fun `trusted quote that disappears is rejected, never replaced by an older value`() = runBlocking {
        val buffer = bufferWith(tick())
        val provider = newProvider(buffer)
        assertTrue(provider.executionReferenceFor("SPY") is ExecutionReferencePrice.Trusted)
        // The tick buffer is cleared, as it would be on a stream reset.
        buffer.clear()
        assertEquals(
            ExecutionPriceRejection.NO_LIVE_QUOTE,
            (provider.executionReferenceFor("SPY") as ExecutionReferencePrice.Rejected).reason,
        )
    }

    @Test
    fun `latest quote decides - a non-trusted newest quote blocks even if an older IEX quote exists`() = runBlocking {
        val buffer = MarketTickBuffer()
        buffer.pushQuote(tick(bid = 500.0, ask = 500.0, eventAgeMillis = 400L))
        buffer.pushQuote(
            tick(
                bid = 500.0,
                ask = 500.0,
                eventAgeMillis = 100L,
                provenance = MarketDataProvenance.ALPACA_TEST_SYNTHETIC,
            ),
        )
        assertEquals(
            ExecutionPriceRejection.PROVENANCE_NOT_REAL_TIME,
            (newProvider(buffer).executionReferenceFor("SPY") as ExecutionReferencePrice.Rejected).reason,
        )
    }

    @Test
    fun `bar-only symbol with no received quote is NO_LIVE_QUOTE`() = runBlocking {
        val buffer = MarketTickBuffer()
        buffer.recordBar("SPY")
        assertEquals(
            ExecutionPriceRejection.NO_LIVE_QUOTE,
            (newProvider(buffer).executionReferenceFor("SPY") as ExecutionReferencePrice.Rejected).reason,
        )
    }

    @Test
    fun `invalid quotes never produce a zero, NaN or crossed price`() = runBlocking {
        val cases = listOf(
            tick(bid = 0.0, ask = 0.0),
            tick(bid = Double.NaN, ask = Double.NaN),
            tick(bid = 521.0, ask = 520.0),
        )
        for (case in cases) {
            val reference = newProvider(bufferWith(case)).executionReferenceFor("SPY")
            assertEquals(
                ExecutionPriceRejection.INVALID_QUOTE,
                (reference as ExecutionReferencePrice.Rejected).reason,
                "quote bid=${case.bidPrice} ask=${case.askPrice}",
            )
        }
    }

    @Test
    fun `empty symbol and unknown symbol return NO_LIVE_QUOTE without crashing`() = runBlocking {
        val provider = newProvider(bufferWith(tick()))
        assertEquals(
            ExecutionPriceRejection.NO_LIVE_QUOTE,
            (provider.executionReferenceFor("") as ExecutionReferencePrice.Rejected).reason,
        )
        assertEquals(
            ExecutionPriceRejection.NO_LIVE_QUOTE,
            (provider.executionReferenceFor("QQQ") as ExecutionReferencePrice.Rejected).reason,
        )
    }

    @Test
    fun `symbol is normalized before lookup`() = runBlocking {
        val reference = newProvider(bufferWith(tick())).executionReferenceFor("  spy ")
        val trusted = reference as ExecutionReferencePrice.Trusted
        assertEquals("SPY", trusted.symbol)
        assertNotNull(trusted.price)
    }

    @Test
    fun `provider has no execution-shape method`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "trading", "executeorder",
            "cancelorder", "openposition", "closeposition",
            "post", "patch", "delete",
        )
        val methods = MarketPriceSnapshotProvider::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        for (name in methods) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertFalse(
                    lower.contains(bad),
                    "provider method '$name' contains forbidden substring '$bad'",
                )
            }
        }
    }
}
