@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.data.market.price.MarketPriceSnapshotProvider
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.market.tick.MarketTickBuffer
import com.vela.android.lab.data.paper.AlpacaHttpClient
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint
import com.vela.android.lab.data.paper.HttpResult
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewRepository
import com.vela.android.lab.data.paper.preflight.PreviewQueueFakeDao
import com.vela.android.lab.data.paper.status.AlpacaPaperOrderStatusEndpoint
import com.vela.android.lab.data.paper.status.AlpacaPaperOrderStatusHttpClient
import com.vela.android.lab.data.paper.status.AlpacaPaperOrderStatusReadOnlyClient
import com.vela.android.lab.data.paper.status.PaperOrderLifecycleStatus
import com.vela.android.lab.data.paper.status.PaperOrderLifecycleLookupResult
import com.vela.android.lab.data.paper.status.PaperOrderLifecyclePersistResult
import com.vela.android.lab.data.paper.status.PaperOrderReconciliationIssue
import com.vela.android.lab.data.paper.status.PaperOrderReconciliationSnapshot
import com.vela.android.lab.data.paper.status.PaperOrderReconciliationVerdict
import com.vela.android.lab.data.paper.status.PaperOrderResetAcknowledgementResult
import com.vela.android.lab.data.paper.status.PaperOrderStatusFetchEvidence
import com.vela.android.lab.data.paper.status.PaperOrderStatusSnapshot
import com.vela.android.lab.data.paper.status.PaperOrderStatusHttpResult
import com.vela.android.lab.data.paper.status.PaperOrderStatusTrackerRepository
import com.vela.android.lab.data.paper.status.PaperOrderTrackingSource
import com.vela.android.lab.data.paper.status.ReconciledPaperOrder
import com.vela.android.lab.data.paper.submit.PaperManualExecutionFeatureGate
import com.vela.android.lab.data.paper.submit.PaperManualOrderSubmitClient
import com.vela.android.lab.data.paper.submit.PaperManualSubmitExecutor
import com.vela.android.lab.data.paper.submit.PaperManualSubmitGate
import com.vela.android.lab.data.paper.submit.PaperManualSubmitTokenStore
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitAuditRepository
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitError
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitResult
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitStatus
import com.vela.android.lab.data.paper.submit.PaperSubmitHttpResult
import com.vela.android.lab.data.paper.submit.SubmitFakeAuditDao
import com.vela.android.lab.data.paper.submit.SubmitFakeHttpClient
import com.vela.android.lab.data.paper.submit.submitTestPreflight
import com.vela.android.lab.data.paper.submit.submitTestPreview
import com.vela.android.lab.data.paper.submit.submitTestRequest
import com.vela.android.lab.data.paper.submit.submitTestReadiness
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.dao.PaperOrderReconciliationDao
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import com.vela.android.lab.db.room.entities.PaperOrderLifecycleObservationEntity
import com.vela.android.lab.db.room.entities.PaperOrderReconciliationEntity
import com.vela.android.lab.state.AppState
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PaperManualSubmitViewModelTest {
    @BeforeEach fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `compile flag OFF keeps session and button blocked`() = runTest {
        val fixture = fixture(compileEnabled = false)
        fixture.vm.updateSource(
            submitTestPreflight(),
            submitTestPreview(),
            submitTestReadiness(),
        )
        fixture.vm.armSession()
        val state = fixture.vm.uiState.value
        assertFalse(state.sessionArmed)
        assertFalse(state.gateAllowed)
        assertTrue(state.gateReasons.contains(PaperOrderSubmitError.FEATURE_DISABLED))
        assertEquals(0, fixture.submitHttp.callCount)
    }

    @Test
    fun `explicit confirmation prepares token without POST then Submit sends once`() = runTest {
        val fixture = fixture(compileEnabled = true)
        val preview = submitTestPreview()
        fixture.previewRepository.savePreview(preview)
        fixture.vm.updateSource(submitTestPreflight(), preview, submitTestReadiness())

        fixture.vm.armSession()
        assertTrue(fixture.vm.uiState.value.sessionArmed)
        assertEquals(500.0, fixture.vm.uiState.value.previewPriceUsd)
        assertEquals(500.0, fixture.vm.uiState.value.finalPriceUsd)
        assertEquals(1_000L, fixture.vm.uiState.value.finalPriceAgeMillis)
        assertEquals(0.0, fixture.vm.uiState.value.finalPriceDriftPercent)
        assertEquals(0.25, fixture.vm.uiState.value.allowedPriceDriftPercent)
        assertEquals("ALLOWED", fixture.vm.uiState.value.finalPriceGateResult)
        assertFalse(fixture.vm.uiState.value.gateAllowed)
        assertTrue(
            fixture.vm.uiState.value.gateReasons.contains(
                PaperOrderSubmitError.CONFIRMATION_MISSING,
            ),
        )

        fixture.vm.onConfirmationInputChange("wrong")
        assertFalse(fixture.vm.uiState.value.gateAllowed)
        assertEquals(null, fixture.vm.uiState.value.confirmationExpiresAtEpochMillis)
        assertEquals(null, fixture.tokenStore.peek("token-vm"))
        fixture.vm.submitOnce()
        assertEquals(0, fixture.submitHttp.callCount)
        fixture.vm.onConfirmationInputChange(
            fixture.vm.uiState.value.requiredConfirmationText,
        )
        val confirmed = fixture.vm.uiState.value
        assertTrue(confirmed.sessionArmed)
        assertTrue(confirmed.gateAllowed)
        assertEquals("SUBMIT PAPER SPY BUY 1", confirmed.confirmationInput)
        assertEquals(40_000L, confirmed.confirmationExpiresAtEpochMillis)
        assertFalse(confirmed.isSubmitting)
        assertEquals(null, confirmed.lastResult)
        assertEquals(0, fixture.submitHttp.callCount)

        fixture.vm.submitOnce()
        val state = fixture.vm.uiState.value
        assertEquals(PaperOrderSubmitStatus.SUBMITTED, state.lastResult?.status)
        assertFalse(state.sessionArmed)
        assertFalse(state.gateAllowed)
        assertEquals(null, state.confirmationExpiresAtEpochMillis)
        assertEquals(1, fixture.submitHttp.callCount)

        fixture.vm.submitOnce()
        assertEquals(1, fixture.submitHttp.callCount)
    }

    @Test
    fun `second exact confirmation in one armed session keeps first token and expiry`() = runTest {
        val ids = SubmitVmIdSequence()
        val fixture = fixture(
            compileEnabled = true,
            tokenIdFactory = ids::nextTokenId,
            attemptIdFactory = ids::nextAttemptId,
            clientOrderIdFactory = ids::nextClientOrderId,
        )
        prepareAndArm(fixture)
        val exact = fixture.vm.uiState.value.requiredConfirmationText

        fixture.vm.onConfirmationInputChange(exact)
        val firstExpiry = fixture.vm.uiState.value.confirmationExpiresAtEpochMillis
        fixture.vm.onConfirmationInputChange(exact)

        assertEquals(listOf("token-1"), ids.tokenIds)
        assertEquals(listOf("attempt-1"), ids.attemptIds)
        assertEquals(listOf("vela-client-1"), ids.clientOrderIds)
        assertEquals(40_000L, firstExpiry)
        assertEquals(firstExpiry, fixture.vm.uiState.value.confirmationExpiresAtEpochMillis)
        assertNotNull(fixture.tokenStore.peek("token-1"))
        assertEquals(0, fixture.submitHttp.callCount)
    }

    @Test
    fun `clearing input after issuance cannot reopen token emission`() = runTest {
        val ids = SubmitVmIdSequence()
        val fixture = fixture(
            compileEnabled = true,
            tokenIdFactory = ids::nextTokenId,
            attemptIdFactory = ids::nextAttemptId,
            clientOrderIdFactory = ids::nextClientOrderId,
        )
        prepareAndArm(fixture)
        val exact = fixture.vm.uiState.value.requiredConfirmationText
        fixture.vm.onConfirmationInputChange(exact)
        val firstExpiry = fixture.vm.uiState.value.confirmationExpiresAtEpochMillis

        fixture.vm.onConfirmationInputChange("")
        fixture.vm.onConfirmationInputChange(exact)

        assertEquals(listOf("token-1"), ids.tokenIds)
        assertEquals(exact, fixture.vm.uiState.value.confirmationInput)
        assertEquals(firstExpiry, fixture.vm.uiState.value.confirmationExpiresAtEpochMillis)
        assertEquals(0, fixture.submitHttp.callCount)
    }

    @Test
    fun `two exact callbacks before recomposition emit only one token`() = runTest {
        val ids = SubmitVmIdSequence()
        val fixture = fixture(
            compileEnabled = true,
            tokenIdFactory = ids::nextTokenId,
            attemptIdFactory = ids::nextAttemptId,
            clientOrderIdFactory = ids::nextClientOrderId,
        )
        prepareAndArm(fixture)
        val exact = fixture.vm.uiState.value.requiredConfirmationText

        fixture.vm.onConfirmationInputChange(exact)
        fixture.vm.onConfirmationInputChange(exact)

        assertEquals(1, ids.tokenIds.size)
        assertEquals(1, ids.attemptIds.size)
        assertEquals(1, ids.clientOrderIds.size)
        assertEquals(40_000L, fixture.vm.uiState.value.confirmationExpiresAtEpochMillis)
        assertEquals(0, fixture.submitHttp.callCount)
    }

    @Test
    fun `active token is not renewed by repeated exact confirmation`() = runTest {
        var now = 10_000L
        val ids = SubmitVmIdSequence()
        val fixture = fixture(
            compileEnabled = true,
            clockMillis = { now },
            tokenIdFactory = ids::nextTokenId,
            attemptIdFactory = ids::nextAttemptId,
            clientOrderIdFactory = ids::nextClientOrderId,
        )
        prepareAndArm(fixture)
        val exact = fixture.vm.uiState.value.requiredConfirmationText
        fixture.vm.onConfirmationInputChange(exact)
        val firstExpiry = fixture.vm.uiState.value.confirmationExpiresAtEpochMillis

        now = 20_000L
        fixture.vm.onConfirmationInputChange(exact)

        assertEquals(listOf("token-1"), ids.tokenIds)
        assertEquals(firstExpiry, fixture.vm.uiState.value.confirmationExpiresAtEpochMillis)
        assertNotNull(fixture.tokenStore.peek("token-1"))
        assertEquals(0, fixture.submitHttp.callCount)
    }

    @Test
    fun `expired token is not reissued by repeated exact confirmation`() = runTest {
        var now = 10_000L
        val ids = SubmitVmIdSequence()
        val fixture = fixture(
            compileEnabled = true,
            clockMillis = { now },
            tokenIdFactory = ids::nextTokenId,
            attemptIdFactory = ids::nextAttemptId,
            clientOrderIdFactory = ids::nextClientOrderId,
        )
        prepareAndArm(fixture)
        val exact = fixture.vm.uiState.value.requiredConfirmationText
        fixture.vm.onConfirmationInputChange(exact)
        val firstExpiry = fixture.vm.uiState.value.confirmationExpiresAtEpochMillis
        assertEquals(40_000L, firstExpiry)

        now = 40_001L
        fixture.vm.onConfirmationInputChange(exact)

        assertEquals(listOf("token-1"), ids.tokenIds)
        assertEquals(firstExpiry, fixture.vm.uiState.value.confirmationExpiresAtEpochMillis)
        assertFalse(
            paperManualConfirmationUiGate(
                state = fixture.vm.uiState.value,
                nowEpochMillis = now,
            ).maySubmit,
        )
        assertEquals(0, fixture.submitHttp.callCount)
    }

    @Test
    fun `consumed token is not reissued by repeated exact confirmation`() = runTest {
        val ids = SubmitVmIdSequence()
        val fixture = fixture(
            compileEnabled = true,
            tokenIdFactory = ids::nextTokenId,
            attemptIdFactory = ids::nextAttemptId,
            clientOrderIdFactory = ids::nextClientOrderId,
        )
        prepareAndArm(fixture)
        val exact = fixture.vm.uiState.value.requiredConfirmationText
        fixture.vm.onConfirmationInputChange(exact)

        fixture.vm.submitOnce()
        assertEquals(PaperOrderSubmitStatus.SUBMITTED, fixture.vm.uiState.value.lastResult?.status)
        assertEquals(1, fixture.submitHttp.callCount)
        assertEquals(null, fixture.tokenStore.peek("token-1"))
        assertFalse(fixture.vm.uiState.value.sessionArmed)

        fixture.vm.onConfirmationInputChange(exact)

        assertEquals(listOf("token-1"), ids.tokenIds)
        assertEquals(null, fixture.vm.uiState.value.confirmationExpiresAtEpochMillis)
        assertEquals(1, fixture.submitHttp.callCount)
    }

    @Test
    fun `new armed session with new preview emits a fresh token`() = runTest {
        var now = 10_000L
        val ids = SubmitVmIdSequence()
        val fixture = fixture(
            compileEnabled = true,
            clockMillis = { now },
            tokenIdFactory = ids::nextTokenId,
            attemptIdFactory = ids::nextAttemptId,
            clientOrderIdFactory = ids::nextClientOrderId,
        )
        prepareAndArm(fixture)
        fixture.vm.onConfirmationInputChange(fixture.vm.uiState.value.requiredConfirmationText)
        val firstExpiry = fixture.vm.uiState.value.confirmationExpiresAtEpochMillis
        fixture.vm.disarmSession()

        val newDryRunId = "dry-run-submit-2"
        val newPreview = submitTestPreview().copy(
            previewId = "preview-submit-2",
            linkedClientDryRunId = newDryRunId,
        )
        val newPreflight = submitTestPreflight().let { result ->
            result.copy(intent = result.intent.copy(clientDryRunId = newDryRunId))
        }
        val newReadiness = submitTestReadiness().copy(
            previewId = newPreview.previewId,
            linkedClientDryRunId = newDryRunId,
        )
        fixture.previewRepository.savePreview(newPreview)
        fixture.vm.updateSource(newPreflight, newPreview, newReadiness)
        now = 11_000L
        fixture.vm.armSession()
        fixture.vm.onConfirmationInputChange(fixture.vm.uiState.value.requiredConfirmationText)

        assertEquals(listOf("token-1", "token-2"), ids.tokenIds)
        assertEquals(listOf("attempt-1", "attempt-2"), ids.attemptIds)
        assertEquals(listOf("vela-client-1", "vela-client-2"), ids.clientOrderIds)
        assertEquals(null, fixture.tokenStore.peek("token-1"))
        val secondToken = fixture.tokenStore.peek("token-2")
        assertNotNull(secondToken)
        assertEquals(newPreview.previewId, secondToken?.previewId)
        assertEquals(41_000L, fixture.vm.uiState.value.confirmationExpiresAtEpochMillis)
        assertTrue(firstExpiry != fixture.vm.uiState.value.confirmationExpiresAtEpochMillis)
        assertEquals(0, fixture.submitHttp.callCount)
    }

    @Test
    fun `UI state never contains credential values`() = runTest {
        val fixture = fixture(compileEnabled = true)
        val preview = submitTestPreview()
        fixture.previewRepository.savePreview(preview)
        fixture.vm.updateSource(submitTestPreflight(), preview, submitTestReadiness())
        fixture.vm.armSession()
        val serialized = fixture.vm.uiState.value.toString()
        assertFalse(serialized.contains("PK-SECRET-ID"))
        assertFalse(serialized.contains("super-secret-value"))
        assertNotNull(fixture.vm.uiState.value.requiredConfirmationText)
    }

    @Test
    fun `confirmation token is not issued while final price drift is blocked`() = runTest {
        val fixture = fixture(compileEnabled = true, finalPriceUsd = 502.0)
        val preview = submitTestPreview()
        fixture.previewRepository.savePreview(preview)
        fixture.vm.updateSource(submitTestPreflight(), preview, submitTestReadiness())

        fixture.vm.armSession()
        assertEquals("PRICE_DRIFT_EXCEEDED", fixture.vm.uiState.value.finalPriceGateResult)
        fixture.vm.onConfirmationInputChange("SUBMIT PAPER SPY BUY 1")

        assertTrue(
            fixture.vm.uiState.value.gateReasons.contains(
                PaperOrderSubmitError.PRICE_DRIFT_EXCEEDED,
            ),
        )
        assertTrue(fixture.tokenStore.peek("token-vm") == null)
        assertEquals(0, fixture.submitHttp.callCount)
    }

    @Test
    fun manualFilledLookupPersistsBeforeResetAndPreservesAudit() = runTest {
        val fixture = fixture(compileEnabled = true)
        fixture.submitHttp.response = PaperSubmitHttpResult.Success(
            200,
            """{"id":"$TEST_ORDER_ID"}""",
        )
        prepareConfirmAndSubmit(fixture)

        assertEquals(PaperOrderSubmitStatus.SUBMITTED, fixture.vm.uiState.value.lastResult?.status)
        assertEquals(TEST_ORDER_ID, fixture.vm.uiState.value.trackedOrder?.orderId)
        assertFalse(fixture.vm.uiState.value.newPreparationAllowed)
        assertEquals(0, fixture.statusHttp.callCount)
        assertEquals(1, fixture.submitHttp.callCount)

        fixture.statusHttp.response = filledStatusResponse(clientOrderId = "vela-client-vm")
        fixture.vm.refreshOrderStatus()

        val filled = fixture.vm.uiState.value
        assertEquals(PaperOrderLifecycleStatus.FILLED, filled.orderStatusSnapshot?.status)
        assertEquals(1.0, filled.orderStatusSnapshot?.filledQuantity)
        assertEquals(501.25, filled.orderStatusSnapshot?.filledAveragePriceUsd)
        assertFalse(filled.newPreparationAllowed)
        assertEquals(PaperOrderReconciliationUiStatus.EXACT_TERMINAL, filled.reconciliationUiStatus)
        assertTrue(fixture.vm.canResetForNewPreparation())
        assertEquals(1, fixture.statusHttp.callCount)
        val auditRowsBeforeReset = fixture.auditDao.rows.toList()
        val historyBeforeReset = filled.trackedOrder?.lifecycleHistory

        var resetCommitted = 0
        fixture.vm.resetForNewPreparation { resetCommitted += 1 }
        advanceUntilIdle()
        val reset = fixture.vm.uiState.value
        assertEquals(1, resetCommitted)
        assertEquals(null, reset.lastResult)
        assertEquals(null, reset.previewId)
        assertFalse(reset.sessionArmed)
        assertFalse(reset.gateAllowed)
        val preserved = reset.reconciliation?.candidates?.singleOrNull()
        assertEquals(TEST_ORDER_ID, preserved?.orderId)
        assertEquals(PaperOrderLifecycleStatus.FILLED,
            preserved?.latestLifecycleSnapshot?.status)
        assertEquals(historyBeforeReset, preserved?.lifecycleHistory)
        assertTrue(reset.newPreparationAllowed)
        assertEquals(auditRowsBeforeReset, fixture.auditDao.rows)
        assertEquals(null, fixture.tokenStore.peek("token-vm"))
        assertEquals(1, fixture.statusHttp.callCount)

        fixture.vm.submitOnce()
        assertEquals(1, fixture.submitHttp.callCount)
    }

    @Test
    fun latestSubmittedAuditIsRestoredWithoutAutomaticGetOrUnlock() = runTest {
        val fixture = fixture(compileEnabled = true, preloadSubmittedOrder = true)

        val state = fixture.vm.uiState.value
        assertEquals(TEST_ORDER_ID, state.trackedOrder?.orderId)
        assertEquals(null, state.orderStatusSnapshot)
        assertEquals(null, state.lastResult)
        assertFalse(state.newPreparationAllowed)
        assertEquals(0, fixture.statusHttp.callCount)

        fixture.statusHttp.response = nonterminalStatusResponse(
            clientOrderId = submitTestRequest().clientOrderId,
        )
        fixture.vm.refreshOrderStatus()
        assertEquals(1, fixture.statusHttp.callCount)
        assertEquals(PaperOrderLifecycleStatus.NEW, fixture.vm.uiState.value.orderStatusSnapshot?.status)
        assertFalse(fixture.vm.uiState.value.newPreparationAllowed)
    }

    @Test
    fun pendingAuditRestoreBlocksArmUntilLocalReadCompletes() = runTest {
        val restoreBlocker = CompletableDeferred<Unit>()
        val trackingSource = SubmitVmTrackingSource(
            snapshot = clearReconciliation(),
            consolidateBlocker = restoreBlocker,
        )
        val fixture = fixture(
            compileEnabled = true,
            trackingSourceOverride = trackingSource,
        )
        val preview = submitTestPreview()
        fixture.vm.updateSource(submitTestPreflight(), preview, submitTestReadiness())

        fixture.vm.armSession()

        assertFalse(fixture.vm.uiState.value.reconciliationRestoreComplete)
        assertFalse(fixture.vm.uiState.value.sessionArmed)
        restoreBlocker.complete(Unit)
        advanceUntilIdle()
        assertTrue(fixture.vm.uiState.value.reconciliationRestoreComplete)
        assertFalse(fixture.vm.uiState.value.sessionArmed)
    }

    @Test
    fun failedAuditRestoreRemainsBlockedWithoutLeakingException() = runTest {
        val fixture = fixture(
            compileEnabled = true,
            trackingSourceOverride = SubmitVmTrackingSource(
                snapshot = clearReconciliation(),
                consolidateFailure = IllegalStateException(
                    "APCA-API-SECRET-KEY=must-not-escape",
                ),
            ),
        )
        val preview = submitTestPreview()
        fixture.vm.updateSource(submitTestPreflight(), preview, submitTestReadiness())

        fixture.vm.armSession()

        val state = fixture.vm.uiState.value
        assertTrue(state.reconciliationRestoreComplete)
        assertTrue(state.untrackableSubmittedOrder)
        assertFalse(state.sessionArmed)
        assertFalse(state.orderStatusError.orEmpty().contains("must-not-escape"))
    }

    @Test
    fun invalidNewSubmittedIdNeverFallsBackToPreviouslyFilledOrder() = runTest {
        val fixture = fixture(compileEnabled = true, preloadSubmittedOrder = true)
        fixture.statusHttp.response = filledStatusResponse(
            clientOrderId = submitTestRequest().clientOrderId,
        )
        fixture.vm.refreshOrderStatus()
        assertTrue(fixture.vm.canResetForNewPreparation())
        fixture.vm.resetForNewPreparation()
        advanceUntilIdle()
        assertTrue(fixture.vm.uiState.value.newPreparationAllowed)
        fixture.submitHttp.response = PaperSubmitHttpResult.Success(
            200,
            """{"id":"not-a-uuid"}""",
        )

        val newDryRunId = "dry-run-submit-2"
        val newPreview = submitTestPreview().copy(
            previewId = "preview-submit-2",
            linkedClientDryRunId = newDryRunId,
        )
        val newPreflight = submitTestPreflight().let { result ->
            result.copy(intent = result.intent.copy(clientDryRunId = newDryRunId))
        }
        val newReadiness = submitTestReadiness().copy(
            previewId = newPreview.previewId,
            linkedClientDryRunId = newDryRunId,
        )
        fixture.previewRepository.savePreview(newPreview)
        fixture.vm.updateSource(newPreflight, newPreview, newReadiness)
        fixture.vm.armSession()
        fixture.vm.onConfirmationInputChange(fixture.vm.uiState.value.requiredConfirmationText)
        fixture.vm.submitOnce()

        val state = fixture.vm.uiState.value
        assertEquals(PaperOrderSubmitStatus.SUBMITTED, state.lastResult?.status)
        assertEquals(null, state.trackedOrder)
        assertTrue(state.untrackableSubmittedOrder)
        assertFalse(state.newPreparationAllowed)
        val statusCalls = fixture.statusHttp.callCount
        fixture.vm.refreshOrderStatus()
        assertEquals(statusCalls, fixture.statusHttp.callCount)
        assertFalse(fixture.vm.canResetForNewPreparation())
    }

    @Test
    fun doubleStatusRefreshWhileInFlightPerformsExactlyOneGet() = runTest {
        val fixture = fixture(compileEnabled = true, preloadSubmittedOrder = true)
        val blocker = CompletableDeferred<Unit>()
        fixture.statusHttp.blocker = blocker

        fixture.vm.refreshOrderStatus()
        fixture.vm.refreshOrderStatus()

        assertEquals(1, fixture.statusHttp.callCount)
        assertTrue(fixture.vm.uiState.value.isRefreshingOrderStatus)
        blocker.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, fixture.statusHttp.callCount)
        assertFalse(fixture.vm.uiState.value.isRefreshingOrderStatus)
    }

    @Test
    fun ambiguousNetworkFailureNeverEnablesAnotherPreparation() = runTest {
        val fixture = fixture(compileEnabled = true)
        fixture.submitHttp.response = PaperSubmitHttpResult.NetworkError
        prepareConfirmAndSubmit(fixture)

        val state = fixture.vm.uiState.value
        assertEquals(PaperOrderSubmitStatus.FAILED, state.lastResult?.status)
        assertEquals(PaperOrderSubmitError.NETWORK_FAILURE, state.lastResult?.errorCode)
        assertFalse(state.newPreparationAllowed)
        assertFalse(fixture.vm.canResetForNewPreparation())
        assertEquals(1, fixture.submitHttp.callCount)
    }

    @Test
    fun malformedSuccessResponseNeverEnablesAnotherPreparation() = runTest {
        val fixture = fixture(compileEnabled = true)
        fixture.submitHttp.response = PaperSubmitHttpResult.Success(200, "{}")
        prepareConfirmAndSubmit(fixture)

        val state = fixture.vm.uiState.value
        assertEquals(PaperOrderSubmitStatus.FAILED, state.lastResult?.status)
        assertEquals(PaperOrderSubmitError.RESPONSE_PARSE_FAILED, state.lastResult?.errorCode)
        assertFalse(state.newPreparationAllowed)
        assertFalse(fixture.vm.canResetForNewPreparation())
    }

    @Test
    fun unexpectedStatusExceptionFailsClosedAndClearsLoading() = runTest {
        val fixture = fixture(compileEnabled = true, preloadSubmittedOrder = true)
        fixture.statusHttp.failure = IllegalStateException(
            "APCA-API-SECRET-KEY=must-not-escape",
        )

        fixture.vm.refreshOrderStatus()

        val state = fixture.vm.uiState.value
        assertEquals(1, fixture.statusHttp.callCount)
        assertFalse(state.isRefreshingOrderStatus)
        assertFalse(state.newPreparationAllowed)
        assertFalse(state.orderStatusError.orEmpty().contains("must-not-escape"))
    }

    @Test
    fun terminalLifecycleSurvivesViewModelRecreationAndManualAckUnlocksWithoutIo() = runTest {
        val first = fixture(compileEnabled = true, preloadSubmittedOrder = true)
        first.statusHttp.response = filledStatusResponse(
            clientOrderId = submitTestRequest().clientOrderId,
        )
        first.vm.refreshOrderStatus()
        val auditBeforeRestart = first.auditDao.rows.toList()
        val historyBeforeRestart = first.vm.uiState.value.trackedOrder?.lifecycleHistory
        assertEquals(PaperOrderReconciliationUiStatus.EXACT_TERMINAL,
            first.vm.uiState.value.reconciliationUiStatus)

        val recreated = fixture(
            compileEnabled = true,
            auditDaoOverride = first.auditDao,
            reconciliationDaoOverride = first.reconciliationDao,
        )

        val restored = recreated.vm.uiState.value
        assertEquals(PaperOrderReconciliationUiStatus.EXACT_TERMINAL,
            restored.reconciliationUiStatus)
        assertEquals(PaperOrderLifecycleStatus.FILLED, restored.orderStatusSnapshot?.status)
        assertEquals(historyBeforeRestart, restored.trackedOrder?.lifecycleHistory)
        assertTrue(recreated.vm.canResetForNewPreparation())
        assertEquals(0, recreated.statusHttp.callCount)
        assertEquals(0, recreated.submitHttp.callCount)

        var callbackCount = 0
        recreated.vm.resetForNewPreparation { callbackCount += 1 }
        advanceUntilIdle()

        val reset = recreated.vm.uiState.value
        assertEquals(1, callbackCount)
        assertTrue(reset.newPreparationAllowed)
        val preserved = reset.reconciliation?.candidates?.singleOrNull()
        assertEquals(PaperOrderLifecycleStatus.FILLED,
            preserved?.latestLifecycleSnapshot?.status)
        assertEquals(historyBeforeRestart, preserved?.lifecycleHistory)
        assertEquals(auditBeforeRestart, recreated.auditDao.rows)
        assertEquals(0, recreated.statusHttp.callCount)
        assertEquals(0, recreated.submitHttp.callCount)
    }

    @Test
    fun nonterminalMultipleAndAmbiguousReconciliationBlockDirectArmAndAutomaticIo() = runTest {
        val snapshots = listOf(
            exactUnresolvedReconciliation(),
            multipleUnresolvedReconciliation(),
            ambiguousReconciliation(),
        )
        snapshots.forEach { snapshot ->
            val source = SubmitVmTrackingSource(snapshot)
            val fixture = fixture(
                compileEnabled = true,
                trackingSourceOverride = source,
            )
            fixture.vm.updateSource(
                submitTestPreflight(),
                submitTestPreview(),
                submitTestReadiness(),
            )

            fixture.vm.armSession()
            assertFalse(fixture.vm.uiState.value.sessionArmed, snapshot.verdict.name)
            if (snapshot.verdict != PaperOrderReconciliationVerdict.SINGLE_UNRESOLVED) {
                fixture.vm.refreshOrderStatus()
                assertEquals(0, source.lookupCount, snapshot.verdict.name)
            }

            assertEquals(0, fixture.statusHttp.callCount, snapshot.verdict.name)
            assertEquals(0, fixture.submitHttp.callCount, snapshot.verdict.name)
            assertFalse(fixture.vm.uiState.value.newPreparationAllowed, snapshot.verdict.name)
        }
    }

    @Test
    fun exactSingleManualGetRunsOnceAndNeverPollsOrSubmits() = runTest {
        val fixture = fixture(compileEnabled = true, preloadSubmittedOrder = true)
        fixture.statusHttp.response = nonterminalStatusResponse(
            clientOrderId = submitTestRequest().clientOrderId,
        )

        assertEquals(0, fixture.statusHttp.callCount)
        fixture.vm.refreshOrderStatus()
        advanceUntilIdle()

        assertEquals(1, fixture.statusHttp.callCount)
        assertEquals(
            listOf(AlpacaPaperOrderStatusEndpoint.urlFor(TEST_ORDER_ID)),
            fixture.statusHttp.urls,
        )
        assertEquals(PaperOrderLifecycleStatus.NEW,
            fixture.vm.uiState.value.orderStatusSnapshot?.status)
        assertFalse(fixture.vm.uiState.value.newPreparationAllowed)
        assertEquals(0, fixture.submitHttp.callCount)
    }

    @Test
    fun lifecyclePersistenceFailureDoesNotExposeTerminalOrUnlockReset() = runTest {
        val fixture = fixture(compileEnabled = true, preloadSubmittedOrder = true)
        val countBefore = fixture.reconciliationDao.lifecycleObservationCount()
        fixture.reconciliationDao.failNextLifecycleInsert = true
        fixture.statusHttp.response = filledStatusResponse(
            clientOrderId = submitTestRequest().clientOrderId,
        )

        fixture.vm.refreshOrderStatus()

        val state = fixture.vm.uiState.value
        assertEquals(1, fixture.statusHttp.callCount)
        assertEquals(countBefore, fixture.reconciliationDao.lifecycleObservationCount())
        assertEquals(PaperOrderReconciliationUiStatus.EXACT_UNRESOLVED,
            state.reconciliationUiStatus)
        assertEquals(null, state.orderStatusSnapshot)
        assertFalse(state.newPreparationAllowed)
        assertFalse(fixture.vm.canResetForNewPreparation())
        assertTrue(state.orderStatusError.orEmpty().contains("PERSISTENCE_FAILED"))
        assertEquals(0, fixture.submitHttp.callCount)
    }

    @Test
    fun resetPersistenceFailureKeepsTerminalAttemptAndActiveUiBlocked() = runTest {
        val fixture = fixture(compileEnabled = true)
        fixture.submitHttp.response = PaperSubmitHttpResult.Success(
            200,
            """{"id":"$TEST_ORDER_ID"}""",
        )
        prepareConfirmAndSubmit(fixture)
        fixture.statusHttp.response = filledStatusResponse(clientOrderId = "vela-client-vm")
        fixture.vm.refreshOrderStatus()
        val before = fixture.vm.uiState.value
        fixture.reconciliationDao.failResetAcknowledgement = true

        var callbackCount = 0
        fixture.vm.resetForNewPreparation { callbackCount += 1 }
        advanceUntilIdle()

        val after = fixture.vm.uiState.value
        assertEquals(0, callbackCount)
        assertEquals(before.previewId, after.previewId)
        assertEquals(before.lastResult, after.lastResult)
        assertEquals(PaperOrderReconciliationUiStatus.EXACT_TERMINAL,
            after.reconciliationUiStatus)
        assertFalse(after.newPreparationAllowed)
        assertTrue(after.orderStatusError.orEmpty().contains("not persisted"))
        assertEquals(1, fixture.statusHttp.callCount)
        assertEquals(1, fixture.submitHttp.callCount)
    }

    private suspend fun prepareAndArm(fixture: ViewModelFixture) {
        val preview = submitTestPreview()
        fixture.previewRepository.savePreview(preview)
        fixture.vm.updateSource(submitTestPreflight(), preview, submitTestReadiness())
        fixture.vm.armSession()
    }

    private suspend fun prepareConfirmAndSubmit(fixture: ViewModelFixture) {
        prepareAndArm(fixture)
        fixture.vm.onConfirmationInputChange(fixture.vm.uiState.value.requiredConfirmationText)
        fixture.vm.submitOnce()
    }

    private fun fixture(
        compileEnabled: Boolean,
        finalPriceUsd: Double = 500.0,
        preloadSubmittedOrder: Boolean = false,
        trackingSourceOverride: PaperOrderTrackingSource? = null,
        auditDaoOverride: SubmitFakeAuditDao? = null,
        reconciliationDaoOverride: SubmitVmReconciliationDao? = null,
        clockMillis: () -> Long = { 10_000L },
        tokenIdFactory: () -> String = { "token-vm" },
        attemptIdFactory: () -> String = { "attempt-vm" },
        clientOrderIdFactory: () -> String = { "vela-client-vm" },
    ): ViewModelFixture {
        val initialNow = clockMillis()
        val store = SubmitVmCredentialStore(
            AlpacaCredentials("PK-SECRET-ID", "super-secret-value"),
        )
        val readHttp = SubmitVmReadHttpClient()
        val readClient = AlpacaPaperReadOnlyClient(
            credentialsProvider = AlpacaCredentialsProvider { store.load() },
            httpClient = readHttp,
        )
        val marketDao = SubmitVmMarketBarDao().apply {
            runBlocking {
                insert(
                    MarketBar1mEntity(
                        symbol = "SPY",
                        bucketStartEpochMillis = 9_000L,
                        open = finalPriceUsd,
                        high = finalPriceUsd,
                        low = finalPriceUsd,
                        close = finalPriceUsd,
                        updateCount = 1,
                        syntheticVolume = 1.0,
                        lastUpdateTimeEpochMillis = null,
                    ),
                )
            }
        }
        val priceProvider = MarketPriceSnapshotProvider(
            tickBuffer = MarketTickBuffer(),
            marketDataRepository = MarketDataRepository(marketDao),
            clock = { Instant.ofEpochMilli(clockMillis()) },
        )
        val previewRepository = PaperOrderPayloadPreviewRepository(PreviewQueueFakeDao())
        val feature = PaperManualExecutionFeatureGate(compileEnabled)
        val gate = PaperManualSubmitGate(feature)
        val tokenStore = PaperManualSubmitTokenStore(
            clock = { Instant.ofEpochMilli(clockMillis()) },
            tokenIdFactory = tokenIdFactory,
        )
        val submitHttp = SubmitFakeHttpClient()
        val auditDao = auditDaoOverride ?: SubmitFakeAuditDao()
        val auditRepository = PaperOrderSubmitAuditRepository(auditDao)
        if (preloadSubmittedOrder) {
            runBlocking {
                val request = submitTestRequest()
                val preview = submitTestPreview()
                auditRepository.recordAttemptStarted(request, preview, true, 9_900L)
                auditRepository.recordResult(
                    request,
                    preview,
                    true,
                    PaperOrderSubmitResult(
                        submitAttemptId = request.submitAttemptId,
                        previewId = request.previewId,
                        status = PaperOrderSubmitStatus.SUBMITTED,
                        alpacaOrderId = TEST_ORDER_ID,
                        clientOrderId = request.clientOrderId,
                        submittedAtEpochMillis = initialNow,
                        errorCode = null,
                        safeErrorMessage = null,
                    ),
                )
            }
        }
        val statusHttp = SubmitVmOrderStatusHttpClient()
        val statusClient = AlpacaPaperOrderStatusReadOnlyClient(
            credentialsProvider = AlpacaCredentialsProvider { store.load() },
            httpClient = statusHttp,
        )
        val reconciliationDao = reconciliationDaoOverride ?: SubmitVmReconciliationDao()
        val statusTracker = PaperOrderStatusTrackerRepository(
            auditRepository,
            reconciliationDao,
        )
        val executor = PaperManualSubmitExecutor(
            gate = gate,
            tokenStore = tokenStore,
            submitClient = PaperManualOrderSubmitClient(
                submitHttp,
                clock = { Instant.ofEpochMilli(clockMillis()) },
            ),
            auditRepository = auditRepository,
            finalPriceSnapshotProvider = priceProvider::snapshotFor,
            clock = { Instant.ofEpochMilli(clockMillis()) },
        )
        val vm = PaperManualSubmitViewModel(
            featureGate = feature,
            gate = gate,
            tokenStore = tokenStore,
            executor = executor,
            readOnlyClient = readClient,
            credentialsStore = store,
            priceSnapshotProvider = priceProvider,
            previewRepository = previewRepository,
            appState = AppState(),
            orderStatusClient = statusClient,
            orderStatusTrackerRepository = trackingSourceOverride ?: statusTracker,
            clock = { Instant.ofEpochMilli(clockMillis()) },
            attemptIdFactory = attemptIdFactory,
            clientOrderIdFactory = clientOrderIdFactory,
        )
        return ViewModelFixture(
            vm = vm,
            previewRepository = previewRepository,
            submitHttp = submitHttp,
            tokenStore = tokenStore,
            statusHttp = statusHttp,
            auditDao = auditDao,
            reconciliationDao = reconciliationDao,
        )
    }
}

