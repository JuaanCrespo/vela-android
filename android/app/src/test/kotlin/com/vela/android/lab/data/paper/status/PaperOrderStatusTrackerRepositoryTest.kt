package com.vela.android.lab.data.paper.status

import com.vela.android.lab.data.paper.submit.PaperOrderSubmitAuditRepository
import com.vela.android.lab.data.paper.submit.SubmitFakeAuditDao
import com.vela.android.lab.db.room.dao.PaperOrderReconciliationDao
import com.vela.android.lab.db.room.entities.PaperOrderLifecycleObservationEntity
import com.vela.android.lab.db.room.entities.PaperOrderReconciliationEntity
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperOrderStatusTrackerRepositoryTest {

    @Test
    fun exactIdentityAndLocalSubmittedEvidenceSurviveIdempotentRestart() = runTest {
        val audit = SubmitFakeAuditDao().apply {
            addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
        }
        val persistence = FakeReconciliationDao()
        val first = repository(audit, persistence).consolidateFromAudit()

        assertEquals(PaperOrderReconciliationVerdict.SINGLE_UNRESOLVED, first.verdict)
        val candidate = first.candidates.single()
        assertEquals(1L, candidate.attemptStartedAuditEntryId)
        assertEquals(2L, candidate.submitResultAuditEntryId)
        assertEquals(VALID_ORDER_ID, candidate.orderId)
        assertEquals("client-1", candidate.clientOrderId)
        assertEquals(listOf("SUBMITTED"), candidate.lifecycleHistory.map { it.status })
        assertTrue(candidate.mappingExact)

        val restarted = repository(audit, persistence).consolidateFromAudit()
        assertEquals(first, restarted)
        assertEquals(1, persistence.lifecycleObservationCount())
        assertTrue(repository(audit, persistence).lookupTarget() is
            PaperOrderLifecycleLookupResult.Exact)
    }

    @Test
    fun multipleUnresolvedAttemptsAreRepresentedAndNeverAutoSelected() = runTest {
        val audit = SubmitFakeAuditDao().apply {
            addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
            addSubmitted("attempt-2", OTHER_ORDER_ID, "client-2", 3L)
        }
        val repo = repository(audit, FakeReconciliationDao())
        val state = repo.consolidateFromAudit()

        assertEquals(
            PaperOrderReconciliationVerdict.MULTIPLE_UNRESOLVED_PAPER_ORDERS,
            state.verdict,
        )
        assertEquals(
            setOf("attempt-1", "attempt-2"),
            state.candidates.map { it.submitAttemptId }.toSet(),
        )
        assertTrue(repo.lookupTarget() is PaperOrderLifecycleLookupResult.Blocked)
        assertFalse(state.preparationAllowed)
    }

    @Test
    fun duplicateOrderOrClientIdentitiesFailClosed() = runTest {
        for (duplicateOrder in listOf(true, false)) {
            val audit = SubmitFakeAuditDao().apply {
                addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
                addSubmitted(
                    "attempt-2",
                    if (duplicateOrder) VALID_ORDER_ID else OTHER_ORDER_ID,
                    if (duplicateOrder) "client-2" else "client-1",
                    3L,
                )
            }
            val state = repository(audit, FakeReconciliationDao()).consolidateFromAudit()
            assertEquals(PaperOrderReconciliationVerdict.AMBIGUOUS, state.verdict)
            assertTrue(
                state.issues.contains(
                    if (duplicateOrder) {
                        PaperOrderReconciliationIssue.DUPLICATE_ALPACA_ORDER_ID
                    } else {
                        PaperOrderReconciliationIssue.DUPLICATE_CLIENT_ORDER_ID
                    },
                ),
            )
            assertFalse(state.preparationAllowed)
        }
    }

    @Test
    fun incompleteAndFailedLocalEvidenceRemainAmbiguous() = runTest {
        for (status in listOf("ATTEMPT_STARTED", "FAILED")) {
            val audit = SubmitFakeAuditDao()
            audit.rows += auditRow(
                1L,
                "attempt-$status",
                "ATTEMPT_STARTED",
                null,
                "client-$status",
            )
            if (status == "FAILED") {
                audit.rows += auditRow(
                    2L,
                    "attempt-$status",
                    status,
                    null,
                    "client-$status",
                )
            }
            val state = repository(audit, FakeReconciliationDao()).consolidateFromAudit()
            assertEquals(PaperOrderReconciliationVerdict.AMBIGUOUS, state.verdict)
            assertFalse(state.preparationAllowed)
        }
    }

    @Test
    fun terminalGetAndResetAcknowledgementSurviveRestart() = runTest {
        val audit = SubmitFakeAuditDao().apply {
            addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
        }
        val persistence = FakeReconciliationDao()
        val repo = repository(audit, persistence)
        val target = (repo.lookupTarget() as PaperOrderLifecycleLookupResult.Exact).target

        val persisted = repo.persistLifecycle(
            target,
            filledSnapshot(),
            PaperOrderStatusFetchEvidence(200),
            1_000L,
        ) as PaperOrderLifecyclePersistResult.Persisted

        assertEquals(
            PaperOrderReconciliationVerdict.TERMINAL_RESET_REQUIRED,
            persisted.snapshot.verdict,
        )
        assertEquals(
            listOf("SUBMITTED", "FILLED"),
            persisted.snapshot.candidates.single().lifecycleHistory.map { it.status },
        )
        assertEquals(2, persistence.lifecycleObservationCount())

        val restarted = repository(audit, persistence)
        val restored = restarted.consolidateFromAudit()
        assertEquals(
            PaperOrderLifecycleStatus.FILLED,
            restored.candidates.single().latestLifecycleSnapshot?.status,
        )
        assertEquals(listOf("attempt-1"), restored.resetEligibleAttemptIds)

        val acknowledged = restarted.acknowledgeTerminalReset("attempt-1", 1_100L)
            as PaperOrderResetAcknowledgementResult.Acknowledged
        assertEquals(PaperOrderReconciliationVerdict.READY, acknowledged.snapshot.verdict)
        assertTrue(acknowledged.snapshot.preparationAllowed)
        assertEquals(2, persistence.lifecycleObservationCount())

        val afterSecondRestart = repository(audit, persistence).consolidateFromAudit()
        assertEquals(PaperOrderReconciliationVerdict.READY, afterSecondRestart.verdict)
        assertEquals(
            1_100L,
            afterSecondRestart.candidates.single().resetAcknowledgedAtEpochMillis,
        )
    }

    @Test
    fun lifecycleAuditRowMismatchIsAmbiguousAndNeverResetEligible() = runTest {
        val audit = SubmitFakeAuditDao().apply {
            addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
        }
        val persistence = FakeReconciliationDao()
        val repo = repository(audit, persistence)
        repo.consolidateFromAudit()
        persistence.insertLifecycleObservation(
            persistedGetObservation(
                observationKey = "wrong-audit-row",
                submitAuditEntryId = 99L,
            ),
        )

        val state = repo.consolidateFromAudit()

        assertEquals(PaperOrderReconciliationVerdict.AMBIGUOUS, state.verdict)
        assertTrue(
            state.issues.contains(
                PaperOrderReconciliationIssue.LIFECYCLE_IDENTITY_MISMATCH,
            ),
        )
        assertTrue(state.resetEligibleAttemptIds.isEmpty())
        assertFalse(state.candidates.single().terminal)
        assertEquals(null, state.candidates.single().latestLifecycleSnapshot)
    }

    @Test
    fun invalidOrInconsistentFilledObservationIsAmbiguousAndNeverResetEligible() = runTest {
        val cases = listOf(
            persistedGetObservation("invalid-filled-quantity", filledQuantity = 0.0),
            persistedGetObservation("invalid-filled-raw-status", rawStatus = "new"),
            persistedGetObservation("invalid-filled-terminal", terminal = false),
            persistedGetObservation("invalid-filled-at", filledAtIso = "tomorrow"),
            persistedGetObservation("invalid-filled-http-null", httpStatusCode = null),
            persistedGetObservation("invalid-filled-http-error", httpStatusCode = 500),
            persistedGetObservation("invalid-filled-overfill", filledQuantity = 2.0),
        )
        cases.forEach { observation ->
            val audit = SubmitFakeAuditDao().apply {
                addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
            }
            val persistence = FakeReconciliationDao()
            val repo = repository(audit, persistence)
            repo.consolidateFromAudit()
            persistence.insertLifecycleObservation(observation)

            val state = repo.consolidateFromAudit()

            assertEquals(PaperOrderReconciliationVerdict.AMBIGUOUS, state.verdict)
            assertTrue(
                state.issues.contains(PaperOrderReconciliationIssue.LIFECYCLE_CONTRADICTION),
            )
            assertTrue(state.resetEligibleAttemptIds.isEmpty())
            assertFalse(state.candidates.single().terminal)
            assertEquals(null, state.candidates.single().latestLifecycleSnapshot)
        }
    }

    @Test
    fun identityMismatchAndPersistenceFailureNeverUnlock() = runTest {
        val audit = SubmitFakeAuditDao().apply {
            addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
        }
        val persistence = FakeReconciliationDao()
        val repo = repository(audit, persistence)
        val target = (repo.lookupTarget() as PaperOrderLifecycleLookupResult.Exact).target

        val mismatch = repo.persistLifecycle(
            target,
            filledSnapshot().copy(clientOrderId = "another-client"),
            PaperOrderStatusFetchEvidence(200),
            1_000L,
        ) as PaperOrderLifecyclePersistResult.Blocked
        assertTrue(mismatch.issues.contains(
            PaperOrderReconciliationIssue.RESPONSE_IDENTITY_MISMATCH,
        ))
        assertEquals(1, persistence.lifecycleObservationCount())

        persistence.failAppend = true
        val failed = repo.persistLifecycle(
            target,
            filledSnapshot(),
            PaperOrderStatusFetchEvidence(200),
            1_001L,
        ) as PaperOrderLifecyclePersistResult.Blocked
        assertTrue(failed.issues.contains(PaperOrderReconciliationIssue.PERSISTENCE_FAILED))
        assertEquals(PaperOrderReconciliationVerdict.SINGLE_UNRESOLVED, failed.snapshot.verdict)
        assertEquals(1, persistence.lifecycleObservationCount())
    }

    @Test
    fun contradictoryLifecycleRemainsAppendOnlyAndBlocksAfterRestart() = runTest {
        val audit = SubmitFakeAuditDao().apply {
            addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
        }
        val persistence = FakeReconciliationDao()
        val repo = repository(audit, persistence)
        val target = (repo.lookupTarget() as PaperOrderLifecycleLookupResult.Exact).target
        repo.persistLifecycle(
            target,
            filledSnapshot(),
            PaperOrderStatusFetchEvidence(200),
            1_000L,
        )
        persistence.insertLifecycleObservation(
            PaperOrderLifecycleObservationEntity(
                observationKey = "contradiction",
                submitAttemptId = "attempt-1",
                alpacaOrderId = VALID_ORDER_ID,
                status = "NEW",
                rawStatus = "new",
                observedAtEpochMillis = 1_001L,
                terminal = false,
                filledQuantity = 0.0,
                filledAveragePriceUsd = null,
                filledAtIso = null,
                source = PaperOrderStatusFetchEvidence.SOURCE,
                httpStatusCode = 200,
                submitAuditEntryId = 2L,
            ),
        )

        val restarted = repository(audit, persistence).consolidateFromAudit()
        assertEquals(PaperOrderReconciliationVerdict.AMBIGUOUS, restarted.verdict)
        assertTrue(restarted.issues.contains(
            PaperOrderReconciliationIssue.LIFECYCLE_CONTRADICTION,
        ))
        assertEquals(3, persistence.lifecycleObservationCount())
        assertFalse(restarted.preparationAllowed)
    }

    private fun repository(
        audit: SubmitFakeAuditDao,
        persistence: FakeReconciliationDao,
    ) = PaperOrderStatusTrackerRepository(PaperOrderSubmitAuditRepository(audit), persistence)

    private fun filledSnapshot() = PaperOrderStatusSnapshot(
        orderId = VALID_ORDER_ID,
        clientOrderId = "client-1",
        symbol = "SPY",
        side = "BUY",
        quantity = 1.0,
        orderType = "MARKET",
        timeInForce = "DAY",
        status = PaperOrderLifecycleStatus.FILLED,
        rawStatus = "filled",
        filledQuantity = 1.0,
        filledAveragePriceUsd = 773.49,
        filledAtIso = "2026-08-07T19:31:02Z",
    )

    private fun persistedGetObservation(
        observationKey: String,
        submitAuditEntryId: Long = 2L,
        filledQuantity: Double = 1.0,
        rawStatus: String = "filled",
        terminal: Boolean = true,
        filledAtIso: String = "2026-08-07T19:31:02Z",
        httpStatusCode: Int? = 200,
    ) = PaperOrderLifecycleObservationEntity(
        observationKey = observationKey,
        submitAttemptId = "attempt-1",
        alpacaOrderId = VALID_ORDER_ID,
        status = "FILLED",
        rawStatus = rawStatus,
        observedAtEpochMillis = 1_000L,
        terminal = terminal,
        filledQuantity = filledQuantity,
        filledAveragePriceUsd = 773.49,
        filledAtIso = filledAtIso,
        source = PaperOrderStatusFetchEvidence.SOURCE,
        httpStatusCode = httpStatusCode,
        submitAuditEntryId = submitAuditEntryId,
    )

    companion object {
        private const val VALID_ORDER_ID = "4b60549d-6dab-47d8-93eb-382ed1eed108"
        private const val OTHER_ORDER_ID = "7d9b45d4-98fb-4f39-a9f0-22c58bdeca31"
    }
}

