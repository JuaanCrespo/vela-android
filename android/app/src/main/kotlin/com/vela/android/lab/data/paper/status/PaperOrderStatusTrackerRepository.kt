package com.vela.android.lab.data.paper.status

import com.vela.android.lab.data.paper.submit.PaperOrderSubmitAuditRepository
import com.vela.android.lab.db.room.dao.PaperOrderReconciliationDao
import com.vela.android.lab.db.room.entities.PaperOrderLifecycleObservationEntity
import com.vela.android.lab.db.room.entities.PaperOrderReconciliationEntity
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PaperOrderReconciliationVerdict {
    CLEAR,
    READY,
    SINGLE_UNRESOLVED,
    MULTIPLE_UNRESOLVED_PAPER_ORDERS,
    TERMINAL_RESET_REQUIRED,
    AMBIGUOUS,
}

enum class PaperOrderReconciliationIssue {
    LOCAL_AUDIT_INCOMPLETE,
    LOCAL_AUDIT_CONFLICT,
    UNKNOWN_LOCAL_RESULT,
    AMBIGUOUS_LOCAL_FAILURE,
    INVALID_ALPACA_ORDER_ID,
    INVALID_ORDER_IDENTITY,
    DUPLICATE_ALPACA_ORDER_ID,
    DUPLICATE_CLIENT_ORDER_ID,
    LIFECYCLE_IDENTITY_MISMATCH,
    LIFECYCLE_CONTRADICTION,
    MULTIPLE_UNRESOLVED_PAPER_ORDERS,
    TERMINAL_RESET_REQUIRED,
    RESPONSE_IDENTITY_MISMATCH,
    SELECTED_ORDER_NOT_ELIGIBLE,
    SELECTED_ORDER_IDENTITY_CHANGED,
    PERSISTENCE_FAILED,
}

data class PaperOrderLifecycleObservation(
    val id: Long,
    val status: String,
    val rawStatus: String,
    val observedAtEpochMillis: Long,
    val terminal: Boolean,
    val filledQuantity: Double?,
    val filledAveragePriceUsd: Double?,
    val filledAtIso: String?,
    val source: String,
    val httpStatusCode: Int?,
)

data class ReconciledPaperOrder(
    val submitAttemptId: String,
    val attemptStartedAuditEntryId: Long?,
    val submitResultAuditEntryId: Long?,
    val previewId: String?,
    val linkedClientDryRunId: String?,
    val orderId: String?,
    val clientOrderId: String?,
    val symbol: String?,
    val side: String?,
    val quantity: Double?,
    val orderType: String?,
    val timeInForce: String?,
    val submittedAtEpochMillis: Long?,
    val localSubmitResult: String?,
    val mappingExact: Boolean,
    val issues: Set<PaperOrderReconciliationIssue>,
    val lifecycleHistory: List<PaperOrderLifecycleObservation>,
    val latestLifecycleSnapshot: PaperOrderStatusSnapshot?,
    val lifecycleObservedAtEpochMillis: Long?,
    val terminal: Boolean,
    val resetAcknowledgedAtEpochMillis: Long?,
) {
    val manualLookupTarget: PaperOrderLifecycleLookupTarget?
        get() {
            if (!mappingExact || issues.isNotEmpty() || localSubmitResult != "SUBMITTED" ||
                terminal
            ) {
                return null
            }
            val target = PaperOrderLifecycleLookupTarget(
                submitAttemptId = submitAttemptId,
                attemptStartedAuditEntryId = attemptStartedAuditEntryId ?: return null,
                submitResultAuditEntryId = submitResultAuditEntryId ?: return null,
                submittedAtEpochMillis = submittedAtEpochMillis ?: return null,
                orderId = orderId ?: return null,
                clientOrderId = clientOrderId,
                symbol = symbol ?: return null,
                side = side ?: return null,
                quantity = quantity ?: return null,
                orderType = orderType ?: return null,
                timeInForce = timeInForce ?: return null,
            )
            return target.takeIf(PaperOrderLifecycleLookupTarget::isStructurallyValid)
        }

    val unresolvedRemoteOrder: Boolean
        get() = manualLookupTarget != null

    val terminalResetRequired: Boolean
        get() = mappingExact && localSubmitResult == "SUBMITTED" && terminal &&
            resetAcknowledgedAtEpochMillis == null
}

