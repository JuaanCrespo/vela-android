package com.vela.android.lab.data.market.source

import com.vela.android.lab.data.market.BootstrapMarketUpdate
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only boundary for market data. **No method on this interface
 * submits orders, mutates account state, or performs any trading
 * action — by design.**
 *
 * The `Phase 2.a` boundary is intentionally narrow: it owns
 * connection lifecycle, subscriptions, and a stream of
 * [BootstrapMarketUpdate] values. Anything beyond that (orders,
 * positions, balances) does not belong here and must not be added
 * in subsequent phases.
 *
 * Implementations:
 *  - [StubPaperMarketDataClient] — deterministic, local-only, no
 *    network. The only implementation that exists in Phase 2.a.
 *  - A future `AlpacaPaperMarketDataClient` (Phase 2.b) will live in
 *    a separate file and is gated by [MarketDataConfig] guards.
 */
interface MarketDataClient {

    /** Which source this client represents. */
    val source: MarketDataSource

    /** Hot, observable connection state. */
    val connectionStatus: StateFlow<MarketDataConnectionStatus>

    /**
     * Hot stream of market updates. Subscribers see only updates
     * emitted *after* they start collecting. Old data is not
     * replayed — this is a streaming boundary, not a state holder.
     */
    val updates: SharedFlow<BootstrapMarketUpdate>

    /**
     * Move the client into [MarketDataConnectionStatus.State.CONNECTED].
     * For [StubPaperMarketDataClient] this is purely a state flip;
     * no network I/O occurs.
     */
    suspend fun connect()

    /** Move the client back to DISCONNECTED. */
    suspend fun disconnect()

    /** Subscribe to a set of normalized symbols. */
    suspend fun subscribe(symbols: Set<String>)

    /** Unsubscribe from a set of normalized symbols. */
    suspend fun unsubscribe(symbols: Set<String>)

    /** Current set of subscribed (normalized) symbols. */
    fun subscribedSymbols(): Set<String>
}