private data class ViewModelFixture(
    val vm: PaperManualSubmitViewModel,
    val previewRepository: PaperOrderPayloadPreviewRepository,
    val submitHttp: SubmitFakeHttpClient,
    val tokenStore: PaperManualSubmitTokenStore,
    val statusHttp: SubmitVmOrderStatusHttpClient,
    val auditDao: SubmitFakeAuditDao,
    val reconciliationDao: SubmitVmReconciliationDao,
)

private class SubmitVmIdSequence {
    val tokenIds = mutableListOf<String>()
    val attemptIds = mutableListOf<String>()
    val clientOrderIds = mutableListOf<String>()

    fun nextTokenId(): String = "token-${tokenIds.size + 1}".also(tokenIds::add)

    fun nextAttemptId(): String = "attempt-${attemptIds.size + 1}".also(attemptIds::add)

    fun nextClientOrderId(): String =
        "vela-client-${clientOrderIds.size + 1}".also(clientOrderIds::add)
}

private const val TEST_ORDER_ID = "4b60549d-6dab-47d8-93eb-382ed1eed108"

private fun nonterminalStatusResponse(
    clientOrderId: String,
): PaperOrderStatusHttpResult = PaperOrderStatusHttpResult.Success(
    200,
    """{
      "id":"$TEST_ORDER_ID","client_order_id":"$clientOrderId",
      "symbol":"SPY","side":"buy","qty":"1","type":"market",
      "time_in_force":"day","status":"new","filled_qty":"0",
      "filled_avg_price":null,"filled_at":null
    }""",
)