data class PaperOrderReconciliationSnapshot(
    val verdict: PaperOrderReconciliationVerdict,
    val candidates: List<ReconciledPaperOrder>,
    val issues: Set<PaperOrderReconciliationIssue>,
) {
    val preparationAllowed: Boolean
        get() = verdict == PaperOrderReconciliationVerdict.CLEAR ||
            verdict == PaperOrderReconciliationVerdict.READY

    val exactUnresolvedCandidates: List<ReconciledPaperOrder>
        get() = candidates.filter { it.manualLookupTarget != null }
            .sortedBy(ReconciledPaperOrder::submitAttemptId)

    val resolvedCount: Int
        get() = candidates.count {
            it.mappingExact && it.localSubmitResult == "SUBMITTED" && it.terminal
        }

    val unresolvedCount: Int
        get() = exactUnresolvedCandidates.size

    val ambiguousCount: Int
        get() = candidates.count { !it.mappingExact }

    val resetEligibleAttemptIds: List<String>
        get() = if (verdict == PaperOrderReconciliationVerdict.TERMINAL_RESET_REQUIRED) {
            candidates.filter(ReconciledPaperOrder::terminalResetRequired)
                .map(ReconciledPaperOrder::submitAttemptId)
                .sorted()
        } else {
            emptyList()
        }
}

data class PaperOrderLifecycleLookupTarget(
    val submitAttemptId: String,
    val attemptStartedAuditEntryId: Long,
    val submitResultAuditEntryId: Long,
    val submittedAtEpochMillis: Long,
    val orderId: String,
    val clientOrderId: String?,
    val symbol: String,
    val side: String,
    val quantity: Double,
    val orderType: String,
    val timeInForce: String,
)

private fun PaperOrderLifecycleLookupTarget.isStructurallyValid(): Boolean =
    submitAttemptId.isNotBlank() &&
        attemptStartedAuditEntryId > 0L &&
        submitResultAuditEntryId > 0L &&
        submittedAtEpochMillis >= 0L &&
        AlpacaPaperOrderStatusEndpoint.isCanonicalOrderId(orderId) &&
        (clientOrderId == null || clientOrderId.isNotBlank() && clientOrderId.length <= 128) &&
        symbol.matches(Regex("^[A-Z][A-Z0-9.-]{0,31}$")) &&
        side in setOf("BUY", "SELL") &&
        quantity.isFinite() && quantity > 0.0 &&
        orderType in setOf("MARKET", "LIMIT") &&
        timeInForce == "DAY"

sealed interface PaperOrderLifecycleLookupResult {
    data class Exact(val target: PaperOrderLifecycleLookupTarget) :
        PaperOrderLifecycleLookupResult

    data class Blocked(
        val verdict: PaperOrderReconciliationVerdict,
        val issues: Set<PaperOrderReconciliationIssue>,
    ) : PaperOrderLifecycleLookupResult
}

sealed interface PaperOrderLifecyclePersistResult {
    data class Persisted(val snapshot: PaperOrderReconciliationSnapshot) :
        PaperOrderLifecyclePersistResult

    data class Blocked(
        val snapshot: PaperOrderReconciliationSnapshot,
        val issues: Set<PaperOrderReconciliationIssue>,
    ) : PaperOrderLifecyclePersistResult
}

sealed interface PaperOrderResetAcknowledgementResult {
    data class Acknowledged(val snapshot: PaperOrderReconciliationSnapshot) :
        PaperOrderResetAcknowledgementResult

    data class Blocked(val snapshot: PaperOrderReconciliationSnapshot) :
        PaperOrderResetAcknowledgementResult
}

interface PaperOrderTrackingSource {
    suspend fun consolidateFromAudit(): PaperOrderReconciliationSnapshot

    suspend fun lookupTarget(
        selectedTarget: PaperOrderLifecycleLookupTarget,
    ): PaperOrderLifecycleLookupResult

    suspend fun persistLifecycle(
        target: PaperOrderLifecycleLookupTarget,
        status: PaperOrderStatusSnapshot,
        evidence: PaperOrderStatusFetchEvidence,
        observedAtEpochMillis: Long,
    ): PaperOrderLifecyclePersistResult

    suspend fun acknowledgeAllTerminalResets(
        acknowledgedAtEpochMillis: Long,
    ): PaperOrderResetAcknowledgementResult
}

/**
 * Persistent read-only reconciliation. It enumerates all attempts and never selects by time.
 * Lifecycle evidence is committed before it can unlock foreground state.
 */
