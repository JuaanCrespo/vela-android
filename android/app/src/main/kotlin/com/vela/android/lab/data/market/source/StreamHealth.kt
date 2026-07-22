package com.vela.android.lab.data.market.source

/**
 * Phase 2.f read-only diagnostic snapshot of a Market Data WebSocket
 * client. Sits **alongside** [MarketDataConnectionStatus] — the
 * interface contract is unchanged. Pure data; no Android imports.
 *
 * The phase model is richer than the connection-status enum:
 *
 *  - `DISCONNECTED` — at boot, after `disconnect()`, or after the
 *    server closed the socket cleanly.
 *  - `CONNECTING` — `connect()` is in flight; socket may be opening.
 *  - `AUTHENTICATED` — the server's `success: authenticated` frame
 *    arrived; the client has sent the subscribe message.
 *  - `SUBSCRIBED` — the server's `subscription` frame arrived
 *    confirming the bar/quote subscription for at least one symbol.
 *  - `ERROR` — auth failure, subscription rejection, or transport
 *    failure. `lastErrorType` / `lastErrorMessage` are populated.
 *
 * Reconnect counter semantics: `reconnectAttempts` is the number of
 * times `connect()` has been called **after the first** for this
 * client. It increments on user-initiated retries; it does **not**
 * advance automatically — Phase 2.f does not implement background
 * retry. A `connect()` that early-returns because the client is
 * already CONNECTING/AUTHENTICATED/SUBSCRIBED does not increment.
 */
data class StreamHealth(
    val endpoint: String,
    val feedLabel: String,
    val phase: Phase,
    val subscribed: Set<String>,
    val lastConnectedAtEpochMillis: Long?,
    val lastDisconnectedAtEpochMillis: Long?,
    val lastMessageAtEpochMillis: Long?,
    val lastErrorType: String?,
    val lastErrorMessage: String?,
    val reconnectAttempts: Int,
) {
    enum class Phase {
        DISCONNECTED,
        CONNECTING,
        AUTHENTICATED,
        SUBSCRIBED,
        ERROR,
    }

    companion object {
        fun initial(endpoint: String, feedLabel: String): StreamHealth = StreamHealth(
            endpoint = endpoint,
            feedLabel = feedLabel,
            phase = Phase.DISCONNECTED,
            subscribed = emptySet(),
            lastConnectedAtEpochMillis = null,
            lastDisconnectedAtEpochMillis = null,
            lastMessageAtEpochMillis = null,
            lastErrorType = null,
            lastErrorMessage = null,
            reconnectAttempts = 0,
        )
    }
}
