package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.reconciliation.domain.*
import com.vela.android.lab.db.room.entities.PaperBrokerSnapshotEntity
import com.vela.android.lab.db.room.entities.PaperBrokerPositionSnapshotEntity
import org.json.JSONArray
import org.json.JSONObject

internal fun enumNames(values: Collection<Enum<*>>): String = JSONArray(values.map { it.name }.sorted()).toString()
internal inline fun <reified T : Enum<T>> readEnumNames(text: String): Set<T> {
    val array = JSONArray(text)
    return (0 until array.length()).map { enumValueOf<T>(array.getString(it)) }.toSet()
}

data class StoredBrokerSnapshot(val metadata: PaperBrokerSnapshotEntity, val positions: List<PaperBrokerPositionSnapshotEntity>) {
    val anchorEligible: Boolean get() = metadata.completeness == BrokerSnapshotCompleteness.COMPLETE.name &&
        validAccountRef(metadata.accountRef) && metadata.localHistoryStable
    fun domain(alignment: CutAssurance = CutAssurance.UNKNOWN): BrokerPositionSnapshot = BrokerPositionSnapshot(
        metadata.snapshotId, metadata.accountRef, metadata.startedAtEpochMillis, metadata.completedAtEpochMillis,
        BrokerSnapshotCompleteness.valueOf(metadata.completeness), positions.map {
            BrokerPositionQuantity(it.symbol, QuantityEvidence(requireCanonicalDecimal(it.qtyCanonicalDecimal),
                DecimalProvenance.valueOf(it.decimalProvenance)), BrokerPositionSide.valueOf(it.side))
        }, alignment,
    )
}

/** No update/delete/replace operations. A failed attempt is distinct from the latest complete capture. */
class PaperBrokerPositionSnapshotRepository(private val database: PositionEvidenceDatabase) {
    suspend fun persist(metadata: PaperBrokerSnapshotEntity, rows: List<CapturedPosition>): StoredBrokerSnapshot = database.transaction {
        require(metadata.sequence == 0L && metadata.snapshotId.isNotBlank() && metadata.manualRefreshId.isNotBlank())
        require(metadata.source == POSITION_CAPTURE_SOURCE && metadata.parserVersion == POSITION_CAPTURE_PARSER_V1)
        require(metadata.accountRef == null || validAccountRef(metadata.accountRef))
        require(metadata.sessionRef.matches(Regex("[0-9a-f-]{36}")))
        require(metadata.configRef.matches(Regex("[0-9a-f-]{36}:[0-9]+")))
        require(metadata.accountHttpStatus == null || metadata.accountHttpStatus in 100..599)
        require(metadata.positionsHttpStatus == null || metadata.positionsHttpStatus in 100..599)
        require(metadata.positionsReceivedCount >= metadata.positionsValidatedCount && metadata.positionsValidatedCount >= 0)
        require(metadata.persistedAtEpochMillis >= 0)
        val diagnostics = readEnumNames<CaptureDiagnostic>(metadata.diagnosticsJson)
        CaptureDiagnostic.valueOf(metadata.accountRequestOutcome)
        CaptureDiagnostic.valueOf(metadata.positionsRequestOutcome)
        val completeness = BrokerSnapshotCompleteness.valueOf(metadata.completeness)
        if (completeness == BrokerSnapshotCompleteness.COMPLETE) {
            require(diagnostics.isEmpty() && validAccountRef(metadata.accountRef))
            require(metadata.accountRequestOutcome == CaptureDiagnostic.SUCCESS_COMPLETE.name && metadata.positionsRequestOutcome == CaptureDiagnostic.SUCCESS_COMPLETE.name)
            require(metadata.accountHttpStatus == 200 && metadata.positionsHttpStatus == 200)
            require(metadata.startedAtEpochMillis >= 0 && metadata.startedMonotonicNanos >= 0 && metadata.completedMonotonicNanos >= metadata.startedMonotonicNanos)
            require(metadata.accountCompletedAtEpochMillis != null && metadata.positionsCompletedAtEpochMillis != null)
            require(metadata.accountCompletedAtEpochMillis >= metadata.startedAtEpochMillis &&
                metadata.positionsCompletedAtEpochMillis >= metadata.accountCompletedAtEpochMillis &&
                metadata.completedAtEpochMillis >= metadata.positionsCompletedAtEpochMillis)
            require(metadata.positionsReceivedCount == rows.size && metadata.positionsValidatedCount == rows.size)
        } else {
            require(rows.isEmpty() && diagnostics.isNotEmpty())
        }
        metadata.observedAccountJson?.let { text ->
            val account = JSONObject(text)
            require(account.keySet().all { it in setOf("cash", "equity", "buyingPower", "portfolioValue") })
            account.keySet().forEach { key -> if (!account.isNull(key)) requireCanonicalDecimal(account.getString(key)) }
        }
        require(rows.map { it.symbol }.distinct().size == rows.size)
        val entities = rows.sortedBy { it.symbol }.mapIndexed { index, row ->
            require(validEvidenceSymbol(row.symbol))
            val qty = requireCanonicalDecimal(row.qtyCanonicalDecimal)
            require(DecimalQuantity.parse(row.qtyRawDecimal) == qty)
            require((row.side == BrokerPositionSide.LONG && qty > DecimalQuantity.ZERO) ||
                (row.side == BrokerPositionSide.SHORT && qty < DecimalQuantity.ZERO))
            PaperBrokerPositionSnapshotEntity(metadata.snapshotId, row.symbol, index, row.side.name,
                row.qtyRawDecimal, row.qtyCanonicalDecimal, DecimalProvenance.EXACT_DECIMAL.name)
        }
        val checkpoint = historyCheckpoint()
        val stored = metadata.copy(sequence = database.evidence.nextSnapshotSequence(),
            localHistoryStable = metadata.localHistoryDigest == checkpoint.digest,
            localHistoryDigest = checkpoint.digest, orderSequenceInclusive = checkpoint.orderSequence,
            lifecycleSequenceInclusive = checkpoint.lifecycleSequence)
        database.evidence.insertBrokerSnapshot(stored)
        entities.forEach { database.evidence.insertBrokerPositionSnapshot(it) }
        StoredBrokerSnapshot(stored, entities)
    }

    suspend fun get(snapshotId: String): StoredBrokerSnapshot? = database.transaction {
        database.evidence.snapshot(snapshotId)?.let { StoredBrokerSnapshot(it, database.evidence.positions(it.snapshotId)) }
    }
    suspend fun latestComplete(accountRef: String? = null): StoredBrokerSnapshot? = database.transaction {
        database.evidence.latestComplete(accountRef)?.let { StoredBrokerSnapshot(it, database.evidence.positions(it.snapshotId)) }
    }
    suspend fun history(): List<PaperBrokerSnapshotEntity> = database.evidence.snapshotHistory()

    suspend fun historyCheckpoint(): LocalEvidenceCheckpoint = database.transaction {
        val all = database.fullCanonicalHistory()
        val json = PositionEvidenceCodec.encode(DurablePositionInputs(PositionHistoryInput(null, all, HistoryCompleteness.INCOMPLETE), emptyList()))
        LocalEvidenceCheckpoint(evidenceDigest(json), all.mapNotNull { it.orderSequenceId }.maxOrNull() ?: 0,
            all.flatMap { it.lifecycleObservations }.maxOfOrNull { it.databaseId } ?: 0)
    }
}

data class LocalEvidenceCheckpoint(val digest: String, val orderSequence: Long, val lifecycleSequence: Long)