private fun filledStatusResponse(
    clientOrderId: String,
): PaperOrderStatusHttpResult = PaperOrderStatusHttpResult.Success(
    200,
    """{
      "id":"$TEST_ORDER_ID","client_order_id":"$clientOrderId",
      "symbol":"SPY","side":"buy","qty":"1","type":"market",
      "time_in_force":"day","status":"filled","filled_qty":"1",
      "filled_avg_price":"501.25","filled_at":"2026-08-11T14:30:00Z"
    }""",
)

private class SubmitVmOrderStatusHttpClient : AlpacaPaperOrderStatusHttpClient {
    var response: PaperOrderStatusHttpResult = nonterminalStatusResponse(
        clientOrderId = submitTestRequest().clientOrderId,
    )
    var blocker: CompletableDeferred<Unit>? = null
    var failure: RuntimeException? = null
    var callCount: Int = 0
    val urls: MutableList<String> = mutableListOf()

    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): PaperOrderStatusHttpResult {
        AlpacaPaperOrderStatusEndpoint.requireSafeGet(url)
        callCount += 1
        urls += url
        failure?.let { throw it }
        blocker?.await()
        return response
    }
}

private class SubmitVmReconciliationDao : PaperOrderReconciliationDao {
    private val reconciliations = linkedMapOf<String, PaperOrderReconciliationEntity>()
    private val observations = mutableListOf<PaperOrderLifecycleObservationEntity>()
    private var nextObservationId = 1L
    var failNextLifecycleInsert: Boolean = false
    var failResetAcknowledgement: Boolean = false

