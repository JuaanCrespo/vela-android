package com.vela.android.lab.db.room.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Phase 2.y.3: new evidence only. All authoritative decimal quantities use TEXT.

@Entity(
    tableName = "paper_broker_snapshot",
    primaryKeys = ["snapshotId"],
    indices = [Index(value = ["manualRefreshId"], unique = true), Index(value = ["sequence"], unique = true)],
)
data class PaperBrokerSnapshotEntity(
    val snapshotId: String,
    val sequence: Long,
    val manualRefreshId: String,
    val accountRef: String?,
    val source: String,
    val parserVersion: String,
    val configRef: String,
    val sessionRef: String,
    val startedAtEpochMillis: Long,
    val accountCompletedAtEpochMillis: Long?,
    val positionsCompletedAtEpochMillis: Long?,
    val completedAtEpochMillis: Long,
    val startedMonotonicNanos: Long,
    val completedMonotonicNanos: Long,
    val accountRequestOutcome: String,
    val positionsRequestOutcome: String,
    val accountHttpStatus: Int?,
    val positionsHttpStatus: Int?,
    val positionsReceivedCount: Int,
    val positionsValidatedCount: Int,
    val completeness: String,
    val diagnosticsJson: String,
    val persistedAtEpochMillis: Long,
    val observedAccountJson: String?,
    val localHistoryDigest: String = "",
    val localHistoryStable: Boolean = false,
    val orderSequenceInclusive: Long = 0,
    val lifecycleSequenceInclusive: Long = 0,
)

@Entity(
    tableName = "paper_broker_position_snapshot",
    primaryKeys = ["snapshotId", "symbol"],
    indices = [Index(value = ["snapshotId", "rowIndex"], unique = true)],
    foreignKeys = [ForeignKey(entity = PaperBrokerSnapshotEntity::class, parentColumns = ["snapshotId"], childColumns = ["snapshotId"], onDelete = ForeignKey.NO_ACTION)],
)
data class PaperBrokerPositionSnapshotEntity(
    val snapshotId: String,
    val symbol: String,
    val rowIndex: Int,
    val side: String,
    val qtyRawDecimal: String,
    val qtyCanonicalDecimal: String,
    val decimalProvenance: String,
)

@Entity(
    tableName = "paper_position_anchor",
    primaryKeys = ["anchorId"],
    indices = [Index(value = ["brokerSnapshotId"], unique = false), Index(value = ["activeKey"], unique = true), Index(value = ["accountRef", "symbol"], unique = false)],
    foreignKeys = [ForeignKey(entity = PaperBrokerSnapshotEntity::class, parentColumns = ["snapshotId"], childColumns = ["brokerSnapshotId"], onDelete = ForeignKey.NO_ACTION)],
)
data class PaperPositionAnchorEntity(
    val anchorId: String,
    val symbol: String,
    val accountRef: String,
    val brokerSnapshotId: String,
    val baselineQty: String,
    val baselineProvenance: String,
    val orderSequenceInclusive: Long,
    val lifecycleSequenceInclusive: Long,
    val cutAssurance: String,
    val status: String,
    val activeKey: String?,
    val createdAtEpochMillis: Long,
    val invalidatedAtEpochMillis: Long?,
    val invalidationReason: String?,
    val version: Long,
)

@Entity(
    tableName = "paper_position_anchor_cursor",
    primaryKeys = ["anchorId", "attemptId"],
    foreignKeys = [ForeignKey(entity = PaperPositionAnchorEntity::class, parentColumns = ["anchorId"], childColumns = ["anchorId"], onDelete = ForeignKey.NO_ACTION)],
)
data class PaperPositionAnchorCursorEntity(
    val anchorId: String,
    val attemptId: String,
    val orderId: String?,
    val clientOrderId: String?,
    val orderSequenceId: Long?,
    val symbol: String?,
    val side: String?,
    val includedFilledQty: String,
    val provenance: String,
    val observationSequence: Long,
    val payloadFingerprint: String,
)

@Entity(
    tableName = "paper_position_anchor_event",
    primaryKeys = ["eventId"],
    indices = [Index(value = ["anchorId", "version"], unique = true)],
    foreignKeys = [ForeignKey(entity = PaperPositionAnchorEntity::class, parentColumns = ["anchorId"], childColumns = ["anchorId"], onDelete = ForeignKey.NO_ACTION)],
)
data class PaperPositionAnchorEventEntity(
    val eventId: String,
    val anchorId: String,
    val version: Long,
    val type: String,
    val timestampEpochMillis: Long,
    val reason: String?,
    val evidenceSnapshotId: String,
)

@Entity(
    tableName = "paper_position_reconciliation_report",
    primaryKeys = ["reportId"],
    indices = [Index(value = ["brokerSnapshotId"], unique = false)],
    foreignKeys = [ForeignKey(entity = PaperBrokerSnapshotEntity::class, parentColumns = ["snapshotId"], childColumns = ["brokerSnapshotId"], onDelete = ForeignKey.NO_ACTION)],
)
data class PaperPositionReconciliationReportEntity(
    val reportId: String,
    val brokerSnapshotId: String,
    val engineVersion: String,
    val policyVersion: String,
    val createdAtEpochMillis: Long,
    val accountRef: String?,
    val matchedCount: Int,
    val mismatchedCount: Int,
    val unanchoredCount: Int,
    val unknownCount: Int,
    val diagnosticsJson: String,
    val inputsJson: String,
    val inputsDigest: String,
    val freshness: String,
    val localEvidenceAlignment: String,
)

@Entity(
    tableName = "paper_position_reconciliation_row",
    primaryKeys = ["reportId", "symbol"],
    indices = [Index(value = ["anchorId"], unique = false)],
    foreignKeys = [ForeignKey(entity = PaperPositionReconciliationReportEntity::class, parentColumns = ["reportId"], childColumns = ["reportId"], onDelete = ForeignKey.NO_ACTION), ForeignKey(entity = PaperPositionAnchorEntity::class, parentColumns = ["anchorId"], childColumns = ["anchorId"], onDelete = ForeignKey.NO_ACTION)],
)
data class PaperPositionReconciliationRowEntity(
    val reportId: String,
    val symbol: String,
    val brokerQty: String?,
    val brokerProvenance: String,
    val knownVelaDelta: String?,
    val knownDeltaProvenance: String,
    val knownDeltaComplete: Boolean,
    val anchorQty: String?,
    val anchorProvenance: String,
    val expectedQty: String?,
    val expectedProvenance: String,
    val difference: String?,
    val state: String,
    val presence: String,
    val diagnosticsJson: String,
    val anchorId: String?,
    val cause: String?,
)

@Entity(
    tableName = "paper_order_decimal_evidence",
    primaryKeys = ["observationId", "field"],
    indices = [Index(value = ["attemptId"], unique = false)],
    foreignKeys = [ForeignKey(entity = PaperOrderLifecycleObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.NO_ACTION)],
)
data class PaperOrderDecimalEvidenceEntity(
    val observationId: Long,
    val field: String,
    val attemptId: String,
    val orderId: String,
    val clientOrderId: String,
    val symbol: String,
    val side: String,
    val orderSequenceId: Long,
    val accountRef: String,
    val rawDecimal: String,
    val canonicalDecimal: String,
    val provenance: String,
    val payloadFingerprint: String,
    val parserVersion: String,
    val recordedAtEpochMillis: Long,
)
