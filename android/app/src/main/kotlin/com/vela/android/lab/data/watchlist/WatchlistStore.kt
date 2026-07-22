package com.vela.android.lab.data.watchlist

import android.content.Context
import android.content.SharedPreferences

/**
 * Phase 2.g read-only watchlist storage boundary.
 *
 * Implementations persist a small `Set<String>` of stock symbols
 * locally. The set is **not** a secret; symbols are plain text and
 * carry no credential value. The store never reaches the network and
 * never reads `vela.db`.
 */
interface WatchlistStore {
    suspend fun load(): Set<String>
    suspend fun save(symbols: Set<String>)
}

/**
 * Android-side implementation backed by app-private
 * [SharedPreferences]. Symbol values are written / read as a
 * `StringSet`. No encryption is applied because the watchlist
 * does **not** contain any credential or other secret.
 */
class SharedPrefsWatchlistStore(
    context: Context,
) : WatchlistStore {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override suspend fun load(): Set<String> =
        prefs.getStringSet(KEY_SYMBOLS, null) ?: emptySet()

    override suspend fun save(symbols: Set<String>) {
        prefs.edit().putStringSet(KEY_SYMBOLS, symbols).apply()
    }

    companion object {
        private const val PREFS_NAME: String = "vela-watchlist"
        private const val KEY_SYMBOLS: String = "symbols"
    }
}

/**
 * In-memory implementation for tests. Not thread-safe; tests run on a
 * single coroutine dispatcher.
 */
class InMemoryWatchlistStore(
    initial: Set<String> = emptySet(),
) : WatchlistStore {
    private var current: Set<String> = initial
    override suspend fun load(): Set<String> = current
    override suspend fun save(symbols: Set<String>) { current = symbols }
}