private fun SubmitFakeAuditDao.addSubmitted(
    attemptId: String,
    orderId: String,
    clientOrderId: String,
    firstId: Long,
) {
    rows += auditRow(firstId, attemptId, "ATTEMPT_STARTED", null, clientOrderId)
    rows += auditRow(firstId + 1L, attemptId, "SUBMITTED", orderId, clientOrderId)
}

private fun auditRow(
    id: Long,
    attemptId: String,
    status: String,
    orderId: String?,
    clientOrderId: String,
): PaperOrderSubmitAuditEntity = PaperOrderSubmitAuditEntity(
    id = id,
    eventKey = "$attemptId:$status",
    submitAttemptId = attemptId,
    previewId = "preview-$attemptId",
    linkedClientDryRunId = "dry-$attemptId",
    clientOrderId = clientOrderId,
    symbol = "SPY",
    side = "BUY",
    quantity = 1.0,
    orderType = "MARKET",
    timeInForce = "DAY",
    limitPriceUsd = null,
    status = status,
    alpacaOrderId = orderId,
    submittedAtEpochMillis = id * 100L,
    safeErrorMessage = null,
    priceSource = "LIVE_QUOTE_MID",
    priceFreshness = "FRESH",
    marketOpen = true,
    confirmationTokenId = "token-$attemptId",
)

