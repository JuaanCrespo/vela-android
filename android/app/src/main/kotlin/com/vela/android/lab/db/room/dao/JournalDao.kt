package com.vela.android.lab.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vela.android.lab.db.room.entities.JournalEventEntity

@Dao
interface JournalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: JournalEventEntity): Long

    @Query(
        "SELECT * FROM journal_events WHERE symbol = :symbol " +
            "ORDER BY timestampEpochMillis ASC"
    )
    suspend fun bySymbol(symbol: String): List<JournalEventEntity>

    @Query(
        "SELECT * FROM journal_events WHERE eventType = :eventType " +
            "ORDER BY timestampEpochMillis DESC LIMIT :limit"
    )
    suspend fun byType(eventType: String, limit: Int): List<JournalEventEntity>

    @Query(
        "SELECT * FROM journal_events " +
            "WHERE timestampEpochMillis BETWEEN :startMillis AND :endMillis " +
            "ORDER BY timestampEpochMillis ASC"
    )
    suspend fun inRange(startMillis: Long, endMillis: Long): List<JournalEventEntity>

    @Query("SELECT COUNT(*) FROM journal_events")
    suspend fun countAll(): Int

    @Query("DELETE FROM journal_events")
    suspend fun clear()
}