    override suspend fun insertReconciliation(entity: PaperOrderReconciliationEntity): Long {
        check(reconciliations.putIfAbsent(entity.submitAttemptId, entity) == null)
        return reconciliations.size.toLong()
    }

    override suspend fun insertReconciliationIfAbsent(
        entity: PaperOrderReconciliationEntity,
    ): Long = if (reconciliations.putIfAbsent(entity.submitAttemptId, entity) == null) {
        reconciliations.size.toLong()
    } else {
        -1L
    }

    override suspend fun updateReconciliation(entity: PaperOrderReconciliationEntity): Int {
        if (!reconciliations.containsKey(entity.submitAttemptId)) return 0
        reconciliations[entity.submitAttemptId] = entity
        return 1
    }

    override suspend fun reconciliationByAttemptId(
        attemptId: String,
    ): PaperOrderReconciliationEntity? = reconciliations[attemptId]

    override suspend fun allReconciliations(): List<PaperOrderReconciliationEntity> =
        reconciliations.values.sortedBy(PaperOrderReconciliationEntity::submitAttemptId)

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
        if (failNextLifecycleInsert) {
            failNextLifecycleInsert = false
            error("simulated lifecycle persistence failure")
        }
        check(observations.none { it.observationKey == observation.observationKey })
        val stored = observation.copy(id = nextObservationId++)
        observations += stored
        return stored.id
    }

    override suspend fun lifecycleByAttemptId(
        attemptId: String,
    ): List<PaperOrderLifecycleObservationEntity> =
        observations.filter { it.submitAttemptId == attemptId }.sortedBy { it.id }

    override suspend fun latestLifecycleByAttemptId(
        attemptId: String,
    ): PaperOrderLifecycleObservationEntity? =
        lifecycleByAttemptId(attemptId).lastOrNull()

    override suspend fun lifecycleObservationCount(): Int = observations.size

    override suspend fun acknowledgeReset(
        attemptId: String,
        acknowledgedAtEpochMillis: Long,
    ): Int {
        if (failResetAcknowledgement) return 0
        val current = reconciliations[attemptId] ?: return 0
        if (!current.terminal || current.mappingStatus != "EXACT" ||
            current.resetAcknowledgedAtEpochMillis != null
        ) {
            return 0
        }
        reconciliations[attemptId] = current.copy(
            resetAcknowledgedAtEpochMillis = acknowledgedAtEpochMillis,
        )
        return 1
    }
}

