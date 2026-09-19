package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory
import com.vela.android.lab.db.room.dao.PaperPositionEvidenceDao
import com.vela.android.lab.db.room.entities.PaperBrokerPositionSnapshotEntity
import com.vela.android.lab.db.room.entities.PaperBrokerSnapshotEntity
import com.vela.android.lab.db.room.entities.PaperOrderDecimalEvidenceEntity
import com.vela.android.lab.db.room.entities.PaperPositionAnchorCursorEntity
import com.vela.android.lab.db.room.entities.PaperPositionAnchorEntity
import com.vela.android.lab.db.room.entities.PaperPositionAnchorEventEntity
import com.vela.android.lab.db.room.entities.PaperPositionReconciliationReportEntity
import com.vela.android.lab.db.room.entities.PaperPositionReconciliationRowEntity

/** Host-side in-memory implementation of the evidence DAO/database. No Room, no emulator. */
internal class InMemoryEvidenceDao : PaperPositionEvidenceDao {
    val brokerSnapshots = linkedMapOf<String, PaperBrokerSnapshotEntity>()
    val brokerPositions = mutableListOf<PaperBrokerPositionSnapshotEntity>()
    val anchors = linkedMapOf<String, PaperPositionAnchorEntity>()
    val anchorCursors = mutableListOf<PaperPositionAnchorCursorEntity>()
    val anchorEvents = mutableListOf<PaperPositionAnchorEventEntity>()
    val reports = linkedMapOf<String, PaperPositionReconciliationReportEntity>()
    val reportRows = mutableListOf<PaperPositionReconciliationRowEntity>()
    val decimalEvidence = mutableListOf<PaperOrderDecimalEvidenceEntity>()
    var lifecycleHighWater: Long = 0

    private fun activeKey(entity: PaperPositionAnchorEntity): String? = entity.activeKey

    override suspend fun insertBrokerSnapshot(row: PaperBrokerSnapshotEntity) {
        require(row.snapshotId !in brokerSnapshots) { "duplicate snapshotId" }
        require(brokerSnapshots.values.none { it.manualRefreshId == row.manualRefreshId }) { "duplicate manualRefreshId" }
        require(brokerSnapshots.values.none { it.sequence == row.sequence }) { "duplicate sequence" }
        brokerSnapshots[row.snapshotId] = row
    }

    override suspend fun insertBrokerPositionSnapshot(row: PaperBrokerPositionSnapshotEntity) {
        require(row.snapshotId in brokerSnapshots) { "FK broker snapshot" }
        require(brokerPositions.none { it.snapshotId == row.snapshotId && it.symbol == row.symbol }) { "PK conflict" }
        require(brokerPositions.none { it.snapshotId == row.snapshotId && it.rowIndex == row.rowIndex }) { "duplicate rowIndex" }
        brokerPositions += row
    }

    override suspend fun insertPositionAnchor(row: PaperPositionAnchorEntity) {
        require(row.anchorId !in anchors) { "duplicate anchorId" }
        require(row.brokerSnapshotId in brokerSnapshots) { "FK broker snapshot" }
        row.activeKey?.let { key ->
            require(anchors.values.none { activeKey(it) == key }) { "duplicate activeKey" }
        }
        anchors[row.anchorId] = row
    }

    override suspend fun insertPositionAnchorCursor(row: PaperPositionAnchorCursorEntity) {
        require(row.anchorId in anchors) { "FK anchor" }
        require(anchorCursors.none { it.anchorId == row.anchorId && it.attemptId == row.attemptId }) { "PK conflict" }
        anchorCursors += row
    }

    override suspend fun insertPositionAnchorEvent(row: PaperPositionAnchorEventEntity) {
        require(row.anchorId in anchors) { "FK anchor" }
        require(anchorEvents.none { it.eventId == row.eventId }) { "duplicate eventId" }
        require(anchorEvents.none { it.anchorId == row.anchorId && it.version == row.version }) { "duplicate anchor version" }
        anchorEvents += row
    }

    override suspend fun insertPositionReconciliationReport(row: PaperPositionReconciliationReportEntity) {
        require(row.reportId !in reports) { "duplicate reportId" }
        require(row.brokerSnapshotId in brokerSnapshots) { "FK broker snapshot" }
        reports[row.reportId] = row
    }

    override suspend fun insertPositionReconciliationRow(row: PaperPositionReconciliationRowEntity) {
        require(row.reportId in reports) { "FK report" }
        require(reportRows.none { it.reportId == row.reportId && it.symbol == row.symbol }) { "PK conflict" }
        row.anchorId?.let { require(it in anchors) { "FK anchor row" } }
        reportRows += row
    }

    override suspend fun insertOrderDecimalEvidence(row: PaperOrderDecimalEvidenceEntity) {
        require(decimalEvidence.none { it.observationId == row.observationId && it.field == row.field }) { "PK conflict" }
        decimalEvidence += row
    }

