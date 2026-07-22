package com.vela.android.lab.data.market.source

import com.vela.android.lab.core.normalizeMarketSymbol
import com.vela.android.lab.data.market.BootstrapMarketUpdate
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic, in-process [MarketDataClient]. **No network. No
 * Alpaca. No credentials. No order submission.**
 *
 * Use cases:
 *  - the eventual Phase 2.b wiring will swap this for a real Alpaca
 *    Paper client; tests will continue to drive this stub directly;
 *  - unit tests and the offline demo dashboard can produce
 *    repeatable updates without ever touching the network.
 *
 * Pricing is deterministic by symbol: each call to [emitDemoUpdate]
 * increments the internal sequence and price by a fixed tick. The
 * BTC tick (5.0) and SPY tick (0.25) match the values used by the
 * Phase 1.e UI's demo buttons, so tests and the dashboard agree.
 */
class StubPaperMarketDataClient(
    initialBtcPrice: Double = 50_000.0,
    initialSpyPrice: Double = 400.0,
    private val btcTick: Double = 5.0,
    private val spyTick: Double = 0.25,
    private val clock: () -> Instant = { Instant.now() },
) : MarketDataClient {

    override val source: MarketDataSource = MarketDataSource.OFFLINE_STUB

    private val _connectionStatus: MutableStateFlow<MarketDataConnectionStatus> =
        MutableStateFlow(MarketDataConnectionStatus.disconnected(source, clock()))

    override val connectionStatus: StateFlow<MarketDataConnectionStatus> =
        _connectionStatus.asStateFlow()

    private val _updates: MutableSharedFlow<BootstrapMarketUpdate> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 16)

    override val updates: SharedFlow<BootstrapMarketUpdate> = _updates.asSharedFlow()

    private val subscribed: MutableSet<String> = linkedSetOf()
    private var sequenceCounter: Int = 0
    private var btcPrice: Double = initialBtcPrice
    private var spyPrice: Double = initialSpyPrice

    override suspend fun connect() {
        _connectionStatus.value = MarketDataConnectionStatus.connected(source, clock())
    }

    override suspend fun disconnect() {
        _connectionStatus.value = MarketDataConnectionStatus.disconnected(source, clock())
    }

    override suspend fun subscribe(symbols: Set<String>) {
        for (raw in symbols) {
            val normalized = normalizeMarketSymbol(raw)
            if (normalized.isNotEmpty()) subscribed += normalized
        }
    }

    override suspend fun unsubscribe(symbols: Set<String>) {
        for (raw in symbols) {
            val normalized = normalizeMarketSymbol(raw)
            if (normalized.isNotEmpty()) subscribed -= normalized
        }
    }

    override fun subscribedSymbols(): Set<String> = subscribed.toSet()

    /**
     * Test/demo helper: deterministically produce one update for the
     * supplied symbol and emit it on [updates]. Returns the update
     * that was emitted so callers can assert on it directly.
     *
     * - BTC/USD ticks the BTC price by +`btcTick` per call.
     * - SPY (or any non-BTC/USD symbol) ticks the SPY price by `spyTick`.
     */
    suspend fun emitDemoUpdate(symbol: String): BootstrapMarketUpdate {
        sequenceCounter += 1
        val normalized = normalizeMarketSymbol(symbol)
        val (price, change) = if (normalized == "BTC/USD") {
            btcPrice += btcTick
            btcPrice to btcTick
        } else {
            spyPrice += spyTick
            spyPrice to spyTick
        }
        val update = BootstrapMarketUpdate(
            symbol = normalized,
            sequence = sequenceCounter,
            price = price,
            change = change,
            timestamp = clock(),
            source = "offline-stub",
        )
        _updates.emit(update)
        return update
    }
}