private class SubmitVmTrackingSource(
    private val snapshot: PaperOrderReconciliationSnapshot,
    private val consolidateBlocker: CompletableDeferred<Unit>? = null,
    private val consolidateFailure: RuntimeException? = null,
) : PaperOrderTrackingSource {
    var lookupCount: Int = 0
    var persistCount: Int = 0
    var resetCount: Int = 0

    override suspend fun consolidateFromAudit(): PaperOrderReconciliationSnapshot {
        consolidateBlocker?.await()
        consolidateFailure?.let { throw it }
        return snapshot
    }

    override suspend fun lookupTarget(): PaperOrderLifecycleLookupResult {
        lookupCount += 1
        return PaperOrderLifecycleLookupResult.Blocked(snapshot.verdict, snapshot.issues)
    }

    override suspend fun persistLifecycle(
        target: com.vela.android.lab.data.paper.status.PaperOrderLifecycleLookupTarget,
        status: PaperOrderStatusSnapshot,
        evidence: PaperOrderStatusFetchEvidence,
        observedAtEpochMillis: Long,
    ): PaperOrderLifecyclePersistResult {
        persistCount += 1
        return PaperOrderLifecyclePersistResult.Blocked(
            snapshot,
            setOf(PaperOrderReconciliationIssue.PERSISTENCE_FAILED),
        )
    }

    override suspend fun acknowledgeTerminalReset(
        submitAttemptId: String,
        acknowledgedAtEpochMillis: Long,
    ): PaperOrderResetAcknowledgementResult {
        resetCount += 1
        return PaperOrderResetAcknowledgementResult.Blocked(snapshot)
    }
}

