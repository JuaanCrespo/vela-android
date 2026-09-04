package com.vela.android.lab.data.paper.history

import com.vela.android.lab.db.room.dao.PaperOrderHistoryDao
import com.vela.android.lab.db.room.entities.PaperOrderLifecycleObservationEntity
import com.vela.android.lab.db.room.entities.PaperOrderReconciliationEntity
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity
import com.vela.android.lab.data.paper.status.PaperOrderLifecycleStatus
import java.security.MessageDigest

/**
 * Read-only canonical history facade. It never consolidates, repairs, acknowledges, or submits.
 * Query order is based on durable database ids; timestamps are evidence and filters only.
 */
class PaperOrderHistoryRepository(
    private val dao: PaperOrderHistoryDao,
) {
    suspend fun getAll(): List<CanonicalPaperOrderHistory> {
        val auditAttempts = dao.allAuditAttemptIds()
        val orphans = dao.allReconciliationAttemptIds().filterNot(auditAttempts::contains)
        return recordsFor(auditAttempts + orphans)
    }

    suspend fun getByAttemptId(attemptId: String): CanonicalPaperOrderHistory? =
        attemptId.trim().takeIf(String::isNotEmpty)?.let { buildRecord(it) }

    /** A list is intentional: duplicate broker identities remain visible and fail-closed. */
    suspend fun getByOrderId(orderId: String): List<CanonicalPaperOrderHistory> =
        if (orderId.isBlank()) emptyList() else recordsFor(dao.attemptIdsByOrderId(orderId.trim()))

    /** A list is intentional: duplicate client identities remain visible and fail-closed. */
    suspend fun getByClientOrderId(clientOrderId: String): List<CanonicalPaperOrderHistory> =
        if (clientOrderId.isBlank()) emptyList()
        else recordsFor(dao.attemptIdsByClientOrderId(clientOrderId.trim()))

    suspend fun getByAuditRowId(auditRowId: Long): CanonicalPaperOrderHistory? =
        if (auditRowId <= 0L) null else dao.auditById(auditRowId)?.submitAttemptId?.let {
            buildRecord(it)
        }

    suspend fun getLifecycleByAttemptId(
        attemptId: String,
    ): List<CanonicalPaperLifecycleObservation> =
        getByAttemptId(attemptId)?.lifecycleObservations.orEmpty()

    suspend fun getLifecycleByOrderId(
        orderId: String,
    ): Map<String, List<CanonicalPaperLifecycleObservation>> =
        getByOrderId(orderId).associate { it.submitAttemptId to it.lifecycleObservations }

    suspend fun getTerminalOrders(): List<CanonicalPaperOrderHistory> =
        recordsFor(dao.terminalCandidateAttemptIds()).filter {
            it.currentLifecycle?.terminal == true
        }

    suspend fun getFilledOrders(): List<CanonicalPaperOrderHistory> =
        recordsFor(dao.filledCandidateAttemptIds()).filter {
            it.currentLifecycle?.status == FILLED
        }

    suspend fun getBySymbol(symbol: String): List<CanonicalPaperOrderHistory> =
        symbol.trim().uppercase().takeIf(String::isNotEmpty)
            ?.let { recordsFor(dao.attemptIdsBySymbol(it)) }
            .orEmpty()

    suspend fun getBySide(side: String): List<CanonicalPaperOrderHistory> {
        val normalized = side.trim().uppercase()
        if (normalized != BUY && normalized != SELL) return emptyList()
        return recordsFor(dao.attemptIdsBySide(normalized))
    }

    /** Uses the local submit-result timestamp and an exclusive upper bound. */
    suspend fun getWithinTimeRange(
        startInclusiveEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<CanonicalPaperOrderHistory> {
        require(startInclusiveEpochMillis >= 0L && endExclusiveEpochMillis > startInclusiveEpochMillis)
        return recordsFor(
            dao.attemptIdsWithinSubmitResultTimeRange(
                startInclusiveEpochMillis,
                endExclusiveEpochMillis,
            ),
        )
    }

    /** Newest submit ingestion first. Equal or decreasing wall-clock timestamps do not reorder it. */
    suspend fun getLatestN(limit: Int): List<CanonicalPaperOrderHistory> =
        if (limit <= 0) emptyList() else recordsFor(dao.latestAttemptIds(limit))

    private suspend fun recordsFor(attemptIds: List<String>): List<CanonicalPaperOrderHistory> =
        attemptIds.distinct().mapNotNull { buildRecord(it) }

    private suspend fun buildRecord(attemptId: String): CanonicalPaperOrderHistory? {
        val audits = dao.auditsByAttemptId(attemptId).sortedBy(PaperOrderSubmitAuditEntity::id)
        val reconciliation = dao.reconciliationByAttemptId(attemptId)
        if (audits.isEmpty() && reconciliation == null) return null

        val diagnostics = linkedSetOf<PaperHistoryIntegrityDiagnostic>()
        val starts = audits.filter { it.status == ATTEMPT_STARTED }
        val results = audits.filter { it.status != ATTEMPT_STARTED }
        if (starts.size > 1 || results.size > 1) {
            diagnostics += PaperHistoryIntegrityDiagnostic.MULTIPLE_SUBMIT_AUDIT_EVENTS
        }
        val start = starts.singleOrNull() ?: starts.lastOrNull()
        val result = results.singleOrNull() ?: results.lastOrNull()
        if (result == null || result.status != BLOCKED && start == null) {
            diagnostics += PaperHistoryIntegrityDiagnostic.INCOMPLETE_SUBMIT_AUDIT
        }
        if (audits.any { it.eventKey != "${it.submitAttemptId}:${it.status}" }) {
            diagnostics += PaperHistoryIntegrityDiagnostic.INCOMPLETE_SUBMIT_AUDIT
        }

        if (reconciliation == null) {
            diagnostics += PaperHistoryIntegrityDiagnostic.RECONCILIATION_MISSING
        } else {
            checkAuditLinkage(reconciliation, start, result, diagnostics)
        }

        val primary = result ?: start
        if (primary != null && reconciliation != null &&
            !reconciliation.matchesPrimaryAudit(primary)
        ) {
            diagnostics += PaperHistoryIntegrityDiagnostic.SOURCE_IDENTITY_MISMATCH
        }
        if (reconciliation?.mappingStatus != null &&
            reconciliation.mappingStatus != MAPPING_EXACT
        ) {
            diagnostics += PaperHistoryIntegrityDiagnostic.AMBIGUOUS_RECONCILIATION
        }

        val orderId = result?.alpacaOrderId ?: reconciliation?.alpacaOrderId
        val clientOrderId = primary?.clientOrderId ?: reconciliation?.clientOrderId
        if (orderId != null && dao.attemptIdsByOrderId(orderId).distinct().size > 1) {
            diagnostics += PaperHistoryIntegrityDiagnostic.DUPLICATE_IDENTITY
        }
        if (clientOrderId != null &&
            dao.attemptIdsByClientOrderId(clientOrderId).distinct().size > 1
        ) {
            diagnostics += PaperHistoryIntegrityDiagnostic.DUPLICATE_IDENTITY
        }

        val linkedDryRunId = primary?.linkedClientDryRunId ?: reconciliation?.linkedClientDryRunId
        val dryRun = linkedDryRunId?.let { dao.dryRunByClientId(it) }
        if (linkedDryRunId != null && dryRun == null) {
            diagnostics += PaperHistoryIntegrityDiagnostic.MISSING_DRY_RUN_EVIDENCE
        }
        classifySubmitMetadata(result, diagnostics)

        val storedLifecycle = dao.lifecycleByAttemptId(attemptId)
        if (storedLifecycle.zipWithNext().any { (before, after) -> after.id <= before.id }) {
            diagnostics += PaperHistoryIntegrityDiagnostic.NON_MONOTONIC_LIFECYCLE_SEQUENCE
        }
        val lifecycle = storedLifecycle.sortedBy(PaperOrderLifecycleObservationEntity::id)
        validateLifecycle(attemptId, orderId, result?.id, lifecycle, diagnostics)
        val canonicalLifecycle = lifecycle.toCanonical(diagnostics)
        val current = canonicalLifecycle.lastOrNull()
        if (reconciliation != null && !reconciliation.matchesProjection(current)) {
            diagnostics += PaperHistoryIntegrityDiagnostic.PROJECTION_MISMATCH
        }

        val orderedDiagnostics = diagnostics.sortedBy(PaperHistoryIntegrityDiagnostic::ordinal)
        val integrity = when {
            orderedDiagnostics.any { !it.warningOnly } -> PaperHistoryIntegrityStatus.INCONSISTENT
            orderedDiagnostics.isNotEmpty() -> PaperHistoryIntegrityStatus.VALID_WITH_WARNINGS
            else -> PaperHistoryIntegrityStatus.VALID
        }
        val ambiguous = reconciliation?.mappingStatus != MAPPING_EXACT ||
            orderedDiagnostics.any { !it.warningOnly }
        val terminal = current?.terminal == true
        val submitted = result?.status == SUBMITTED
        return CanonicalPaperOrderHistory(
            submitAttemptId = attemptId,
            orderSequenceId = audits.minOfOrNull(PaperOrderSubmitAuditEntity::id),
            linkedClientDryRunId = linkedDryRunId,
            previewId = primary?.previewId ?: reconciliation?.previewId,
            auditStartRowId = start?.id,
            auditResultRowId = result?.id,
            alpacaOrderId = orderId,
            clientOrderId = clientOrderId,
            symbol = primary?.symbol ?: reconciliation?.symbol,
            side = primary?.side ?: reconciliation?.side,
            quantity = primary?.quantity ?: reconciliation?.quantity,
            orderType = primary?.orderType ?: reconciliation?.orderType,
            timeInForce = primary?.timeInForce ?: reconciliation?.timeInForce,
            limitPriceUsd = primary?.limitPriceUsd ?: reconciliation?.limitPriceUsd,
            dryRunAuditRowId = dryRun?.id,
            decisionCreatedAtEpochMillis = dryRun?.createdAtEpochMillis,
            localSubmitResult = result?.status,
            localSubmitResultAtEpochMillis = result?.submittedAtEpochMillis,
            submitHttpStatusCode = result?.submitHttpStatusCode,
            initialAlpacaStatus = result?.initialAlpacaStatus,
            alpacaSubmittedAtIso = result?.alpacaSubmittedAtIso,
            lifecycleObservations = canonicalLifecycle,
            currentLifecycle = current,
            mappingState = reconciliation?.mappingStatus,
            resolved = submitted && terminal && !ambiguous,
            unresolved = submitted && !terminal && !ambiguous,
            ambiguous = ambiguous,
            resetAcknowledgedAtEpochMillis = reconciliation?.resetAcknowledgedAtEpochMillis,
            integrityStatus = integrity,
            integrityDiagnostics = orderedDiagnostics,
        )
    }

    private fun checkAuditLinkage(
        reconciliation: PaperOrderReconciliationEntity,
        start: PaperOrderSubmitAuditEntity?,
        result: PaperOrderSubmitAuditEntity?,
        diagnostics: MutableSet<PaperHistoryIntegrityDiagnostic>,
    ) {
        if (reconciliation.attemptStartedAuditEntryId != start?.id ||
            reconciliation.submitResultAuditEntryId != result?.id
        ) {
            diagnostics += PaperHistoryIntegrityDiagnostic.MISSING_AUDIT_LINKAGE
        }
    }

    private fun classifySubmitMetadata(
        result: PaperOrderSubmitAuditEntity?,
        diagnostics: MutableSet<PaperHistoryIntegrityDiagnostic>,
    ) {
        result ?: return
        if (result.status in setOf(SUBMITTED, REJECTED) && result.submitHttpStatusCode == null) {
            diagnostics += PaperHistoryIntegrityDiagnostic.LEGACY_SUBMIT_METADATA_UNKNOWN
        }
        if (result.status == SUBMITTED && result.submitHttpStatusCode != null) {
            if (result.initialAlpacaStatus == null) {
                diagnostics += PaperHistoryIntegrityDiagnostic.INITIAL_ALPACA_STATUS_UNKNOWN
            }
            if (result.alpacaSubmittedAtIso == null) {
                diagnostics += PaperHistoryIntegrityDiagnostic.ALPACA_SUBMITTED_AT_UNKNOWN
            }
        }
    }

    private fun validateLifecycle(
        attemptId: String,
        orderId: String?,
        submitAuditId: Long?,
        history: List<PaperOrderLifecycleObservationEntity>,
        diagnostics: MutableSet<PaperHistoryIntegrityDiagnostic>,
    ) {
        if (history.any {
                it.id <= 0L || it.submitAttemptId != attemptId ||
                    it.alpacaOrderId != orderId || it.submitAuditEntryId != submitAuditId
            }
        ) {
            diagnostics += PaperHistoryIntegrityDiagnostic.LIFECYCLE_IDENTITY_MISMATCH
        }
        if (history.any { !it.isStructurallyCoherent() }) {
            diagnostics += PaperHistoryIntegrityDiagnostic.INVALID_LIFECYCLE_EVIDENCE
        }
        var terminal: PaperOrderLifecycleObservationEntity? = null
        history.forEachIndexed { index, observation ->
            val previous = history.getOrNull(index - 1)
            if (previous?.rawStatus == PARTIALLY_FILLED && observation.rawStatus == NEW) {
                diagnostics += PaperHistoryIntegrityDiagnostic.PARTIALLY_FILLED_REGRESSION
            }
            if (previous?.filledQuantity != null && observation.filledQuantity != null &&
                observation.filledQuantity < previous.filledQuantity
            ) {
                diagnostics += PaperHistoryIntegrityDiagnostic.LIFECYCLE_CONTRADICTION
            }
            terminal?.let { firstTerminal ->
                if (!observation.terminal ||
                    observation.rawStatus != firstTerminal.rawStatus ||
                    observation.filledQuantity != firstTerminal.filledQuantity ||
                    observation.filledAveragePriceUsd != firstTerminal.filledAveragePriceUsd ||
                    observation.filledAtIso != firstTerminal.filledAtIso
                ) {
                    diagnostics += PaperHistoryIntegrityDiagnostic.LIFECYCLE_CONTRADICTION
                }
            }
            if (terminal == null && observation.terminal) terminal = observation
        }
    }

    private fun List<PaperOrderLifecycleObservationEntity>.toCanonical(
        diagnostics: MutableSet<PaperHistoryIntegrityDiagnostic>,
    ): List<CanonicalPaperLifecycleObservation> {
        var previousFingerprint: String? = null
        return map { observation ->
            val fingerprint = observation.payloadFingerprint()
            val repeated = fingerprint == previousFingerprint
            if (repeated) diagnostics += PaperHistoryIntegrityDiagnostic.REPEATED_LIFECYCLE_PAYLOAD
            previousFingerprint = fingerprint
            CanonicalPaperLifecycleObservation(
                databaseId = observation.id,
                observedAtEpochMillis = observation.observedAtEpochMillis,
                status = observation.status,
                rawStatus = observation.rawStatus,
                terminal = observation.terminal,
                filledQuantity = observation.filledQuantity,
                filledAveragePriceUsd = observation.filledAveragePriceUsd,
                filledAtIso = observation.filledAtIso,
                source = observation.source,
                httpStatusCode = observation.httpStatusCode,
                submitAuditEntryId = observation.submitAuditEntryId,
                payloadFingerprint = fingerprint,
                samePayloadAsPrevious = repeated,
            )
        }
    }

    private fun PaperOrderLifecycleObservationEntity.payloadFingerprint(): String {
        val canonical = listOf(
            submitAttemptId,
            alpacaOrderId,
            rawStatus,
            filledQuantity?.toString().orEmpty(),
            filledAveragePriceUsd?.toString().orEmpty(),
            filledAtIso.orEmpty(),
        ).joinToString("|") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun PaperOrderLifecycleObservationEntity.isStructurallyCoherent(): Boolean {
        if (status.isBlank() || rawStatus.isBlank() || source.isBlank()) return false
        if (httpStatusCode != null && httpStatusCode !in 100..599) return false
        if (source == LOCAL_SUBMIT_AUDIT) {
            return status == SUBMITTED && rawStatus == "submitted" && !terminal &&
                httpStatusCode == null
        }
        val parsedStatus = PaperOrderLifecycleStatus.fromWireValue(rawStatus)
        if (status != parsedStatus.name) return false
        if (source == ALPACA_GET && httpStatusCode !in 200..299) return false
        val expectedTerminal = rawStatus in TERMINAL_RAW_STATUSES
        return terminal == expectedTerminal
    }

    private fun PaperOrderReconciliationEntity.matchesPrimaryAudit(
        audit: PaperOrderSubmitAuditEntity,
    ): Boolean = submitAttemptId == audit.submitAttemptId &&
        previewId == audit.previewId && linkedClientDryRunId == audit.linkedClientDryRunId &&
        alpacaOrderId == audit.alpacaOrderId && clientOrderId == audit.clientOrderId &&
        symbol == audit.symbol && side == audit.side && quantity == audit.quantity &&
        orderType == audit.orderType && timeInForce == audit.timeInForce &&
        limitPriceUsd == audit.limitPriceUsd

    private fun PaperOrderReconciliationEntity.matchesProjection(
        current: CanonicalPaperLifecycleObservation?,
    ): Boolean = if (current == null) {
        latestLifecycleStatus == null && latestLifecycleRawStatus == null &&
            latestLifecycleObservedAtEpochMillis == null && !terminal && filledQuantity == null &&
            filledAveragePriceUsd == null && filledAtIso == null && lifecycleSource == null &&
            lifecycleHttpStatusCode == null
    } else {
        latestLifecycleStatus == current.status && latestLifecycleRawStatus == current.rawStatus &&
            latestLifecycleObservedAtEpochMillis == current.observedAtEpochMillis &&
            terminal == current.terminal && filledQuantity == current.filledQuantity &&
            filledAveragePriceUsd == current.filledAveragePriceUsd &&
            filledAtIso == current.filledAtIso && lifecycleSource == current.source &&
            lifecycleHttpStatusCode == current.httpStatusCode
    }

    private companion object {
        const val ATTEMPT_STARTED = "ATTEMPT_STARTED"
        const val SUBMITTED = "SUBMITTED"
        const val REJECTED = "REJECTED"
        const val BLOCKED = "BLOCKED"
        const val FILLED = "FILLED"
        const val BUY = "BUY"
        const val SELL = "SELL"
        const val MAPPING_EXACT = "EXACT"
        const val LOCAL_SUBMIT_AUDIT = "LOCAL_SUBMIT_AUDIT"
        const val ALPACA_GET = "ALPACA_PAPER_ORDER_GET"
        const val PARTIALLY_FILLED = "partially_filled"
        const val NEW = "new"
        val TERMINAL_RAW_STATUSES = setOf("filled", "canceled", "expired", "rejected")
    }
}
