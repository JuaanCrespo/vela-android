package com.vela.android.lab.data.market.source

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamHealthTrackerTest {

    private fun fixedClockSequence(vararg millis: Long): () -> Instant {
        val it = millis.iterator()
        return { Instant.ofEpochMilli(it.next()) }
    }

    @Test
    fun `initial health is DISCONNECTED with zero counters and no timestamps`() {
        val tracker = StreamHealthTracker(
            endpoint = "wss://stream.data.alpaca.markets/v2/iex",
            feedLabel = "test",
        )
        val s = tracker.health.value
        assertEquals(StreamHealth.Phase.DISCONNECTED, s.phase)
        assertEquals(emptySet<String>(), s.subscribed)
        assertNull(s.lastConnectedAtEpochMillis)
        assertNull(s.lastDisconnectedAtEpochMillis)
        assertNull(s.lastMessageAtEpochMillis)
        assertNull(s.lastErrorType)
        assertNull(s.lastErrorMessage)
        assertEquals(0, s.reconnectAttempts)
    }

    @Test
    fun `first onConnectRequested leaves reconnectAttempts at 0 and flips to CONNECTING`() {
        val tracker = StreamHealthTracker("u", "f")
        tracker.onConnectRequested()
        val s = tracker.health.value
        assertEquals(StreamHealth.Phase.CONNECTING, s.phase)
        assertEquals(0, s.reconnectAttempts)
    }

    @Test
    fun `subsequent onConnectRequested calls increment reconnectAttempts`() {
        val tracker = StreamHealthTracker("u", "f")
        tracker.onConnectRequested()
        tracker.onConnectRequested()
        tracker.onConnectRequested()
        assertEquals(2, tracker.health.value.reconnectAttempts)
    }

    @Test
    fun `onAuthenticated records lastConnectedAt and clears prior error`() {
        val tracker = StreamHealthTracker("u", "f", fixedClockSequence(1_000L))
        tracker.onError("AuthenticationFailed", "bad creds")
        tracker.onAuthenticated()
        val s = tracker.health.value
        assertEquals(StreamHealth.Phase.AUTHENTICATED, s.phase)
        assertEquals(1_000L, s.lastConnectedAtEpochMillis)
        assertNull(s.lastErrorType)
        assertNull(s.lastErrorMessage)
    }

    @Test
    fun `onSubscribed transitions to SUBSCRIBED and stores symbol set`() {
        val tracker = StreamHealthTracker("u", "f")
        tracker.onAuthenticated()
        tracker.onSubscribed(setOf("SPY"))
        val s = tracker.health.value
        assertEquals(StreamHealth.Phase.SUBSCRIBED, s.phase)
        assertEquals(setOf("SPY"), s.subscribed)
    }

    @Test
    fun `onMessage updates lastMessageAt`() {
        val tracker = StreamHealthTracker("u", "f", fixedClockSequence(2_000L, 5_000L))
        tracker.onMessage()
        val first = tracker.health.value.lastMessageAtEpochMillis
        tracker.onMessage()
        val second = tracker.health.value.lastMessageAtEpochMillis
        assertEquals(2_000L, first)
        assertEquals(5_000L, second)
        assertNotEquals(first, second)
    }

    @Test
    fun `onDisconnected flips phase and clears subscribed set`() {
        val tracker = StreamHealthTracker("u", "f", fixedClockSequence(2_000L, 3_000L))
        tracker.onAuthenticated()
        tracker.onSubscribed(setOf("FAKEPACA"))
        tracker.onDisconnected()
        val s = tracker.health.value
        assertEquals(StreamHealth.Phase.DISCONNECTED, s.phase)
        assertEquals(emptySet<String>(), s.subscribed)
        assertEquals(3_000L, s.lastDisconnectedAtEpochMillis)
    }

    @Test
    fun `onError captures type and message and sets phase ERROR`() {
        val tracker = StreamHealthTracker("u", "f")
        tracker.onError("SubscriptionRejected", "connection limit exceeded")
        val s = tracker.health.value
        assertEquals(StreamHealth.Phase.ERROR, s.phase)
        assertEquals("SubscriptionRejected", s.lastErrorType)
        assertEquals("connection limit exceeded", s.lastErrorMessage)
    }

    @Test
    fun `onConnectRequested clears prior error so UI shows trying again`() {
        val tracker = StreamHealthTracker("u", "f")
        tracker.onError("AuthenticationFailed", "bad")
        assertEquals("AuthenticationFailed", tracker.health.value.lastErrorType)
        tracker.onConnectRequested()
        val s = tracker.health.value
        assertNull(s.lastErrorType)
        assertNull(s.lastErrorMessage)
        assertEquals(StreamHealth.Phase.CONNECTING, s.phase)
    }

    @Test
    fun `resetAttempts zeroes counter and emits update`() {
        val tracker = StreamHealthTracker("u", "f")
        tracker.onConnectRequested()
        tracker.onConnectRequested()
        tracker.onConnectRequested()
        assertEquals(2, tracker.health.value.reconnectAttempts)
        tracker.resetAttempts()
        assertEquals(0, tracker.health.value.reconnectAttempts)

        // Next onConnectRequested after reset starts again at 0.
        tracker.onConnectRequested()
        assertEquals(0, tracker.health.value.reconnectAttempts)
    }

    @Test
    fun `no method on StreamHealthTracker has a trading-shaped name`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "trading", "executeorder", "cancelorder",
            "getaccount", "openposition", "closeposition", "getportfolio",
        )
        val methodNames = StreamHealthTracker::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        for (name in methodNames) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertTrue(
                    !lower.contains(bad),
                    "Tracker method '$name' contains forbidden substring '$bad'",
                )
            }
        }
    }
}