private fun clearReconciliation(): PaperOrderReconciliationSnapshot =
    PaperOrderReconciliationSnapshot(
        verdict = PaperOrderReconciliationVerdict.CLEAR,
        candidates = emptyList(),
        issues = emptySet(),
    )

private fun exactUnresolvedReconciliation(): PaperOrderReconciliationSnapshot =
    PaperOrderReconciliationSnapshot(
        verdict = PaperOrderReconciliationVerdict.SINGLE_UNRESOLVED,
        candidates = listOf(reconciledOrder("attempt-1", TEST_ORDER_ID, "client-1")),
        issues = emptySet(),
    )

private fun multipleUnresolvedReconciliation(): PaperOrderReconciliationSnapshot =
    PaperOrderReconciliationSnapshot(
        verdict = PaperOrderReconciliationVerdict.MULTIPLE_UNRESOLVED_PAPER_ORDERS,
        candidates = listOf(
            reconciledOrder("attempt-1", TEST_ORDER_ID, "client-1"),
            reconciledOrder(
                "attempt-2",
                "d21f4ca1-5765-4d6a-8984-1f934c9183d2",
                "client-2",
            ),
        ),
        issues = setOf(PaperOrderReconciliationIssue.MULTIPLE_UNRESOLVED_PAPER_ORDERS),
    )

