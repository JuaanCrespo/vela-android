@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.watchlist

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchlistRepositoryTest {

    @Test
    fun `load on empty store seeds defaults and persists them`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryWatchlistStore()
        val repo = WatchlistRepository(store)

        val loaded = repo.load()
        assertEquals(listOf("AAPL", "MSFT", "NVDA", "QQQ", "SPY"), loaded)
        // Defaults were persisted back so the next call does not re-seed.
        assertEquals(setOf("AAPL", "MSFT", "NVDA", "QQQ", "SPY"), store.load())
    }

    @Test
    fun `load on populated store returns sorted unique symbols`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryWatchlistStore(initial = setOf("nvda", "spy", "AAPL", "spy", " msft "))
        val repo = WatchlistRepository(store)
        val loaded = repo.load()
        assertEquals(listOf("AAPL", "MSFT", "NVDA", "SPY"), loaded)
    }

    @Test
    fun `add normalizes input and persists`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryWatchlistStore(initial = setOf("SPY"))
        val repo = WatchlistRepository(store)
        val result = repo.add("tsla")
        assertTrue(result is WatchlistRepository.MutationResult.Added)
        assertEquals("TSLA", (result as WatchlistRepository.MutationResult.Added).symbol)
        assertEquals(listOf("SPY", "TSLA"), repo.load())
    }

    @Test
    fun `add returns AlreadyPresent for duplicate`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryWatchlistStore(initial = setOf("SPY"))
        val repo = WatchlistRepository(store)
        val result = repo.add("spy")
        assertTrue(result is WatchlistRepository.MutationResult.AlreadyPresent)
    }

    @Test
    fun `add returns Invalid for crypto slash`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryWatchlistStore(initial = setOf("SPY"))
        val repo = WatchlistRepository(store)
        val result = repo.add("BTC/USD")
        assertTrue(result is WatchlistRepository.MutationResult.Invalid)
        // The store was NOT mutated by an invalid add.
        assertEquals(setOf("SPY"), store.load())
    }

    @Test
    fun `add returns AtCap when watchlist is full`() = runTest(UnconfinedTestDispatcher()) {
        val tenSymbols = setOf("AAPL", "MSFT", "NVDA", "QQQ", "SPY", "TSLA", "AMZN", "GOOG", "META", "AMD")
        val store = InMemoryWatchlistStore(initial = tenSymbols)
        val repo = WatchlistRepository(store)
        assertEquals(10, repo.load().size)
        val result = repo.add("NFLX")
        assertTrue(result is WatchlistRepository.MutationResult.AtCap)
        assertEquals(10, repo.load().size)
    }

    @Test
    fun `remove works and returns Removed`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryWatchlistStore(initial = setOf("SPY", "QQQ"))
        val repo = WatchlistRepository(store)
        val result = repo.remove("SPY")
        assertTrue(result is WatchlistRepository.MutationResult.Removed)
        assertEquals(listOf("QQQ"), repo.load())
    }

    @Test
    fun `remove returns NotPresent for unknown symbol`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryWatchlistStore(initial = setOf("SPY"))
        val repo = WatchlistRepository(store)
        val result = repo.remove("XOM")
        assertTrue(result is WatchlistRepository.MutationResult.NotPresent)
        assertEquals(listOf("SPY"), repo.load())
    }

    @Test
    fun `remove returns Invalid for crypto slash`() = runTest(UnconfinedTestDispatcher()) {
        val repo = WatchlistRepository(InMemoryWatchlistStore(initial = setOf("SPY")))
        val result = repo.remove("BTC/USD")
        assertTrue(result is WatchlistRepository.MutationResult.Invalid)
    }

    @Test
    fun `resetToDefaults reseeds and returns sorted defaults`() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryWatchlistStore(initial = setOf("XOM"))
        val repo = WatchlistRepository(store)
        val result = repo.resetToDefaults()
        assertEquals(listOf("AAPL", "MSFT", "NVDA", "QQQ", "SPY"), result)
    }

    @Test
    fun `mutation methods never contain trading shapes`() {
        val forbidden = listOf(
            "submitorder", "placeorder", "trading", "executeorder",
            "cancelorder", "openposition", "closeposition", "getaccount",
        )
        val methods = WatchlistRepository::class.java.declaredMethods.map { it.name }
        for (name in methods) {
            val lower = name.lowercase()
            for (bad in forbidden) {
                assertTrue(
                    !lower.contains(bad),
                    "WatchlistRepository method '$name' contains forbidden substring '$bad'",
                )
            }
        }
        // sanity: at least one expected method present
        assertNotNull(methods.firstOrNull { it.contains("add") })
    }
}