private class FakeReconciliationDao : PaperOrderReconciliationDao {
    private val reconciliations = linkedMapOf<String, PaperOrderReconciliationEntity>()
    private val observations = mutableListOf<PaperOrderLifecycleObservationEntity>()
    private var nextObservationId = 1L
    var failAppend: Boolean = false

    override suspend fun insertReconciliation(entity: PaperOrderReconciliationEntity): Long {
        check(reconciliations.putIfAbsent(entity.submitAttemptId, entity) == null)
        return 1L
    }

    override suspend fun insertReconciliationIfAbsent(
        entity: PaperOrderReconciliationEntity,
    ): Long = if (reconciliations.putIfAbsent(entity.submitAttemptId, entity) == null) 1L else -1L

    override suspend fun updateReconciliation(entity: PaperOrderReconciliationEntity): Int {
        if (!reconciliations.containsKey(entity.submitAttemptId)) return 0
        reconciliations[entity.submitAttemptId] = entity
        return 1
    }

    override suspend fun reconciliationByAttemptId(
        attemptId: String,
    ): PaperOrderReconciliationEntity? = reconciliations[attemptId]

    override suspend fun allReconciliations(): List<PaperOrderReconciliationEntity> =
        reconciliations.values.sortedBy { it.submitAttemptId }