private fun ambiguousReconciliation(): PaperOrderReconciliationSnapshot {
    val issue = PaperOrderReconciliationIssue.LOCAL_AUDIT_INCOMPLETE
    return PaperOrderReconciliationSnapshot(
        verdict = PaperOrderReconciliationVerdict.AMBIGUOUS,
        candidates = listOf(
            reconciledOrder(
                attemptId = "attempt-ambiguous",
                orderId = null,
                clientOrderId = null,
                mappingExact = false,
                issues = setOf(issue),
            ),
        ),
        issues = setOf(issue),
    )
}

private fun reconciledOrder(
    attemptId: String,
    orderId: String?,
    clientOrderId: String?,
    mappingExact: Boolean = true,
    issues: Set<PaperOrderReconciliationIssue> = emptySet(),
): ReconciledPaperOrder = ReconciledPaperOrder(
    submitAttemptId = attemptId,
    attemptStartedAuditEntryId = 1L,
    submitResultAuditEntryId = 2L,
    previewId = "preview-$attemptId",
    linkedClientDryRunId = "dry-$attemptId",
    orderId = orderId,
    clientOrderId = clientOrderId,
    symbol = "SPY",
    side = "BUY",
    quantity = 1.0,
    orderType = "MARKET",
    timeInForce = "DAY",
    submittedAtEpochMillis = 10_000L,
    localSubmitResult = "SUBMITTED",
    mappingExact = mappingExact,
    issues = issues,
    lifecycleHistory = emptyList(),
    latestLifecycleSnapshot = null,
    lifecycleObservedAtEpochMillis = null,
    terminal = false,
    resetAcknowledgedAtEpochMillis = null,
)

