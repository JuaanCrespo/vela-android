package com.vela.android.lab.data.watchlist

/**
 * Phase 2.g read-only watchlist repository. Wraps a [WatchlistStore]
 * with seeding, normalization, validation, deduplication, and the
 * [WatchlistConfig.MAX_SYMBOLS] cap.
 *
 * No method opens a network, mutates account state, or performs any
 * trading action.
 */
class WatchlistRepository(
    private val store: WatchlistStore,
    private val defaults: List<String> = WatchlistConfig.DEFAULT_SYMBOLS,
) {

    sealed interface MutationResult {
        val message: String
        data class Added(val symbol: String) : MutationResult {
            override val message: String = "Added $symbol"
        }
        data class Removed(val symbol: String) : MutationResult {
            override val message: String = "Removed $symbol"
        }
        data class AlreadyPresent(val symbol: String) : MutationResult {
            override val message: String = "$symbol already on the watchlist"
        }
        data class NotPresent(val symbol: String) : MutationResult {
            override val message: String = "$symbol is not on the watchlist"
        }
        data class Invalid(val raw: String) : MutationResult {
            override val message: String = "'$raw' is not a valid stock symbol"
        }
        object AtCap : MutationResult {
            override val message: String =
                "Watchlist already at the cap of ${WatchlistConfig.MAX_SYMBOLS} symbols"
        }
    }

    /**
     * Read the current watchlist. If the store is empty (first run,
     * cleared by the user), the [defaults] list is written back and
     * returned. The returned list is sorted in alphabetical order so
     * the UI is deterministic across launches.
     */
    suspend fun load(): List<String> {
        val raw = store.load()
        if (raw.isEmpty()) {
            val seed = defaults
                .mapNotNull { WatchlistConfig.normalize(it) }
                .distinct()
                .take(WatchlistConfig.MAX_SYMBOLS)
            store.save(seed.toSet())
            return seed.sorted()
        }
        return raw
            .mapNotNull { WatchlistConfig.normalize(it) }
            .distinct()
            .sorted()
    }

    suspend fun add(input: String): MutationResult {
        val normalized = WatchlistConfig.normalize(input)
            ?: return MutationResult.Invalid(input)
        val current = load().toMutableList()
        if (normalized in current) return MutationResult.AlreadyPresent(normalized)
        if (current.size >= WatchlistConfig.MAX_SYMBOLS) return MutationResult.AtCap
        current += normalized
        store.save(current.toSet())
        return MutationResult.Added(normalized)
    }

    suspend fun remove(input: String): MutationResult {
        val normalized = WatchlistConfig.normalize(input)
            ?: return MutationResult.Invalid(input)
        val current = load().toMutableList()
        if (normalized !in current) return MutationResult.NotPresent(normalized)
        current -= normalized
        store.save(current.toSet())
        return MutationResult.Removed(normalized)
    }

    /** Reset to [defaults]. Used by tests and a future "reset" UI control. */
    suspend fun resetToDefaults(): List<String> {
        store.save(emptySet())
        return load()
    }
}