    override suspend fun nextSnapshotSequence(): Long = (brokerSnapshots.values.maxOfOrNull { it.sequence } ?: 0L) + 1L

    override suspend fun snapshot(snapshotId: String): PaperBrokerSnapshotEntity? = brokerSnapshots[snapshotId]

    override suspend fun latestComplete(accountRef: String?): PaperBrokerSnapshotEntity? = brokerSnapshots.values
        .filter { it.completeness == "COMPLETE" && (accountRef == null || it.accountRef == accountRef) }
        .maxByOrNull { it.sequence }

    override suspend fun snapshotHistory(): List<PaperBrokerSnapshotEntity> = brokerSnapshots.values.sortedByDescending { it.sequence }

    override suspend fun allAnchors(): List<PaperPositionAnchorEntity> = anchors.values
        .sortedWith(compareBy({ it.createdAtEpochMillis }, { it.anchorId }))

    override suspend fun latestReportId(): String? = reports.values.sortedWith(
        compareByDescending<PaperPositionReconciliationReportEntity> { brokerSnapshots.getValue(it.brokerSnapshotId).sequence }
            .thenByDescending { it.createdAtEpochMillis }.thenByDescending { it.reportId },
    ).firstOrNull()?.reportId

    override suspend fun positions(snapshotId: String): List<PaperBrokerPositionSnapshotEntity> = brokerPositions
        .filter { it.snapshotId == snapshotId }.sortedBy { it.rowIndex }

    override suspend fun anchor(anchorId: String): PaperPositionAnchorEntity? = anchors[anchorId]

    override suspend fun activeAnchor(symbol: String, accountRef: String): PaperPositionAnchorEntity? = anchors.values
        .firstOrNull { it.symbol == symbol && it.accountRef == accountRef && it.status == "ACTIVE" }

    override suspend fun anchorHistory(symbol: String, accountRef: String): List<PaperPositionAnchorEntity> = anchors.values
        .filter { it.symbol == symbol && it.accountRef == accountRef }
        .sortedWith(compareBy({ it.createdAtEpochMillis }, { it.anchorId }))

    override suspend fun cursors(anchorId: String): List<PaperPositionAnchorCursorEntity> = anchorCursors
        .filter { it.anchorId == anchorId }.sortedBy { it.attemptId }

    override suspend fun anchorEvents(anchorId: String): List<PaperPositionAnchorEventEntity> = anchorEvents
        .filter { it.anchorId == anchorId }.sortedBy { it.version }

    override suspend fun changeAnchorStatus(anchorId: String, previousVersion: Long, status: String, at: Long, reason: String): Int {
        val current = anchors[anchorId] ?: return 0
        if (current.version != previousVersion || current.status != "ACTIVE") return 0
        anchors[anchorId] = current.copy(status = status, activeKey = null, invalidatedAtEpochMillis = at,
            invalidationReason = reason, version = current.version + 1)
        return 1
    }

    override suspend fun report(reportId: String): PaperPositionReconciliationReportEntity? = reports[reportId]

    override suspend fun reportRows(reportId: String): List<PaperPositionReconciliationRowEntity> = reportRows
        .filter { it.reportId == reportId }.sortedBy { it.symbol }

    override suspend fun decimalEvidence(attemptId: String): List<PaperOrderDecimalEvidenceEntity> = decimalEvidence
        .filter { it.attemptId == attemptId }.sortedWith(compareBy({ it.observationId }, { it.field }))

    override suspend fun lifecycleHighWater(): Long = lifecycleHighWater
}

/** Reentrant transaction envelope — the production Room.withTransaction is reentrant, so this must be too.
 *  Test scope is single-threaded via runTest's TestDispatcher, so no lock is required. */
internal class InMemoryEvidenceDatabase(
    override val evidence: InMemoryEvidenceDao = InMemoryEvidenceDao(),
    var canonicalHistory: List<CanonicalPaperOrderHistory> = emptyList(),
) : PositionEvidenceDatabase {
    var transactionFailNext: Boolean = false
    override suspend fun <T> transaction(block: suspend () -> T): T {
        if (transactionFailNext) {
            transactionFailNext = false
            throw RuntimeException("Simulated persistence failure")
        }
        return block()
    }
    override suspend fun fullCanonicalHistory(): List<CanonicalPaperOrderHistory> = canonicalHistory
}

/** Explicit-set credentials provider that lets a test rotate/absent them. */
internal class MutableCredentialsProvider(initial: AlpacaCredentials? = AlpacaCredentials("KEY0", "SEC0")) : AlpacaCredentialsProvider {
    @Volatile var current: AlpacaCredentials? = initial
    override suspend fun read(): AlpacaCredentials? = current
}
