package com.vela.android.lab.data.market.source

import java.time.Instant

/**
 * Snapshot of a [MarketDataClient]'s connection state. Designed to
 * be safe to read from any thread; immutable.
 */
data class MarketDataConnectionStatus(
    val source: MarketDataSource,
    val state: State,
    val lastError: MarketDataError? = null,
    val updatedAt: Instant,
) {
    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR,
    }

    val isConnected: Boolean get() = state == State.CONNECTED

    companion object {
        fun disconnected(
            source: MarketDataSource,
            now: Instant = Instant.now(),
        ): MarketDataConnectionStatus = MarketDataConnectionStatus(
            source = source,
            state = State.DISCONNECTED,
            lastError = null,
            updatedAt = now,
        )

        fun connecting(
            source: MarketDataSource,
            now: Instant = Instant.now(),
        ): MarketDataConnectionStatus = MarketDataConnectionStatus(
            source = source,
            state = State.CONNECTING,
            lastError = null,
            updatedAt = now,
        )

        fun connected(
            source: MarketDataSource,
            now: Instant = Instant.now(),
        ): MarketDataConnectionStatus = MarketDataConnectionStatus(
            source = source,
            state = State.CONNECTED,
            lastError = null,
            updatedAt = now,
        )

        fun error(
            source: MarketDataSource,
            error: MarketDataError,
            now: Instant = Instant.now(),
        ): MarketDataConnectionStatus = MarketDataConnectionStatus(
            source = source,
            state = State.ERROR,
            lastError = error,
            updatedAt = now,
        )
    }
}
