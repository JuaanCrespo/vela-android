package com.vela.android.lab.data.paper.reconciliation.integration

import com.vela.android.lab.data.paper.reconciliation.domain.*
import com.vela.android.lab.data.paper.reconciliation.evidence.*
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException

data class DurablePositionOverview(
    val lastAttempt: StoredBrokerSnapshot? = null,
    val latestComplete: StoredBrokerSnapshot? = null,
    val anchors: List<StoredPositionAnchor> = emptyList(),
    val latestReport: StoredPositionReport? = null,
    val reportCapturedAt: Long? = null,
)

enum class PositionRefreshResult { SUCCESS, BUSY, BROKER_READ_FAILED, CAPTURE_PERSISTENCE_FAILED, RECONCILIATION_PERSISTENCE_FAILED }
enum class BaselineBlockedReason { NO_SNAPSHOT, INVALID_SYMBOL, SNAPSHOT_INELIGIBLE, SNAPSHOT_NOT_FRESH, ACTIVE_CONFLICT, INSUFFICIENT_LOCAL_EVIDENCE }

data class BaselineSelection(
    val symbol: String,
    val snapshot: StoredBrokerSnapshot? = null,
    val proposal: PositionAnchor? = null,
    val knownDelta: QuantityEvidence = QuantityEvidence.UNKNOWN,
    val blockedReason: BaselineBlockedReason? = null,
) {
    val eligible: Boolean get() = proposal != null && blockedReason == null
}

/** No transport, credentials, SQL or execution dependency is exposed to the ViewModel. */
interface PositionReconciliationStore {
    suspend fun loadOffline(): DurablePositionOverview
    suspend fun refreshManually(): PositionRefreshResult
    suspend fun selectBaselineSymbol(symbol: String): BaselineSelection
    suspend fun establishBaseline(selection: BaselineSelection)
    suspend fun invalidateBaseline(anchorId: String)
}

/** Visual/acceptance policy only: age never schedules work. Regression is UNKNOWN, not fresh. */
data class PositionObservationPolicy(val maxAgeMillis: Long = 60_000L) {
    init { require(maxAgeMillis >= 0L) }
    fun freshness(capturedAt: Long, now: Long): SnapshotFreshness = when {
        capturedAt < 0L || now < capturedAt -> SnapshotFreshness.UNKNOWN
        now - capturedAt > maxAgeMillis -> SnapshotFreshness.STALE
        else -> SnapshotFreshness.FRESH
    }
}

