package com.vela.android.lab.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vela.android.lab.db.room.entities.SymbolSignalEntity

@Dao
interface SignalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(signal: SymbolSignalEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(signals: List<SymbolSignalEntity>): List<Long>

    @Query(
        "SELECT * FROM symbol_signals WHERE symbol = :symbol " +
            "ORDER BY bucketStartEpochMillis ASC"
    )
    suspend fun bySymbol(symbol: String): List<SymbolSignalEntity>

    @Query(
        "SELECT * FROM symbol_signals WHERE symbol = :symbol " +
            "ORDER BY bucketStartEpochMillis DESC LIMIT :limit"
    )
    suspend fun recent(symbol: String, limit: Int): List<SymbolSignalEntity>

    @Query(
        "SELECT * FROM symbol_signals WHERE symbol = :symbol " +
            "ORDER BY bucketStartEpochMillis DESC LIMIT 1"
    )
    suspend fun latestFor(symbol: String): SymbolSignalEntity?

    @Query(
        "SELECT * FROM symbol_signals WHERE state = :state " +
            "ORDER BY bucketStartEpochMillis DESC LIMIT :limit"
    )
    suspend fun byState(state: String, limit: Int): List<SymbolSignalEntity>

    @Query("DELETE FROM symbol_signals")
    suspend fun clear()
}