class PaperOrderStatusTrackerRepository(
    private val auditRepository: PaperOrderSubmitAuditRepository,
    private val reconciliationDao: PaperOrderReconciliationDao,
) : PaperOrderTrackingSource {
    private val mutex = Mutex()

    override suspend fun consolidateFromAudit(): PaperOrderReconciliationSnapshot =
        mutex.withLock { consolidateLocked() }

    override suspend fun lookupTarget(
        selectedTarget: PaperOrderLifecycleLookupTarget,
    ): PaperOrderLifecycleLookupResult = mutex.withLock {
        lookupTarget(consolidateLocked(), selectedTarget)
    }

    override suspend fun persistLifecycle(
        target: PaperOrderLifecycleLookupTarget,
        status: PaperOrderStatusSnapshot,
        evidence: PaperOrderStatusFetchEvidence,
        observedAtEpochMillis: Long,
    ): PaperOrderLifecyclePersistResult = mutex.withLock {
        val before = consolidateLocked()
        val lookup = lookupTarget(before, target)
        if (lookup !is PaperOrderLifecycleLookupResult.Exact) {
            return@withLock PaperOrderLifecyclePersistResult.Blocked(
                before,
                (lookup as PaperOrderLifecycleLookupResult.Blocked).issues,
            )
        }
        if (!status.matches(target)) {
            return@withLock PaperOrderLifecyclePersistResult.Blocked(
                before,
                setOf(PaperOrderReconciliationIssue.RESPONSE_IDENTITY_MISMATCH),
            )
        }
        if (reconciliationDao.reconciliationsByClientOrderId(status.clientOrderId).any {
                it.submitAttemptId != target.submitAttemptId
            }
        ) {
            return@withLock PaperOrderLifecyclePersistResult.Blocked(
                before,
                setOf(PaperOrderReconciliationIssue.DUPLICATE_CLIENT_ORDER_ID),
            )
        }
        val current = reconciliationDao.reconciliationByAttemptId(target.submitAttemptId)
            ?: return@withLock PaperOrderLifecyclePersistResult.Blocked(
                before,
                setOf(PaperOrderReconciliationIssue.PERSISTENCE_FAILED),
            )
        val history = reconciliationDao.lifecycleByAttemptId(target.submitAttemptId)
        val observation = PaperOrderLifecycleObservationEntity(
            observationKey = lifecycleObservationKey(
                target,
                observedAtEpochMillis,
                history.size,
                status.rawStatus,
            ),
            submitAttemptId = target.submitAttemptId,
            alpacaOrderId = target.orderId,
            status = status.status.name,
            rawStatus = status.rawStatus,
            observedAtEpochMillis = observedAtEpochMillis,
            terminal = status.terminalForNewPreparation,
            filledQuantity = status.filledQuantity,
            filledAveragePriceUsd = status.filledAveragePriceUsd,
            filledAtIso = status.filledAtIso,
            source = evidence.source,
            httpStatusCode = evidence.httpStatusCode,
            submitAuditEntryId = target.submitResultAuditEntryId,
        )
        val updated = current.copy(
            clientOrderId = current.clientOrderId ?: status.clientOrderId,
            latestLifecycleStatus = status.status.name,
            latestLifecycleRawStatus = status.rawStatus,
            latestLifecycleObservedAtEpochMillis = observedAtEpochMillis,
            terminal = status.terminalForNewPreparation,
            filledQuantity = status.filledQuantity,
            filledAveragePriceUsd = status.filledAveragePriceUsd,
            filledAtIso = status.filledAtIso,
            lifecycleSource = evidence.source,
            lifecycleHttpStatusCode = evidence.httpStatusCode,
        )
        try {
            reconciliationDao.appendLifecycleObservation(observation, updated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock PaperOrderLifecyclePersistResult.Blocked(
                before,
                setOf(PaperOrderReconciliationIssue.PERSISTENCE_FAILED),
            )
        }
        PaperOrderLifecyclePersistResult.Persisted(consolidateLocked())
    }

    override suspend fun acknowledgeAllTerminalResets(
        acknowledgedAtEpochMillis: Long,
    ): PaperOrderResetAcknowledgementResult = mutex.withLock {
        require(acknowledgedAtEpochMillis >= 0L)
        val before = consolidateLocked()
        val expectedAttemptIds = before.resetEligibleAttemptIds
        if (before.verdict != PaperOrderReconciliationVerdict.TERMINAL_RESET_REQUIRED ||
            before.unresolvedCount != 0 || before.ambiguousCount != 0 ||
            expectedAttemptIds.isEmpty()
        ) {
            return@withLock PaperOrderResetAcknowledgementResult.Blocked(before)
        }
        val acknowledged = try {
            reconciliationDao.acknowledgeTerminalResetsAtomically(
                expectedAttemptIds,
                acknowledgedAtEpochMillis,
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!acknowledged) return@withLock PaperOrderResetAcknowledgementResult.Blocked(before)
        val after = buildSnapshotLocked()
        if (after.preparationAllowed) {
            PaperOrderResetAcknowledgementResult.Acknowledged(after)
        } else PaperOrderResetAcknowledgementResult.Blocked(after)
    }

    private suspend fun consolidateLocked(): PaperOrderReconciliationSnapshot {
        val rows = auditRepository.recent(Int.MAX_VALUE).sortedBy(PaperOrderSubmitAuditEntity::id)
        val existing = reconciliationDao.allReconciliations()
            .associateBy(PaperOrderReconciliationEntity::submitAttemptId)
        val derived = rows.groupBy(PaperOrderSubmitAuditEntity::submitAttemptId)
            .toSortedMap()
            .mapValues { (attemptId, events) ->
                deriveIdentity(attemptId, events, existing[attemptId])
            }
            .toMutableMap()
        val reconciled = existing.mapValues { it.value.withoutGlobalDuplicateIssues() }
            .toMutableMap()
            .apply { putAll(derived) }
        applyGlobalDuplicateIssues(reconciled)
        reconciled.values.forEach { entity ->
            val stored = existing[entity.submitAttemptId]
            if (stored == null) {
                reconciliationDao.insertReconciliationIfAbsent(entity)
            } else if (stored != entity) {
                reconciliationDao.updateReconciliation(entity)
            }
        }
        appendMissingLocalSubmittedObservations()
        return buildSnapshotLocked()
    }

    private suspend fun appendMissingLocalSubmittedObservations() {
        reconciliationDao.allReconciliations().forEach { entity ->
            if (entity.mappingStatus != MAPPING_EXACT || entity.localSubmitResult != SUBMITTED) {
                return@forEach
            }
            val orderId = entity.alpacaOrderId ?: return@forEach
            val auditEntryId = entity.submitResultAuditEntryId ?: return@forEach
            val submittedAt = entity.submittedAtEpochMillis ?: return@forEach
            if (reconciliationDao.lifecycleByAttemptId(entity.submitAttemptId).isNotEmpty()) {
                return@forEach
            }
            val observation = PaperOrderLifecycleObservationEntity(
                observationKey = "LOCAL_SUBMIT:" + entity.submitAttemptId + ":$auditEntryId",
                submitAttemptId = entity.submitAttemptId,
                alpacaOrderId = orderId,
                status = SUBMITTED,
                rawStatus = "submitted",
                observedAtEpochMillis = submittedAt,
                terminal = false,
                filledQuantity = 0.0,
                filledAveragePriceUsd = null,
                filledAtIso = null,
                source = LOCAL_SUBMIT_AUDIT,
                httpStatusCode = null,
                submitAuditEntryId = auditEntryId,
            )
            reconciliationDao.appendLifecycleObservation(
                observation,
                entity.copy(
                    latestLifecycleStatus = SUBMITTED,
                    latestLifecycleRawStatus = "submitted",
                    latestLifecycleObservedAtEpochMillis = submittedAt,
                    terminal = false,
                    filledQuantity = 0.0,
                    lifecycleSource = LOCAL_SUBMIT_AUDIT,
                    lifecycleHttpStatusCode = null,
                ),
            )
        }
    }

    private fun deriveIdentity(
        attemptId: String,
        rows: List<PaperOrderSubmitAuditEntity>,
        previous: PaperOrderReconciliationEntity?,
    ): PaperOrderReconciliationEntity {
        val starts = rows.filter { it.status == ATTEMPT_STARTED }
        val results = rows.filter { it.status != ATTEMPT_STARTED }
        val issues = linkedSetOf<PaperOrderReconciliationIssue>()
        if (starts.size > 1 || results.size > 1 || rows.any { !it.eventKeyMatches() }) {
            issues += PaperOrderReconciliationIssue.LOCAL_AUDIT_CONFLICT
        }
        if (results.isEmpty()) issues += PaperOrderReconciliationIssue.LOCAL_AUDIT_INCOMPLETE
        val result = results.singleOrNull()
        if (result != null && result.status !in KNOWN_LOCAL_RESULTS) {
            issues += PaperOrderReconciliationIssue.UNKNOWN_LOCAL_RESULT
        }
        if (result?.status == FAILED) {
            issues += PaperOrderReconciliationIssue.AMBIGUOUS_LOCAL_FAILURE
        }
        if (result?.status == SUBMITTED &&
            result.alpacaOrderId?.let(AlpacaPaperOrderStatusEndpoint::isCanonicalOrderId) != true
        ) {
            issues += PaperOrderReconciliationIssue.INVALID_ALPACA_ORDER_ID
        }
        if (result?.status in setOf(SUBMITTED, REJECTED, FAILED) && starts.size != 1) {
            issues += PaperOrderReconciliationIssue.LOCAL_AUDIT_INCOMPLETE
        }
        if (!rows.haveConsistentIdentity()) {
            issues += PaperOrderReconciliationIssue.LOCAL_AUDIT_CONFLICT
        }
        val evidence = rows.firstOrNull()
        val identity = PaperOrderReconciliationEntity(
            submitAttemptId = attemptId,
            attemptStartedAuditEntryId = starts.singleOrNull()?.id,
            submitResultAuditEntryId = result?.id,
            previewId = evidence?.previewId.takeIf { rows.sameValue { it.previewId } },
            linkedClientDryRunId =
                evidence?.linkedClientDryRunId.takeIf { rows.sameValue { it.linkedClientDryRunId } },
            alpacaOrderId = result?.alpacaOrderId,
            clientOrderId = evidence?.clientOrderId.takeIf { rows.sameValue { it.clientOrderId } },
            symbol = evidence?.symbol.takeIf { rows.sameValue { it.symbol } },
            side = evidence?.side.takeIf { rows.sameValue { it.side } },
            quantity = evidence?.quantity.takeIf { rows.sameValue { it.quantity } },
            orderType = evidence?.orderType.takeIf { rows.sameValue { it.orderType } },
            timeInForce = evidence?.timeInForce.takeIf { rows.sameValue { it.timeInForce } },
            limitPriceUsd = evidence?.limitPriceUsd.takeIf { rows.sameValue { it.limitPriceUsd } },
            submittedAtEpochMillis = result?.submittedAtEpochMillis,
            localSubmitResult = result?.status,
            mappingStatus = MAPPING_EXACT,
            mappingDiagnostic = null,
            latestLifecycleStatus = previous?.latestLifecycleStatus,
            latestLifecycleRawStatus = previous?.latestLifecycleRawStatus,
            latestLifecycleObservedAtEpochMillis = previous?.latestLifecycleObservedAtEpochMillis,
            terminal = previous?.terminal ?: false,
            filledQuantity = previous?.filledQuantity,
            filledAveragePriceUsd = previous?.filledAveragePriceUsd,
            filledAtIso = previous?.filledAtIso,
            lifecycleSource = previous?.lifecycleSource,
            lifecycleHttpStatusCode = previous?.lifecycleHttpStatusCode,
            resetAcknowledgedAtEpochMillis = previous?.resetAcknowledgedAtEpochMillis,
        )
        if (result?.status == SUBMITTED && !identity.hasStructurallyValidSubmittedIdentity()) {
            issues += PaperOrderReconciliationIssue.INVALID_ORDER_IDENTITY
        }
        return identity.copy(
            mappingStatus = if (issues.isEmpty()) MAPPING_EXACT else MAPPING_AMBIGUOUS,
            mappingDiagnostic = issues.toDiagnostic(),
        )
    }

    private fun PaperOrderReconciliationEntity.hasStructurallyValidSubmittedIdentity(): Boolean {
        if (localSubmitResult != SUBMITTED) return false
        val target = PaperOrderLifecycleLookupTarget(
            submitAttemptId = submitAttemptId,
            attemptStartedAuditEntryId = attemptStartedAuditEntryId ?: return false,
            submitResultAuditEntryId = submitResultAuditEntryId ?: return false,
            submittedAtEpochMillis = submittedAtEpochMillis ?: return false,
            orderId = alpacaOrderId ?: return false,
            clientOrderId = clientOrderId,
            symbol = symbol ?: return false,
            side = side ?: return false,
            quantity = quantity ?: return false,
            orderType = orderType ?: return false,
            timeInForce = timeInForce ?: return false,
        )
        return target.isStructurallyValid()
    }

    private fun PaperOrderReconciliationEntity.withoutGlobalDuplicateIssues(): PaperOrderReconciliationEntity {
        val issues = mappingDiagnostic.toIssues() - GLOBAL_DUPLICATE_ISSUES
        return copy(
            mappingStatus = if (issues.isEmpty()) MAPPING_EXACT else MAPPING_AMBIGUOUS,
            mappingDiagnostic = issues.toDiagnostic(),
        )
    }

    private fun applyGlobalDuplicateIssues(
        entities: MutableMap<String, PaperOrderReconciliationEntity>,
    ) {
        markDuplicates(
            entities,
            PaperOrderReconciliationEntity::alpacaOrderId,
            PaperOrderReconciliationIssue.DUPLICATE_ALPACA_ORDER_ID,
        )
        markDuplicates(
            entities,
            PaperOrderReconciliationEntity::clientOrderId,
            PaperOrderReconciliationIssue.DUPLICATE_CLIENT_ORDER_ID,
        )
    }

    private fun markDuplicates(
        entities: MutableMap<String, PaperOrderReconciliationEntity>,
        key: (PaperOrderReconciliationEntity) -> String?,
        issue: PaperOrderReconciliationIssue,
    ) {
        entities.values.groupBy(key)
            .filterKeys { !it.isNullOrBlank() }
            .values
            .filter { rows ->
                rows.map(PaperOrderReconciliationEntity::submitAttemptId).distinct().size > 1
            }
            .flatten()
            .forEach { duplicate ->
                val issues = duplicate.mappingDiagnostic.toIssues() + issue
                entities[duplicate.submitAttemptId] = duplicate.copy(
                    mappingStatus = MAPPING_AMBIGUOUS,
                    mappingDiagnostic = issues.toDiagnostic(),
                )
            }
    }

    private suspend fun buildSnapshotLocked(): PaperOrderReconciliationSnapshot {
        val candidates = reconciliationDao.allReconciliations().map { entity ->
            val history = reconciliationDao.lifecycleByAttemptId(entity.submitAttemptId)
            val issues = entity.mappingDiagnostic.toIssues() + lifecycleIssues(entity, history)
            val latestGet = history.lastOrNull {
                it.source == PaperOrderStatusFetchEvidence.SOURCE
            }
            val latestValidatedSnapshot = latestGet?.validatedSnapshot(entity)
            ReconciledPaperOrder(
                submitAttemptId = entity.submitAttemptId,
                attemptStartedAuditEntryId = entity.attemptStartedAuditEntryId,
                submitResultAuditEntryId = entity.submitResultAuditEntryId,
                previewId = entity.previewId,
                linkedClientDryRunId = entity.linkedClientDryRunId,
                orderId = entity.alpacaOrderId,
                clientOrderId = entity.clientOrderId,
                symbol = entity.symbol,
                side = entity.side,
                quantity = entity.quantity,
                orderType = entity.orderType,
                timeInForce = entity.timeInForce,
                submittedAtEpochMillis = entity.submittedAtEpochMillis,
                localSubmitResult = entity.localSubmitResult,
                mappingExact = entity.mappingStatus == MAPPING_EXACT && issues.isEmpty(),
                issues = issues,
                lifecycleHistory = history.map { it.toDomain() },
                latestLifecycleSnapshot = latestValidatedSnapshot,
                lifecycleObservedAtEpochMillis = latestGet?.observedAtEpochMillis
                    ?.takeIf { latestValidatedSnapshot != null },
                terminal = latestValidatedSnapshot?.terminalForNewPreparation == true,
                resetAcknowledgedAtEpochMillis = entity.resetAcknowledgedAtEpochMillis,
            )
        }
        val ambiguous = candidates.flatMap(ReconciledPaperOrder::issues).toSet()
        val unresolved = candidates.filter(ReconciledPaperOrder::unresolvedRemoteOrder)
        val resetRequired = candidates.filter(ReconciledPaperOrder::terminalResetRequired)
        val verdict = when {
            candidates.isEmpty() -> PaperOrderReconciliationVerdict.CLEAR
            ambiguous.isNotEmpty() -> PaperOrderReconciliationVerdict.AMBIGUOUS
            unresolved.size > 1 ->
                PaperOrderReconciliationVerdict.MULTIPLE_UNRESOLVED_PAPER_ORDERS
            unresolved.size == 1 -> PaperOrderReconciliationVerdict.SINGLE_UNRESOLVED
            resetRequired.isNotEmpty() ->
                PaperOrderReconciliationVerdict.TERMINAL_RESET_REQUIRED
            else -> PaperOrderReconciliationVerdict.READY
        }
        val issues = ambiguous.toMutableSet()
        if (verdict == PaperOrderReconciliationVerdict.MULTIPLE_UNRESOLVED_PAPER_ORDERS) {
            issues += PaperOrderReconciliationIssue.MULTIPLE_UNRESOLVED_PAPER_ORDERS
        }
        if (verdict == PaperOrderReconciliationVerdict.TERMINAL_RESET_REQUIRED) {
            issues += PaperOrderReconciliationIssue.TERMINAL_RESET_REQUIRED
        }
        return PaperOrderReconciliationSnapshot(verdict, candidates, issues)
    }

    private fun lookupTarget(
        snapshot: PaperOrderReconciliationSnapshot,
        selectedTarget: PaperOrderLifecycleLookupTarget,
    ): PaperOrderLifecycleLookupResult {
        if (!selectedTarget.isStructurallyValid()) {
            return PaperOrderLifecycleLookupResult.Blocked(
                snapshot.verdict,
                snapshot.issues + PaperOrderReconciliationIssue.INVALID_ORDER_IDENTITY,
            )
        }
        if (snapshot.verdict != PaperOrderReconciliationVerdict.SINGLE_UNRESOLVED &&
            snapshot.verdict != PaperOrderReconciliationVerdict.MULTIPLE_UNRESOLVED_PAPER_ORDERS
        ) {
            return PaperOrderLifecycleLookupResult.Blocked(
                snapshot.verdict,
                snapshot.issues + PaperOrderReconciliationIssue.SELECTED_ORDER_NOT_ELIGIBLE,
            )
        }
        val candidate = snapshot.candidates.singleOrNull {
            it.submitAttemptId == selectedTarget.submitAttemptId
        }
        val persistedTarget = candidate?.manualLookupTarget
            ?: return PaperOrderLifecycleLookupResult.Blocked(
                snapshot.verdict,
                snapshot.issues + PaperOrderReconciliationIssue.SELECTED_ORDER_NOT_ELIGIBLE,
            )
        if (persistedTarget != selectedTarget) {
            return PaperOrderLifecycleLookupResult.Blocked(
                snapshot.verdict,
                snapshot.issues + PaperOrderReconciliationIssue.SELECTED_ORDER_IDENTITY_CHANGED,
            )
        }
        return PaperOrderLifecycleLookupResult.Exact(persistedTarget)
    }

    private fun lifecycleIssues(
        entity: PaperOrderReconciliationEntity,
        history: List<PaperOrderLifecycleObservationEntity>,
    ): Set<PaperOrderReconciliationIssue> {
        val issues = linkedSetOf<PaperOrderReconciliationIssue>()
        if (history.any {
                it.submitAttemptId != entity.submitAttemptId ||
                    it.alpacaOrderId != entity.alpacaOrderId ||
                    it.submitAuditEntryId != entity.submitResultAuditEntryId
            }
        ) {
            issues += PaperOrderReconciliationIssue.LIFECYCLE_IDENTITY_MISMATCH
        }
        if (history.asSequence()
                .filter { it.source == PaperOrderStatusFetchEvidence.SOURCE }
                .any { it.toCoherentSnapshot(entity) == null }
        ) {
            issues += PaperOrderReconciliationIssue.LIFECYCLE_CONTRADICTION
        }
        val terminals = history.filter(PaperOrderLifecycleObservationEntity::terminal)
        if (terminals.map(PaperOrderLifecycleObservationEntity::rawStatus).distinct().size > 1) {
            issues += PaperOrderReconciliationIssue.LIFECYCLE_CONTRADICTION
        }
        val terminalIndex = history.indexOfFirst(PaperOrderLifecycleObservationEntity::terminal)
        if (terminalIndex >= 0) {
            val terminal = history[terminalIndex]
            if (history.drop(terminalIndex + 1).any {
                    it.rawStatus != terminal.rawStatus ||
                        it.filledQuantity != terminal.filledQuantity ||
                        it.filledAveragePriceUsd != terminal.filledAveragePriceUsd ||
                        it.filledAtIso != terminal.filledAtIso
                }
            ) {
                issues += PaperOrderReconciliationIssue.LIFECYCLE_CONTRADICTION
            }
        }
        val filled = history.mapNotNull(PaperOrderLifecycleObservationEntity::filledQuantity)
        if (filled.zipWithNext().any { (before, after) -> after < before }) {
            issues += PaperOrderReconciliationIssue.LIFECYCLE_CONTRADICTION
        }
        return issues
    }

    private fun PaperOrderStatusSnapshot.matches(target: PaperOrderLifecycleLookupTarget): Boolean =
        orderId == target.orderId &&
            (target.clientOrderId == null || clientOrderId == target.clientOrderId) &&
            symbol == target.symbol &&
            side == target.side &&
            quantity == target.quantity &&
            orderType == target.orderType &&
            timeInForce == target.timeInForce

    private fun PaperOrderLifecycleObservationEntity.toDomain(): PaperOrderLifecycleObservation =
        PaperOrderLifecycleObservation(
            id,
            status,
            rawStatus,
            observedAtEpochMillis,
            terminal,
            filledQuantity,
            filledAveragePriceUsd,
            filledAtIso,
            source,
            httpStatusCode,
        )

    private fun PaperOrderLifecycleObservationEntity.toSnapshot(
        identity: PaperOrderReconciliationEntity,
    ): PaperOrderStatusSnapshot? {
        val lifecycle = runCatching { PaperOrderLifecycleStatus.valueOf(status) }.getOrNull()
            ?: return null
        return runCatching {
            PaperOrderStatusSnapshot(
                orderId = alpacaOrderId,
                clientOrderId = identity.clientOrderId ?: return null,
                symbol = identity.symbol ?: return null,
                side = identity.side ?: return null,
                quantity = identity.quantity ?: return null,
                orderType = identity.orderType ?: return null,
                timeInForce = identity.timeInForce ?: return null,
                status = lifecycle,
                rawStatus = rawStatus,
                filledQuantity = filledQuantity ?: return null,
                filledAveragePriceUsd = filledAveragePriceUsd,
                filledAtIso = filledAtIso,
            )
        }.getOrNull()
    }

    private fun PaperOrderLifecycleObservationEntity.matchesLifecycleIdentity(
        identity: PaperOrderReconciliationEntity,
    ): Boolean = submitAttemptId == identity.submitAttemptId &&
        alpacaOrderId == identity.alpacaOrderId &&
        submitAuditEntryId == identity.submitResultAuditEntryId

    private fun PaperOrderLifecycleObservationEntity.toCoherentSnapshot(
        identity: PaperOrderReconciliationEntity,
    ): PaperOrderStatusSnapshot? {
        val snapshot = toSnapshot(identity) ?: return null
        return snapshot.takeIf {
            source == PaperOrderStatusFetchEvidence.SOURCE &&
                httpStatusCode != null &&
                httpStatusCode in 200..299 &&
                status == snapshot.status.name &&
                PaperOrderLifecycleStatus.fromWireValue(rawStatus) == snapshot.status &&
                terminal == snapshot.terminalForNewPreparation
        }
    }

    private fun PaperOrderLifecycleObservationEntity.validatedSnapshot(
        identity: PaperOrderReconciliationEntity,
    ): PaperOrderStatusSnapshot? = takeIf { matchesLifecycleIdentity(identity) }
        ?.toCoherentSnapshot(identity)

    private fun PaperOrderSubmitAuditEntity.eventKeyMatches(): Boolean =
        eventKey == "$submitAttemptId:$status"

    private fun List<PaperOrderSubmitAuditEntity>.haveConsistentIdentity(): Boolean =
        sameValue { it.previewId } &&
            sameValue { it.linkedClientDryRunId } &&
            sameValue { it.clientOrderId } &&
            sameValue { it.symbol } &&
            sameValue { it.side } &&
            sameValue { it.quantity } &&
            sameValue { it.orderType } &&
            sameValue { it.timeInForce } &&
            sameValue { it.limitPriceUsd }

    private fun <T> List<PaperOrderSubmitAuditEntity>.sameValue(
        value: (PaperOrderSubmitAuditEntity) -> T,
    ): Boolean = map(value).distinct().size <= 1

    private fun String?.toIssues(): Set<PaperOrderReconciliationIssue> =
        this?.split(',')
            ?.mapNotNull { raw ->
                runCatching { PaperOrderReconciliationIssue.valueOf(raw) }.getOrNull()
            }
            ?.toSet()
            .orEmpty()

    private fun Set<PaperOrderReconciliationIssue>.toDiagnostic(): String? =
        takeIf(Set<PaperOrderReconciliationIssue>::isNotEmpty)
            ?.map(PaperOrderReconciliationIssue::name)
            ?.sorted()
            ?.joinToString(",")

    private fun lifecycleObservationKey(
        target: PaperOrderLifecycleLookupTarget,
        observedAtEpochMillis: Long,
        priorCount: Int,
        rawStatus: String,
    ): String = "GET:" + target.submitAttemptId + ":" +
        target.submitResultAuditEntryId + ":$observedAtEpochMillis:$priorCount:$rawStatus"

    companion object {
        private const val MAPPING_EXACT: String = "EXACT"
        private const val MAPPING_AMBIGUOUS: String = "AMBIGUOUS"
        private const val ATTEMPT_STARTED: String = "ATTEMPT_STARTED"
        private const val SUBMITTED: String = "SUBMITTED"
        private const val REJECTED: String = "REJECTED"
        private const val FAILED: String = "FAILED"
        private const val BLOCKED: String = "BLOCKED"
        private const val LOCAL_SUBMIT_AUDIT: String = "LOCAL_SUBMIT_AUDIT"
        private val KNOWN_LOCAL_RESULTS: Set<String> =
            setOf(SUBMITTED, REJECTED, FAILED, BLOCKED)
        private val GLOBAL_DUPLICATE_ISSUES: Set<PaperOrderReconciliationIssue> = setOf(
            PaperOrderReconciliationIssue.DUPLICATE_ALPACA_ORDER_ID,
            PaperOrderReconciliationIssue.DUPLICATE_CLIENT_ORDER_ID,
        )
    }
}
