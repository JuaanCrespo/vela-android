package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.reconciliation.domain.*
import com.vela.android.lab.db.room.entities.*

data class StoredPositionAnchor(val metadata: PaperPositionAnchorEntity, val cursors: List<PaperPositionAnchorCursorEntity>) {
    fun domain(): PositionAnchor = PositionAnchor(metadata.anchorId, metadata.symbol, metadata.accountRef,
        QuantityEvidence(requireCanonicalDecimal(metadata.baselineQty), DecimalProvenance.valueOf(metadata.baselineProvenance)),
        AnchorCoverageCut(metadata.orderSequenceInclusive, metadata.lifecycleSequenceInclusive, CutAssurance.valueOf(metadata.cutAssurance)),
        cursors.map { AnchorOrderCursor(PositionOrderIdentity(it.attemptId, it.orderId, it.clientOrderId, it.orderSequenceId, it.symbol, it.side),
            QuantityEvidence(requireCanonicalDecimal(it.includedFilledQty), DecimalProvenance.valueOf(it.provenance)), it.observationSequence, it.payloadFingerprint) },
        AnchorStatus.valueOf(metadata.status), metadata.createdAtEpochMillis, metadata.invalidatedAtEpochMillis,
        metadata.invalidationReason?.let(AnchorInvalidationReason::valueOf))
}

class PaperPositionAnchorRepository(private val database: PositionEvidenceDatabase) {
    private val snapshots = PaperBrokerPositionSnapshotRepository(database)
    private val history = ConsistentPositionHistoryReader(database)

    suspend fun createAnchor(anchor: PositionAnchor, brokerSnapshotId: String, scopeAssurance: CutAssurance): StoredPositionAnchor = database.transaction {
        require(database.evidence.activeAnchor(anchor.symbol, anchor.accountRef) == null) { "Active anchor conflict" }
        create(anchor, brokerSnapshotId, scopeAssurance)
    }

    private suspend fun create(anchor: PositionAnchor, brokerSnapshotId: String, scopeAssurance: CutAssurance): StoredPositionAnchor {
        val snapshot = requireNotNull(snapshots.get(brokerSnapshotId))
        require(snapshot.anchorEligible && snapshot.metadata.accountRef == anchor.accountRef && validEvidenceSymbol(anchor.symbol))
        require(snapshots.historyCheckpoint().digest == snapshot.metadata.localHistoryDigest) { "Local evidence changed since capture" }
        require(anchor.cut.orderSequenceInclusive == snapshot.metadata.orderSequenceInclusive &&
            anchor.cut.lifecycleSequenceInclusive == snapshot.metadata.lifecycleSequenceInclusive) { "Anchor cut does not match capture evidence" }
        require(anchor.status == AnchorStatus.ACTIVE && anchor.baselineQty.exact && anchor.createdAtEpochMillis >= snapshot.metadata.completedAtEpochMillis)
        val baseline = snapshot.positions.singleOrNull { it.symbol == anchor.symbol }?.qtyCanonicalDecimal ?: "0"
        require(anchor.baselineQty.quantity == requireCanonicalDecimal(baseline))
        val input = history.read(anchor.accountRef, scopeAssurance)
        require(LocalPositionExpectationEngine().evaluate(input, listOf(anchor)).positions.single { it.symbol == anchor.symbol }.coverage == PositionCoverage.ANCHORED) {
            "Anchor evidence or coverage is not sufficient"
        }
        val entity = PaperPositionAnchorEntity(anchor.anchorId, anchor.symbol, anchor.accountRef, brokerSnapshotId,
            baseline, anchor.baselineQty.provenance.name, anchor.cut.orderSequenceInclusive, anchor.cut.lifecycleSequenceInclusive,
            anchor.cut.assurance.name, AnchorStatus.ACTIVE.name, "${anchor.accountRef}|${anchor.symbol}", anchor.createdAtEpochMillis, null, null, 1)
        database.evidence.insertPositionAnchor(entity)
        val cursors = anchor.cursors.map { cursor ->
            PaperPositionAnchorCursorEntity(anchor.anchorId, cursor.identity.attemptId, cursor.identity.orderId,
                cursor.identity.clientOrderId, cursor.identity.orderSequenceId, cursor.identity.symbol, cursor.identity.side,
                requireNotNull(cursor.includedFilledQty.quantity).toString(), cursor.includedFilledQty.provenance.name,
                cursor.observationSequence, cursor.payloadFingerprint)
        }
        cursors.forEach { database.evidence.insertPositionAnchorCursor(it) }
        database.evidence.insertPositionAnchorEvent(PaperPositionAnchorEventEntity("${anchor.anchorId}:1", anchor.anchorId, 1,
            "CREATED", anchor.createdAtEpochMillis, null, brokerSnapshotId))
        return StoredPositionAnchor(entity, cursors)
    }

    suspend fun invalidateAnchor(anchorId: String, at: Long, reason: AnchorInvalidationReason): StoredPositionAnchor = database.transaction {
        transition(anchorId, at, reason, AnchorStatus.INVALIDATED)
    }

    suspend fun supersedeAnchor(anchorId: String, replacement: PositionAnchor, brokerSnapshotId: String,
        scopeAssurance: CutAssurance): StoredPositionAnchor = database.transaction {
        val previous = requireNotNull(get(anchorId))
        require(previous.metadata.accountRef == replacement.accountRef && previous.metadata.symbol == replacement.symbol && anchorId != replacement.anchorId)
        transition(anchorId, replacement.createdAtEpochMillis, AnchorInvalidationReason.MANUAL, AnchorStatus.SUPERSEDED)
        create(replacement, brokerSnapshotId, scopeAssurance)
    }

    private suspend fun transition(anchorId: String, at: Long, reason: AnchorInvalidationReason, status: AnchorStatus): StoredPositionAnchor {
        val old = requireNotNull(get(anchorId))
        require(old.metadata.status == AnchorStatus.ACTIVE.name && at >= old.metadata.createdAtEpochMillis)
        require(database.evidence.changeAnchorStatus(anchorId, old.metadata.version, status.name, at, reason.name) == 1)
        val version = old.metadata.version + 1
        database.evidence.insertPositionAnchorEvent(PaperPositionAnchorEventEntity("$anchorId:$version", anchorId, version,
            status.name, at, reason.name, old.metadata.brokerSnapshotId))
        return requireNotNull(get(anchorId))
    }

    suspend fun get(anchorId: String): StoredPositionAnchor? = database.transaction {
        database.evidence.anchor(anchorId)?.let { StoredPositionAnchor(it, database.evidence.cursors(anchorId)) }
    }
    suspend fun getActiveAnchor(symbol: String, accountRef: String): StoredPositionAnchor? = database.transaction {
        database.evidence.activeAnchor(symbol, accountRef)?.let { StoredPositionAnchor(it, database.evidence.cursors(it.anchorId)) }
    }
    suspend fun getAnchorHistory(symbol: String, accountRef: String): List<StoredPositionAnchor> = database.transaction {
        database.evidence.anchorHistory(symbol, accountRef).map { StoredPositionAnchor(it, database.evidence.cursors(it.anchorId)) }
    }
    suspend fun events(anchorId: String): List<PaperPositionAnchorEventEntity> = database.evidence.anchorEvents(anchorId)
}