private class SubmitVmCredentialStore(
    private var credentials: AlpacaCredentials?,
) : SecureAlpacaCredentialsStore {
    override suspend fun save(credentials: AlpacaCredentials) { this.credentials = credentials }
    override suspend fun load(): AlpacaCredentials? = credentials
    override suspend fun clear() { credentials = null }
    override suspend fun hasCredentials(): Boolean = credentials != null
}

private class SubmitVmReadHttpClient : AlpacaHttpClient {
    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): HttpResult {
        AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
        val body = when (url) {
            AlpacaPaperTradingEndpoint.ACCOUNT_URL ->
                """{"status":"ACTIVE","buying_power":"100000","cash":"50000","equity":"100000","portfolio_value":"100000","trading_blocked":false,"account_blocked":false}"""
            AlpacaPaperTradingEndpoint.CLOCK_URL -> """{"is_open":true}"""
            AlpacaPaperTradingEndpoint.POSITIONS_URL -> "[]"
            else -> return HttpResult.HttpError(404, "not found")
        }
        return HttpResult.Success(200, body)
    }
}

private class SubmitVmMarketBarDao : MarketBarDao {
    private val rows = mutableListOf<MarketBar1mEntity>()
    private var nextId = 1L
    override suspend fun insert(bar: MarketBar1mEntity): Long {
        val stored = if (bar.id == 0L) bar.copy(id = nextId++) else bar
        rows += stored
        return stored.id
    }
    override suspend fun insertAll(bars: List<MarketBar1mEntity>): List<Long> = bars.map { insert(it) }
    override suspend fun bySymbol(symbol: String): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }
    override suspend fun recent(symbol: String, limit: Int): List<MarketBar1mEntity> =
        rows.filter { it.symbol == symbol }
            .sortedByDescending { it.bucketStartEpochMillis }.take(limit)
    override suspend fun countBySymbol(symbol: String): Int = rows.count { it.symbol == symbol }
    override suspend fun countAll(): Int = rows.size
    override suspend fun deleteBySymbol(symbol: String) { rows.removeAll { it.symbol == symbol } }
    override suspend fun clear() { rows.clear() }
}
