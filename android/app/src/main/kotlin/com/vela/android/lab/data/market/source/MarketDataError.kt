package com.vela.android.lab.data.market.source

/**
 * Typed error categories the boundary may surface to callers.
 * Pure-data, no Android imports. No method on this hierarchy
 * accepts trading inputs or returns trading capabilities.
 */
sealed interface MarketDataError {
    val message: String

    data class NetworkUnavailable(
        override val message: String = "Network not available",
    ) : MarketDataError

    data class AuthenticationFailed(
        override val message: String,
    ) : MarketDataError

    data class SubscriptionRejected(
        override val message: String,
        val symbol: String,
    ) : MarketDataError

    data class StreamLost(
        override val message: String,
    ) : MarketDataError

    data class Unknown(
        override val message: String,
    ) : MarketDataError
}