/** Only refreshManually may capture AND produce a new report. All other operations are local. */
class CanonicalPositionReconciliationStore(
    private val database: PositionEvidenceDatabase,
    private val coordinator: PaperPositionEvidenceCaptureCoordinator,
    private val now: () -> Long = System::currentTimeMillis,
    private val policy: PositionObservationPolicy = PositionObservationPolicy(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : PositionReconciliationStore {
    private val snapshots = PaperBrokerPositionSnapshotRepository(database)
    private val anchors = PaperPositionAnchorRepository(database)
    private val reports = PaperPositionReconciliationReportRepository(database)
    private val history = ConsistentPositionHistoryReader(database)

    override suspend fun loadOffline(): DurablePositionOverview = database.transaction {
        val report = database.evidence.latestReportId()?.let { reports.get(it) }
        DurablePositionOverview(
            snapshots.history().firstOrNull()?.let { snapshots.get(it.snapshotId) },
            snapshots.latestComplete(),
            database.evidence.allAnchors().map { requireNotNull(anchors.get(it.anchorId)) },
            report,
            report?.let { snapshots.get(it.metadata.brokerSnapshotId)?.metadata?.completedAtEpochMillis },
        )
    }

    override suspend fun refreshManually(): PositionRefreshResult {
        return when (val result = coordinator.captureManually()) {
            ManualCaptureResult.Busy -> PositionRefreshResult.BUSY
            ManualCaptureResult.PersistenceFailure -> PositionRefreshResult.CAPTURE_PERSISTENCE_FAILED
            is ManualCaptureResult.Persisted -> {
                // Only the durable identity crosses the capture boundary; reload before interpretation.
                val snapshot = requireNotNull(snapshots.get(result.snapshot.metadata.snapshotId))
                if (snapshot.metadata.completeness != BrokerSnapshotCompleteness.COMPLETE.name) {
                    PositionRefreshResult.BROKER_READ_FAILED
                } else {
                    try {
                        database.transaction {
                            val accountRef = snapshot.metadata.accountRef
                            val activeIds = database.evidence.allAnchors().filter {
                                it.accountRef == accountRef && it.status == AnchorStatus.ACTIVE.name
                            }.map { it.anchorId }
                            reports.createReport(newId(), snapshot.metadata.snapshotId, activeIds,
                                scopeAssurance(accountRef), policy.freshness(snapshot.metadata.completedAtEpochMillis, now()),
                                CutAssurance.CONFIRMED, now())
                        }
                        PositionRefreshResult.SUCCESS
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { PositionRefreshResult.RECONCILIATION_PERSISTENCE_FAILED }
                }
            }
        }
    }

    /** Never infer the legacy account from current credentials, symbols, prices or order IDs. */
    private suspend fun scopeAssurance(accountRef: String?): CutAssurance {
        val input = history.read(accountRef, CutAssurance.CONFIRMED)
        return if (input.completeness == HistoryCompleteness.COMPLETE &&
            input.histories.all { it.submitAttemptId in input.decimalEvidence }
        ) CutAssurance.CONFIRMED else CutAssurance.UNKNOWN
    }

    override suspend fun selectBaselineSymbol(symbol: String): BaselineSelection = database.transaction {
        val normalized = symbol.trim().uppercase(Locale.ROOT)
        fun blocked(reason: BaselineBlockedReason, snapshot: StoredBrokerSnapshot? = null) =
            BaselineSelection(normalized, snapshot = snapshot, blockedReason = reason)
        if (!validPositionSymbol(normalized)) return@transaction blocked(BaselineBlockedReason.INVALID_SYMBOL)
        val snapshot = snapshots.latestComplete() ?: return@transaction blocked(BaselineBlockedReason.NO_SNAPSHOT)
        if (!snapshot.anchorEligible) return@transaction blocked(BaselineBlockedReason.SNAPSHOT_INELIGIBLE, snapshot)
        if (policy.freshness(snapshot.metadata.completedAtEpochMillis, now()) != SnapshotFreshness.FRESH) {
            return@transaction blocked(BaselineBlockedReason.SNAPSHOT_NOT_FRESH, snapshot)
        }
        val accountRef = requireNotNull(snapshot.metadata.accountRef)
        if (anchors.getActiveAnchor(normalized, accountRef) != null) {
            return@transaction blocked(BaselineBlockedReason.ACTIVE_CONFLICT, snapshot)
        }
        val scope = scopeAssurance(accountRef)
        try {
            val proposal = anchors.prepareAnchor(newId(), normalized, snapshot.metadata.snapshotId, scope, now())
            val local = LocalPositionExpectationEngine().evaluate(history.read(accountRef, scope))
            BaselineSelection(normalized, snapshot, proposal,
                local.positions.singleOrNull { it.symbol == normalized }?.knownVelaFillDelta ?: QuantityEvidence.ZERO)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { blocked(BaselineBlockedReason.INSUFFICIENT_LOCAL_EVIDENCE, snapshot) }
    }

    override suspend fun establishBaseline(selection: BaselineSelection) = database.transaction {
        require(selection.eligible)
        val proposal = requireNotNull(selection.proposal)
        val snapshot = requireNotNull(snapshots.latestComplete())
        require(snapshot.metadata.snapshotId == selection.snapshot?.metadata?.snapshotId) { "Snapshot changed" }
        require(policy.freshness(snapshot.metadata.completedAtEpochMillis, now()) == SnapshotFreshness.FRESH)
        // Revalidate on confirm, including account scope, digest, precision, coverage and active conflict.
        anchors.createAnchor(proposal.copy(createdAtEpochMillis = now()), snapshot.metadata.snapshotId,
            scopeAssurance(snapshot.metadata.accountRef))
        Unit
    }

    override suspend fun invalidateBaseline(anchorId: String) {
        anchors.invalidateAnchor(anchorId, now(), AnchorInvalidationReason.MANUAL)
    }
}
