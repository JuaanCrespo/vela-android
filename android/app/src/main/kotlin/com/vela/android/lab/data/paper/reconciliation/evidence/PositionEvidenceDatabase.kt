package com.vela.android.lab.data.paper.reconciliation.evidence

import androidx.room.withTransaction
import com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory
import com.vela.android.lab.data.paper.history.PaperOrderHistoryRepository
import com.vela.android.lab.db.room.VelaDatabase
import com.vela.android.lab.db.room.dao.PaperPositionEvidenceDao

/** Shared transaction boundary for capture, anchor events, reports and a coherent FULL history read. */
interface PositionEvidenceDatabase {
    val evidence: PaperPositionEvidenceDao
    suspend fun <T> transaction(block: suspend () -> T): T
    suspend fun fullCanonicalHistory(): List<CanonicalPaperOrderHistory>
}

class RoomPositionEvidenceDatabase(private val database: VelaDatabase) : PositionEvidenceDatabase {
    override val evidence = database.paperPositionEvidenceDao()
    private val history = PaperOrderHistoryRepository(database.paperOrderHistoryDao())
    override suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction { block() }
    override suspend fun fullCanonicalHistory(): List<CanonicalPaperOrderHistory> =
        database.withTransaction { history.getAll() }
}
