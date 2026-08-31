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
        val selectedTarget = restarted.exactUnresolvedCandidates.single().manualLookupTarget!!
        assertTrue(
            repository(audit, persistence).lookupTarget(selectedTarget) is
                PaperOrderLifecycleLookupResult.Exact,
        )
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
        assertEquals(2, state.unresolvedCount)
        assertEquals(
            listOf("attempt-1", "attempt-2"),
            state.exactUnresolvedCandidates.map(ReconciledPaperOrder::submitAttemptId),
        )
        state.exactUnresolvedCandidates.forEach { candidate ->
            assertTrue(
                repo.lookupTarget(candidate.manualLookupTarget!!) is
                    PaperOrderLifecycleLookupResult.Exact,
            )
        }
        assertFalse(state.preparationAllowed)
    }

    @Test
    fun selectedIdentityIsRevalidatedAsTheSamePersistedCandidate() = runTest {
        val audit = SubmitFakeAuditDao().apply {
            addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
            addSubmitted("attempt-2", OTHER_ORDER_ID, "client-2", 3L)
        }
        val repo = repository(audit, FakeReconciliationDao())
        val state = repo.consolidateFromAudit()
        val selectedA = state.exactUnresolvedCandidates.first {
            it.submitAttemptId == "attempt-1"
        }.manualLookupTarget!!

        assertTrue(repo.lookupTarget(selectedA) is PaperOrderLifecycleLookupResult.Exact)

        val changedIdentity = repo.lookupTarget(selectedA.copy(orderId = OTHER_ORDER_ID))
            as PaperOrderLifecycleLookupResult.Blocked
        assertTrue(
            changedIdentity.issues.contains(
                PaperOrderReconciliationIssue.SELECTED_ORDER_IDENTITY_CHANGED,
            ),
        )

        val unknownAttempt = repo.lookupTarget(selectedA.copy(submitAttemptId = "attempt-missing"))
            as PaperOrderLifecycleLookupResult.Blocked
        assertTrue(
            unknownAttempt.issues.contains(
                PaperOrderReconciliationIssue.SELECTED_ORDER_NOT_ELIGIBLE,
            ),
        )
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
            assertTrue(state.exactUnresolvedCandidates.isEmpty())
        }
    }

    @Test
    fun persistedOrphanDuplicateOrderOrClientIdentitiesFailClosed() = runTest {
        val cases = listOf(
            Triple(
                persistedExactIdentity("attempt-1", VALID_ORDER_ID, "client-1", 1L),
                persistedExactIdentity("attempt-2", VALID_ORDER_ID, "client-2", 3L),
                PaperOrderReconciliationIssue.DUPLICATE_ALPACA_ORDER_ID,
            ),
            Triple(
                persistedExactIdentity("attempt-1", VALID_ORDER_ID, "shared-client", 1L),
                persistedExactIdentity("attempt-2", OTHER_ORDER_ID, "shared-client", 3L),
                PaperOrderReconciliationIssue.DUPLICATE_CLIENT_ORDER_ID,
            ),
        )

        for ((first, second, expectedIssue) in cases) {
            val persistence = FakeReconciliationDao()
            persistence.insertReconciliation(first)
            persistence.insertReconciliation(second)

            val state = repository(SubmitFakeAuditDao(), persistence).consolidateFromAudit()

            assertEquals(PaperOrderReconciliationVerdict.AMBIGUOUS, state.verdict)
            assertEquals(2, state.ambiguousCount)
            assertTrue(state.issues.contains(expectedIssue))
            assertTrue(state.exactUnresolvedCandidates.isEmpty())
            assertFalse(state.preparationAllowed)
        }
    }

    @Test
    fun stalePersistedDuplicateDiagnosticsAreRecomputedFromCurrentIdentities() = runTest {
        val persistence = FakeReconciliationDao()
        persistence.insertReconciliation(
            persistedExactIdentity("attempt-1", VALID_ORDER_ID, "client-1", 1L).copy(
                mappingStatus = "AMBIGUOUS",
                mappingDiagnostic = "DUPLICATE_CLIENT_ORDER_ID",
            ),
        )

        val state = repository(SubmitFakeAuditDao(), persistence).consolidateFromAudit()

        assertEquals(PaperOrderReconciliationVerdict.SINGLE_UNRESOLVED, state.verdict)
        assertEquals(1, state.exactUnresolvedCandidates.size)
        assertFalse(state.issues.contains(PaperOrderReconciliationIssue.DUPLICATE_CLIENT_ORDER_ID))
        assertTrue(state.exactUnresolvedCandidates.single().manualLookupTarget != null)
    }

    @Test
    fun invalidSubmittedIdentityAndLocalBlockedAttemptAreNotSelectable() = runTest {
        val invalidAudit = SubmitFakeAuditDao().apply {
            rows += auditRow(1L, "attempt-invalid", "ATTEMPT_STARTED", null, "client-invalid")
                .copy(side = "HOLD")
            rows += auditRow(
                2L,
                "attempt-invalid",
                "SUBMITTED",
                VALID_ORDER_ID,
                "client-invalid",
            ).copy(side = "HOLD")
        }
        val invalidState = repository(invalidAudit, FakeReconciliationDao())
            .consolidateFromAudit()
        assertEquals(PaperOrderReconciliationVerdict.AMBIGUOUS, invalidState.verdict)
        assertTrue(
            invalidState.issues.contains(PaperOrderReconciliationIssue.INVALID_ORDER_IDENTITY),
        )
        assertTrue(invalidState.exactUnresolvedCandidates.isEmpty())

        val blockedAudit = SubmitFakeAuditDao().apply {
            rows += auditRow(1L, "attempt-blocked", "ATTEMPT_STARTED", null, "client-blocked")
            rows += auditRow(2L, "attempt-blocked", "BLOCKED", null, "client-blocked")
        }
        val blockedRepo = repository(blockedAudit, FakeReconciliationDao())
        val blockedState = blockedRepo.consolidateFromAudit()
        assertTrue(blockedState.exactUnresolvedCandidates.isEmpty())
        val inventedTarget = PaperOrderLifecycleLookupTarget(
            submitAttemptId = "attempt-blocked",
            attemptStartedAuditEntryId = 1L,
            submitResultAuditEntryId = 2L,
            submittedAtEpochMillis = 200L,
            orderId = VALID_ORDER_ID,
            clientOrderId = "client-blocked",
            symbol = "SPY",
            side = "BUY",
            quantity = 1.0,
            orderType = "MARKET",
            timeInForce = "DAY",
        )
        val lookup = blockedRepo.lookupTarget(inventedTarget)
            as PaperOrderLifecycleLookupResult.Blocked
        assertTrue(
            lookup.issues.contains(PaperOrderReconciliationIssue.SELECTED_ORDER_NOT_ELIGIBLE),
        )
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
        val state = repo.consolidateFromAudit()
        val selected = state.exactUnresolvedCandidates.single().manualLookupTarget!!
        val target = (repo.lookupTarget(selected) as PaperOrderLifecycleLookupResult.Exact).target

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

        val acknowledged = restarted.acknowledgeAllTerminalResets(1_100L)
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
    fun terminalResolutionRemovesOnlyThatOrderAndPartialStateSurvivesRestart() = runTest {
        val audit = SubmitFakeAuditDao().apply {
            addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
            addSubmitted("attempt-2", OTHER_ORDER_ID, "client-2", 3L)
        }
        val persistence = FakeReconciliationDao()
        val repo = repository(audit, persistence)
        val initial = repo.consolidateFromAudit()
        val targetA = initial.exactUnresolvedCandidates.first {
            it.submitAttemptId == "attempt-1"
        }.manualLookupTarget!!

        val afterA = repo.persistLifecycle(
            targetA,
            filledSnapshot(targetA),
            PaperOrderStatusFetchEvidence(200),
            1_000L,
        ) as PaperOrderLifecyclePersistResult.Persisted

        assertEquals(PaperOrderReconciliationVerdict.SINGLE_UNRESOLVED, afterA.snapshot.verdict)
        assertEquals(1, afterA.snapshot.resolvedCount)
        assertEquals(1, afterA.snapshot.unresolvedCount)
        assertEquals(
            listOf("attempt-2"),
            afterA.snapshot.exactUnresolvedCandidates.map(ReconciledPaperOrder::submitAttemptId),
        )
        assertTrue(repo.lookupTarget(targetA) is PaperOrderLifecycleLookupResult.Blocked)

        val restarted = repository(audit, persistence)
        val restored = restarted.consolidateFromAudit()
        assertEquals(1, restored.resolvedCount)
        assertEquals(1, restored.unresolvedCount)
        assertEquals(
            PaperOrderLifecycleStatus.FILLED,
            restored.candidates.first { it.submitAttemptId == "attempt-1" }
                .latestLifecycleSnapshot?.status,
        )
        assertEquals(
            "attempt-2",
            restored.exactUnresolvedCandidates.single().submitAttemptId,
        )
    }

    @Test
    fun nonTerminalObservationKeepsTheSelectedOrderUnresolved() = runTest {
        val audit = SubmitFakeAuditDao().apply {
            addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
        }
        val persistence = FakeReconciliationDao()
        val repo = repository(audit, persistence)
        val initial = repo.consolidateFromAudit()
        val target = initial.exactUnresolvedCandidates.single().manualLookupTarget!!

        val persisted = repo.persistLifecycle(
            target,
            nonTerminalSnapshot(target),
            PaperOrderStatusFetchEvidence(200),
            1_000L,
        ) as PaperOrderLifecyclePersistResult.Persisted

        assertEquals(PaperOrderReconciliationVerdict.SINGLE_UNRESOLVED, persisted.snapshot.verdict)
        assertEquals(0, persisted.snapshot.resolvedCount)
        assertEquals(1, persisted.snapshot.unresolvedCount)
        assertEquals(
            listOf("SUBMITTED", "NEW"),
            persisted.snapshot.candidates.single().lifecycleHistory.map { it.status },
        )
        assertTrue(repo.lookupTarget(target) is PaperOrderLifecycleLookupResult.Exact)
        assertFalse(persisted.snapshot.preparationAllowed)
    }

    @Test
    fun allTerminalOrdersRequireOneManualBatchResetAndPreserveHistory() = runTest {
        val audit = SubmitFakeAuditDao().apply {
            addSubmitted("attempt-1", VALID_ORDER_ID, "client-1", 1L)
            addSubmitted("attempt-2", OTHER_ORDER_ID, "client-2", 3L)
        }
        val persistence = FakeReconciliationDao()
        val repo = repository(audit, persistence)
        val initial = repo.consolidateFromAudit()
        val targetA = initial.exactUnresolvedCandidates.first {
            it.submitAttemptId == "attempt-1"
        }.manualLookupTarget!!
        val targetB = initial.exactUnresolvedCandidates.first {
            it.submitAttemptId == "attempt-2"
        }.manualLookupTarget!!

        repo.persistLifecycle(
            targetA,
            filledSnapshot(targetA),
            PaperOrderStatusFetchEvidence(200),
            1_000L,
        )
        val prematureReset = repo.acknowledgeAllTerminalResets(1_050L)
        assertTrue(prematureReset is PaperOrderResetAcknowledgementResult.Blocked)

        val afterB = repo.persistLifecycle(
            targetB,
            filledSnapshot(targetB),
            PaperOrderStatusFetchEvidence(200),
            1_100L,
        ) as PaperOrderLifecyclePersistResult.Persisted
        assertEquals(0, afterB.snapshot.unresolvedCount)
        assertEquals(2, afterB.snapshot.resolvedCount)
        assertEquals(
            listOf("attempt-1", "attempt-2"),
            afterB.snapshot.resetEligibleAttemptIds,
        )
        assertEquals(
            PaperOrderReconciliationVerdict.TERMINAL_RESET_REQUIRED,
            afterB.snapshot.verdict,
        )
        assertFalse(afterB.snapshot.preparationAllowed)

        val acknowledged = repo.acknowledgeAllTerminalResets(1_200L)
            as PaperOrderResetAcknowledgementResult.Acknowledged
        assertEquals(PaperOrderReconciliationVerdict.READY, acknowledged.snapshot.verdict)
        assertTrue(acknowledged.snapshot.preparationAllowed)
        assertEquals(4, persistence.lifecycleObservationCount())
        assertTrue(
            acknowledged.snapshot.candidates.all {
                it.resetAcknowledgedAtEpochMillis == 1_200L &&
                    it.lifecycleHistory.size == 2
            },
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
        val state = repo.consolidateFromAudit()
        val selected = state.exactUnresolvedCandidates.single().manualLookupTarget!!
        val target = (repo.lookupTarget(selected) as PaperOrderLifecycleLookupResult.Exact).target

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
        val state = repo.consolidateFromAudit()
        val selected = state.exactUnresolvedCandidates.single().manualLookupTarget!!
        val target = (repo.lookupTarget(selected) as PaperOrderLifecycleLookupResult.Exact).target
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

    @Test
    fun absentClientOrderIdIsEnrichedByAValidatedResponseAndSurvivesRestart() = runTest {
        val audit = SubmitFakeAuditDao()
        val persistence = FakeReconciliationDao()
        persistence.insertReconciliation(
            persistedExactIdentity(
                attemptId = "attempt-1",
                orderId = VALID_ORDER_ID,
                clientOrderId = null,
                firstAuditId = 1L,
            ),
        )
        val repo = repository(audit, persistence)
        val initial = repo.consolidateFromAudit()
        val target = initial.exactUnresolvedCandidates.single().manualLookupTarget!!
        assertEquals(null, target.clientOrderId)

        val persisted = repo.persistLifecycle(
            target,
            filledSnapshot(target, responseClientOrderId = "response-client"),
            PaperOrderStatusFetchEvidence(200),
            1_000L,
        ) as PaperOrderLifecyclePersistResult.Persisted

        assertEquals(0, persisted.snapshot.unresolvedCount)
        assertEquals(1, persisted.snapshot.resolvedCount)
        assertEquals(
            "response-client",
            persisted.snapshot.candidates.single().clientOrderId,
        )
        val restarted = repository(audit, persistence).consolidateFromAudit()
        assertEquals(persisted.snapshot, restarted)
        assertEquals(
            "response-client",
            restarted.candidates.single().latestLifecycleSnapshot?.clientOrderId,
        )
    }

    @Test
    fun responseClientOrderIdOwnedByAnotherAttemptFailsClosedWithoutPersistence() = runTest {
        val audit = SubmitFakeAuditDao()
        val persistence = FakeReconciliationDao()
        persistence.insertReconciliation(
            persistedExactIdentity("attempt-1", VALID_ORDER_ID, null, 1L),
        )
        persistence.insertReconciliation(
            persistedExactIdentity("attempt-2", OTHER_ORDER_ID, "shared-client", 3L),
        )
        val repo = repository(audit, persistence)
        val initial = repo.consolidateFromAudit()
        val target = initial.exactUnresolvedCandidates.first {
            it.submitAttemptId == "attempt-1"
        }.manualLookupTarget!!
        val countBefore = persistence.lifecycleObservationCount()

        val blocked = repo.persistLifecycle(
            target,
            nonTerminalSnapshot(target, responseClientOrderId = "shared-client"),
            PaperOrderStatusFetchEvidence(200),
            1_000L,
        ) as PaperOrderLifecyclePersistResult.Blocked

        assertTrue(blocked.issues.contains(PaperOrderReconciliationIssue.DUPLICATE_CLIENT_ORDER_ID))
        assertEquals(countBefore, persistence.lifecycleObservationCount())
        assertEquals(null, blocked.snapshot.candidates.first {
            it.submitAttemptId == "attempt-1"
        }.clientOrderId)
        assertEquals(2, blocked.snapshot.unresolvedCount)
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

    private fun filledSnapshot(
        target: PaperOrderLifecycleLookupTarget,
        responseClientOrderId: String = target.clientOrderId ?: "response-client",
    ) =
        PaperOrderStatusSnapshot(
            orderId = target.orderId,
            clientOrderId = responseClientOrderId,
            symbol = target.symbol,
            side = target.side,
            quantity = target.quantity,
            orderType = target.orderType,
            timeInForce = target.timeInForce,
            status = PaperOrderLifecycleStatus.FILLED,
            rawStatus = "filled",
            filledQuantity = target.quantity,
            filledAveragePriceUsd = 773.49,
            filledAtIso = "2026-08-07T19:31:02Z",
        )

    private fun nonTerminalSnapshot(
        target: PaperOrderLifecycleLookupTarget,
        responseClientOrderId: String = target.clientOrderId ?: "response-client",
    ) =
        PaperOrderStatusSnapshot(
            orderId = target.orderId,
            clientOrderId = responseClientOrderId,
            symbol = target.symbol,
            side = target.side,
            quantity = target.quantity,
            orderType = target.orderType,
            timeInForce = target.timeInForce,
            status = PaperOrderLifecycleStatus.NEW,
            rawStatus = "new",
            filledQuantity = 0.0,
            filledAveragePriceUsd = null,
            filledAtIso = null,
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

private fun persistedExactIdentity(
    attemptId: String,
    orderId: String,
    clientOrderId: String?,
    firstAuditId: Long,
): PaperOrderReconciliationEntity = PaperOrderReconciliationEntity(
    submitAttemptId = attemptId,
    attemptStartedAuditEntryId = firstAuditId,
    submitResultAuditEntryId = firstAuditId + 1L,
    previewId = "preview-$attemptId",
    linkedClientDryRunId = "dry-$attemptId",
    alpacaOrderId = orderId,
    clientOrderId = clientOrderId,
    symbol = "SPY",
    side = "BUY",
    quantity = 1.0,
    orderType = "MARKET",
    timeInForce = "DAY",
    limitPriceUsd = null,
    submittedAtEpochMillis = firstAuditId * 100L,
    localSubmitResult = "SUBMITTED",
    mappingStatus = "EXACT",
    mappingDiagnostic = null,
    latestLifecycleStatus = null,
    latestLifecycleRawStatus = null,
    latestLifecycleObservedAtEpochMillis = null,
    terminal = false,
    filledQuantity = null,
    filledAveragePriceUsd = null,
    filledAtIso = null,
    lifecycleSource = null,
    lifecycleHttpStatusCode = null,
    resetAcknowledgedAtEpochMillis = null,
)

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

    override suspend fun pendingTerminalResetAttemptIds(): List<String> =
        reconciliations.values.filter {
            it.terminal &&
                it.mappingStatus == "EXACT" &&
                it.localSubmitResult == "SUBMITTED" &&
                it.resetAcknowledgedAtEpochMillis == null
        }.map(PaperOrderReconciliationEntity::submitAttemptId).sorted()

    override suspend fun acknowledgeTerminalResetsUnchecked(
        attemptIds: List<String>,
        acknowledgedAtEpochMillis: Long,
    ): Int {
        var updated = 0
        attemptIds.forEach { attemptId ->
            val row = reconciliations[attemptId] ?: return@forEach
            if (!row.terminal || row.mappingStatus != "EXACT" ||
                row.localSubmitResult != "SUBMITTED" ||
                row.resetAcknowledgedAtEpochMillis != null
            ) return@forEach
            reconciliations[attemptId] = row.copy(
                resetAcknowledgedAtEpochMillis = acknowledgedAtEpochMillis,
            )
            updated += 1
        }
        return updated
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
