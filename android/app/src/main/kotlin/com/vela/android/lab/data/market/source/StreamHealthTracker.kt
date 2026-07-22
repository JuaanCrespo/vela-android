package com.vela.android.lab.data.market.source

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Mutable Phase 2.f health tracker used by both Alpaca read-only
 * Market Data clients. Owns a [MutableStateFlow] of [StreamHealth];
 * the client exposes a read-only [StateFlow] view.
 *
 * Thread-safe for concurrent transitions thanks to
 * [MutableStateFlow.update] and the [AtomicInteger] counters.
 *
 * **No method on this class submits orders, mutates account state,
 * or performs any trading action.** It only records lifecycle
 * timestamps and a small counter for diagnostics.
 */
class StreamHealthTracker(
    endpoint: String,
    feedLabel: String,
    private val clock: () -> Instant = { Instant.now() },
) {
    private val _health: MutableStateFlow<StreamHealth> =
        MutableStateFlow(StreamHealth.initial(endpoint, feedLabel))

    val health: StateFlow<StreamHealth> = _health.asStateFlow()

    private val connectAttempts: AtomicInteger = AtomicInteger(0)

    private fun nowMillis(): Long = clock().toEpochMilli()

    /**
     * Called every time `connect()` is invoked AND will actually do
     * work (i.e. it has not early-returned because the client is
     * already CONNECTING / AUTHENTICATED / SUBSCRIBED). The first
     * call leaves `reconnectAttempts` at 0; every subsequent call
     * increments it. Phase transitions to [StreamHealth.Phase.CONNECTING].
     */
    fun onConnectRequested() {
        val attempts = connectAttempts.incrementAndGet()
        val reconnects = (attempts - 1).coerceAtLeast(0)
        _health.update {
            it.copy(
                phase = StreamHealth.Phase.CONNECTING,
                reconnectAttempts = reconnects,
                // Clear last error on a fresh attempt so the UI
                // reflects "trying again", not "still showing prior
                // failure".
                lastErrorType = null,
                lastErrorMessage = null,
            )
        }
    }

    fun onAuthenticated() {
        val now = nowMillis()
        _health.update {
            it.copy(
                phase = StreamHealth.Phase.AUTHENTICATED,
                lastConnectedAtEpochMillis = now,
                lastErrorType = null,
                lastErrorMessage = null,
            )
        }
    }

    fun onSubscribed(symbols: Set<String>) {
        _health.update {
            it.copy(
                phase = StreamHealth.Phase.SUBSCRIBED,
                subscribed = symbols,
            )
        }
    }

    /**
     * Any inbound parsed frame ticks the message clock so the UI
     * can show "Last message Xs ago". This is a heartbeat indicator,
     * not a bar count.
     */
    fun onMessage() {
        val now = nowMillis()
        _health.update { it.copy(lastMessageAtEpochMillis = now) }
    }

    fun onDisconnected() {
        val now = nowMillis()
        _health.update {
            it.copy(
                phase = StreamHealth.Phase.DISCONNECTED,
                lastDisconnectedAtEpochMillis = now,
                subscribed = emptySet(),
            )
        }
    }

    /**
     * Phase 2.h: user-initiated stop. Clears the active-error
     * surface so the UI no longer shows a stale `StreamLost`-style
     * indicator after the user explicitly closed the stream. Differs
     * from [onDisconnected] only in that it also wipes
     * `lastErrorType` / `lastErrorMessage`.
     *
     * The aggregate `reconnectAttempts` counter is **not** touched —
     * an intentional stop is not a reconnect.
     */
    fun onUserStop() {
        val now = nowMillis()
        _health.update {
            it.copy(
                phase = StreamHealth.Phase.DISCONNECTED,
                lastDisconnectedAtEpochMillis = now,
                subscribed = emptySet(),
                lastErrorType = null,
                lastErrorMessage = null,
            )
        }
    }

    fun onError(type: String, message: String?) {
        _health.update {
            it.copy(
                phase = StreamHealth.Phase.ERROR,
                lastErrorType = type,
                lastErrorMessage = message,
            )
        }
    }

    /** Reset the per-process attempt counter back to zero. */
    fun resetAttempts() {
        connectAttempts.set(0)
        _health.update { it.copy(reconnectAttempts = 0) }
    }
}
