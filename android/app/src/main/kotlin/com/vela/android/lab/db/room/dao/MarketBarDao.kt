package com.vela.android.lab.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vela.android.lab.db.room.entities.MarketBar1mEntity

/**
 * DAO declared as an interface so JVM unit tests can substitute a
 * pure-Kotlin fake. The Room compiler still generates an SQLite-backed
 * implementation at build time.
 */
@Dao
interface MarketBarDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bar: MarketBar1mEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long>

    @Query(
        "SELECT * FROM market_bars_1m WHERE symbol = :symbol " +
            "ORDER BY bucketStartEpochMillis ASC"
    )
    suspend fun bySymbol(symbol: String): List<MarketBar1mEntity>

    @Query(
        "SELECT * FROM market_bars_1m WHERE symbol = :symbol " +
            "ORDER BY bucketStartEpochMillis DESC LIMIT :limit"
    )
    suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity>

    @Query("SELECT COUNT(*) FROM market_bars_1m WHERE symbol = :symbol")
    suspend fun countBySymbol(symbol: String): Int

    @Query("SELECT COUNT(*) FROM market_bars_1m")
    suspend fun countAll(): Int

    /**
     * Broad delete of persisted market bars. Phase 3.a.1-D: no production caller may use it. The rows
     * are LEGACY_UNKNOWN_PROVENANCE and must be preserved. Do not call without a separate, reviewed phase.
     */
    @Query("DELETE FROM market_bars_1m WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)

    /** Broad delete of all persisted market bars. Forbidden in production (3.a.1-D). See [deleteBySymbol]. */
    @Query("DELETE FROM market_bars_1m")
    suspend fun clear()
}
