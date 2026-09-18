package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.reconciliation.domain.BrokerSnapshotCompleteness
import com.vela.android.lab.db.room.entities.PaperBrokerSnapshotEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

sealed interface ManualCaptureResult {
    data object Busy : ManualCaptureResult
    data class Persisted(val snapshot: StoredBrokerSnapshot) : ManualCaptureResult
    data object PersistenceFailure : ManualCaptureResult
}

/** Explicit suspend call only. No startup/navigation wiring, scheduler or retry. */
class PaperPositionEvidenceCaptureCoordinator(
    private val configuration: PaperCaptureConfiguration,
    private val transport: PaperCaptureTransport,
    private val repository: PaperBrokerPositionSnapshotRepository,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val monotonicTime: () -> Long = System::nanoTime,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val parser: StrictPaperCaptureParser = StrictPaperCaptureParser(),
) {
    suspend fun captureManually(): ManualCaptureResult {
        if (!captureLock.tryLock()) return ManualCaptureResult.Busy
        try {
            val start = wallTime()
            val mono = monotonicTime()
            val refreshId = newId()
            val snapshotId = newId()
            val session = configuration.read()
            val localStart = repository.historyCheckpoint()
            val diagnostics = linkedSetOf<CaptureDiagnostic>()
            var accountAt: Long? = null
            var positionsAt: Long? = null
            var accountCode: Int? = null
            var positionsCode: Int? = null
            var account: ObservedPaperAccount? = null
            var rows = emptyList<CapturedPosition>()
            var received = 0
            var validated = 0
            var accountOutcome = CaptureDiagnostic.AUTH_FAILURE
            var positionsOutcome = CaptureDiagnostic.NOT_REQUESTED
            if (session.credentials != null) {
                val response = request(PaperCaptureEndpoint.ACCOUNT, session)
                accountAt = wallTime(); accountCode = response.code
                val parsed = if (response.failure == null && response.code == 200) parser.account(response.body) else null
                accountOutcome = response.failure ?: parsed?.diagnostic ?: CaptureDiagnostic.HTTP_FAILURE
                account = parsed?.value
                if (accountOutcome == CaptureDiagnostic.SUCCESS_COMPLETE && configuration.isCurrent(session)) {
                    val positionsResponse = request(PaperCaptureEndpoint.POSITIONS, session)
                    positionsAt = wallTime(); positionsCode = positionsResponse.code
                    val positions = if (positionsResponse.failure == null && positionsResponse.code == 200) parser.positions(positionsResponse.body) else null
                    positionsOutcome = positionsResponse.failure ?: positions?.diagnostic ?: CaptureDiagnostic.HTTP_FAILURE
                    rows = positions?.value.orEmpty(); received = positions?.receivedCount ?: 0; validated = positions?.validatedCount ?: 0
                } else if (accountOutcome == CaptureDiagnostic.SUCCESS_COMPLETE) diagnostics += CaptureDiagnostic.CONFIG_CHANGED
            }
            if (accountOutcome != CaptureDiagnostic.SUCCESS_COMPLETE) diagnostics += accountOutcome
            if (positionsOutcome != CaptureDiagnostic.SUCCESS_COMPLETE) diagnostics += positionsOutcome
            if (!configuration.isCurrent(session)) diagnostics += CaptureDiagnostic.CONFIG_CHANGED
            val end = wallTime()
            val endMono = monotonicTime()
            if (start < 0 || mono < 0 || end < (positionsAt ?: accountAt ?: start) ||
                (accountAt ?: start) < start || (positionsAt ?: accountAt ?: start) < (accountAt ?: start) || endMono < mono
            ) diagnostics += CaptureDiagnostic.CLOCK_REGRESSION
            val complete = diagnostics.isEmpty()
            val observed = account?.let {
                JSONObject().put("cash", it.cash ?: JSONObject.NULL).put("equity", it.equity ?: JSONObject.NULL)
                    .put("buyingPower", it.buyingPower ?: JSONObject.NULL).put("portfolioValue", it.portfolioValue ?: JSONObject.NULL).toString()
            }
            val candidate = PaperBrokerSnapshotEntity(snapshotId, 0, refreshId, account?.accountRef,
                POSITION_CAPTURE_SOURCE, POSITION_CAPTURE_PARSER_V1, session.configRef, session.sessionRef,
                start, accountAt, positionsAt, end, mono, endMono, accountOutcome.name, positionsOutcome.name,
                accountCode, positionsCode, received, validated,
                if (complete) BrokerSnapshotCompleteness.COMPLETE.name else BrokerSnapshotCompleteness.FAILED.name,
                enumNames(diagnostics), wallTime(), observed, localHistoryDigest = localStart.digest)
            return try { ManualCaptureResult.Persisted(repository.persist(candidate, if (complete) rows else emptyList())) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ManualCaptureResult.PersistenceFailure }
        } finally { captureLock.unlock() }
    }

    private suspend fun request(endpoint: PaperCaptureEndpoint, session: PaperCaptureSession): CaptureHttpResponse = try {
        transport.get(endpoint, session)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { CaptureHttpResponse(null, null, CaptureDiagnostic.NETWORK_FAILURE) }

    private companion object { val captureLock = Mutex() }
}
