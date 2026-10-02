package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.reconciliation.domain.*
import com.vela.android.lab.db.room.entities.PaperPositionReconciliationReportEntity
import com.vela.android.lab.db.room.entities.PaperPositionReconciliationRowEntity

data class StoredPositionReport(val metadata: PaperPositionReconciliationReportEntity, val report: PositionReconciliationReport)

/** Explicit local operation only. Historical outputs and their source inputs are insert-only. */
class PaperPositionReconciliationReportRepository(private val database: PositionEvidenceDatabase) {
    private val snapshots = PaperBrokerPositionSnapshotRepository(database)
    private val anchors = PaperPositionAnchorRepository(database)
    private val history = ConsistentPositionHistoryReader(database)

    suspend fun createReport(reportId: String, brokerSnapshotId: String, anchorIds: List<String>,
        scopeAssurance: CutAssurance, freshness: SnapshotFreshness, alignment: CutAssurance, createdAt: Long): StoredPositionReport = database.transaction {
        require(reportId.isNotBlank() && createdAt >= 0 && anchorIds.distinct().size == anchorIds.size)
        val snapshot = requireNotNull(snapshots.get(brokerSnapshotId))
        val referencedAnchors = anchorIds.map { requireNotNull(anchors.get(it)).domain() }
        val input = history.read(snapshot.metadata.accountRef, scopeAssurance)
        // Freeze the detached inputs before running either engine. Later projections cannot alter replay.
        val json = PositionEvidenceCodec.encode(DurablePositionInputs(input, referencedAnchors))
        val frozen = PositionEvidenceCodec.decode(json)
        val effectiveAlignment = if (snapshot.metadata.localHistoryStable &&
            snapshots.historyCheckpoint().digest == snapshot.metadata.localHistoryDigest) alignment else CutAssurance.UNKNOWN
        val report = PositionReconciliationEngine().reconcile(LocalPositionExpectationEngine().evaluate(frozen.history, frozen.anchors),
            snapshot.domain(effectiveAlignment), freshness)
        val bootstrap = referencedAnchors.any { it.coverageMode == PositionCoverageMode.LEGACY_BOOTSTRAP_V1 }
        val metadata = PaperPositionReconciliationReportEntity(reportId, brokerSnapshotId,
            if (bootstrap) POSITION_ENGINE_V2 else POSITION_ENGINE_V1, if (bootstrap) POSITION_POLICY_BOOTSTRAP_V1 else POSITION_POLICY_V1,
            createdAt, input.accountRef, report.summary.matchedCount, report.summary.mismatchedCount,
            report.summary.unanchoredCount, report.summary.unknownCount, enumNames(report.diagnostics), json, evidenceDigest(json), freshness.name, effectiveAlignment.name)
        database.evidence.insertPositionReconciliationReport(metadata)
        report.rows.forEach { row ->
            database.evidence.insertPositionReconciliationRow(PaperPositionReconciliationRowEntity(reportId, row.symbol,
                row.brokerQty.quantity?.toString(), row.brokerQty.provenance.name, row.knownVelaDelta.quantity?.toString(), row.knownVelaDelta.provenance.name,
                row.knownDeltaComplete, row.anchorQty.quantity?.toString(), row.anchorQty.provenance.name,
                row.expectedQty.quantity?.toString(), row.expectedQty.provenance.name, row.difference?.toString(), row.state.name,
                row.presence.name, enumNames(row.diagnostics), row.anchorId, row.cause?.name, row.coverageMetadata?.let(BootstrapEvidenceCodec::encodeCoverage)))
        }
        StoredPositionReport(metadata, report)
    }

    /** Load the original result without running today's engine. */
    suspend fun get(reportId: String): StoredPositionReport? = database.transaction {
        database.evidence.report(reportId)?.let { metadata ->
            val rows = database.evidence.reportRows(reportId).map { row ->
                fun quantity(text: String?, provenance: String) = QuantityEvidence(text?.let(::requireCanonicalDecimal), DecimalProvenance.valueOf(provenance))
                PositionReconciliationRow(row.symbol, quantity(row.brokerQty, row.brokerProvenance),
                    quantity(row.knownVelaDelta, row.knownDeltaProvenance), row.knownDeltaComplete,
                    quantity(row.anchorQty, row.anchorProvenance), quantity(row.expectedQty, row.expectedProvenance),
                    row.difference?.let(::requireCanonicalDecimal), PositionReconciliationState.valueOf(row.state), PositionPresence.valueOf(row.presence),
                    readEnumNames(row.diagnosticsJson), row.anchorId, row.cause?.let(PositionDifferenceCause::valueOf),
                    row.coverageJson?.let(BootstrapEvidenceCodec::decodeCoverage))
            }
            StoredPositionReport(metadata, PositionReconciliationReport(metadata.brokerSnapshotId, rows,
                PositionReconciliationSummary(metadata.matchedCount, metadata.mismatchedCount, metadata.unanchoredCount, metadata.unknownCount),
                readEnumNames(metadata.diagnosticsJson)))
        }
    }

    /** Verification only. Never overwrites a result, and refuses incompatible versions. */
    suspend fun verifyReplay(reportId: String): Boolean = database.transaction {
        val stored = requireNotNull(get(reportId))
        require(evidenceDigest(stored.metadata.inputsJson) == stored.metadata.inputsDigest) { "Evidence digest mismatch" }
        val inputs = PositionEvidenceCodec.decode(stored.metadata.inputsJson)
        val bootstrap = inputs.anchors.any { it.coverageMode == PositionCoverageMode.LEGACY_BOOTSTRAP_V1 }
        require(stored.metadata.engineVersion == (if (bootstrap) POSITION_ENGINE_V2 else POSITION_ENGINE_V1) &&
            stored.metadata.policyVersion == (if (bootstrap) POSITION_POLICY_BOOTSTRAP_V1 else POSITION_POLICY_V1)) { "Incompatible replay version" }
        val snapshot = requireNotNull(snapshots.get(stored.metadata.brokerSnapshotId))
        inputs.anchors.forEach { anchor ->
            val durable = requireNotNull(anchors.get(anchor.anchorId)).domain()
            // Status may subsequently advance; the baseline/cut/cursor evidence must not change.
            require(durable.copy(status = anchor.status, invalidatedAtEpochMillis = anchor.invalidatedAtEpochMillis,
                invalidationReason = anchor.invalidationReason) == anchor)
        }
        PositionReconciliationEngine().reconcile(LocalPositionExpectationEngine().evaluate(inputs.history, inputs.anchors),
            snapshot.domain(CutAssurance.valueOf(stored.metadata.localEvidenceAlignment)), SnapshotFreshness.valueOf(stored.metadata.freshness)) == stored.report
    }
}
