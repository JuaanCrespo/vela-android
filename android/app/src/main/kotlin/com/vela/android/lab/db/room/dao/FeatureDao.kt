package com.vela.android.lab.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vela.android.lab.db.room.entities.SymbolFeaturesEntity

@Dao
interface FeatureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(features: SymbolFeaturesEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(features: List<SymbolFeaturesEntity>): List<Long>

    @Query(
        "SELECT * FROM symbol_features WHERE symbol = :symbol " +
            "ORDER BY bucketStartEpochMillis ASC"
    )
    suspend fun bySymbol(symbol: String): List<SymbolFeaturesEntity>

    @Query(
        "SELECT * FROM symbol_features WHERE symbol = :symbol " +
            "ORDER BY bucketStartEpochMillis DESC LIMIT :limit"
    )
    suspend fun recent(symbol: String, limit: Int): List<SymbolFeaturesEntity>

    @Query(
        "SELECT * FROM symbol_features WHERE symbol = :symbol " +
            "ORDER BY bucketStartEpochMillis DESC LIMIT 1"
    )
    suspend fun latestFor(symbol: String): SymbolFeaturesEntity?

    @Query("SELECT COUNT(*) FROM symbol_features WHERE symbol = :symbol")
    suspend fun countBySymbol(symbol: String): Int

    @Query("DELETE FROM symbol_features")
    suspend fun clear()
}