    override suspend fun reconciliationsByOrderId(
        orderId: String,
    ): List<PaperOrderReconciliationEntity> =
        allReconciliations().filter { it.alpacaOrderId == orderId }

    override suspend fun reconciliationsByClientOrderId(
        clientOrderId: String,
    ): List<PaperOrderReconciliationEntity> =
        allReconciliations().filter { it.clientOrderId == clientOrderId }

    override suspend fun insertLifecycleObservation(
        observation: PaperOrderLifecycleObservationEntity,
    ): Long {
        check(observations.none { it.observationKey == observation.observationKey })
        val id = nextObservationId++
        observations += observation.copy(id = id)
        return id
    }

    override suspend fun lifecycleByAttemptId(
        attemptId: String,
    ): List<PaperOrderLifecycleObservationEntity> =
        observations.filter { it.submitAttemptId == attemptId }.sortedBy { it.id }

    override suspend fun latestLifecycleByAttemptId(
        attemptId: String,
    ): PaperOrderLifecycleObservationEntity? =
        lifecycleByAttemptId(attemptId).maxByOrNull { it.id }

    override suspend fun lifecycleObservationCount(): Int = observations.size

    override suspend fun acknowledgeReset(
        attemptId: String,
        acknowledgedAtEpochMillis: Long,
    ): Int {
        val row = reconciliations[attemptId] ?: return 0
        if (!row.terminal || row.mappingStatus != "EXACT" ||
            row.resetAcknowledgedAtEpochMillis != null
        ) return 0
        reconciliations[attemptId] = row.copy(
            resetAcknowledgedAtEpochMillis = acknowledgedAtEpochMillis,
        )
        return 1
    }

    override suspend fun appendLifecycleObservation(
        observation: PaperOrderLifecycleObservationEntity,
        updatedReconciliation: PaperOrderReconciliationEntity,
    ) {
        if (failAppend) error("simulated persistence failure")
        check(observation.submitAttemptId == updatedReconciliation.submitAttemptId)
        check(observation.alpacaOrderId == updatedReconciliation.alpacaOrderId)
        insertLifecycleObservation(observation)
        check(updateReconciliation(updatedReconciliation) == 1)
    }
}
