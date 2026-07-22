package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.market.price.MarketPriceSnapshot
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperManualSubmitExecutorTest {
    @Test
    fun `valid confirmed flow sends one POST and writes start plus success audit`() = runTest {
        val fixture = fixture()
        val result = fixture.executor.executeOnce(
            fixture.request,
            fixture.preview,
            fixture.gateInput,
        )
        assertEquals(PaperOrderSubmitStatus.SUBMITTED, result.status)
        assertEquals(1, fixture.http.callCount)
        assertEquals(1, fixture.finalPriceCalls())
        assertEquals(listOf("ATTEMPT_STARTED", "SUBMITTED"), fixture.dao.rows.map { it.status })
    }

    @Test
    fun `duplicate invocation for same preview and client id sends only one POST`() = runTest {
        val fixture = fixture()
        val results = listOf(
            async {
                fixture.executor.executeOnce(
                    fixture.request,
                    fixture.preview,
                    fixture.gateInput,
                )
            },
            async {
                fixture.executor.executeOnce(
                    fixture.request,
                    fixture.preview,
                    fixture.gateInput,
                )
            },
        ).awaitAll()

        assertEquals(1, fixture.http.callCount)
        assertEquals(1, results.count { it.status == PaperOrderSubmitStatus.SUBMITTED })
        assertEquals(1, results.count { it.status == PaperOrderSubmitStatus.BLOCKED })
    }

    @Test
    fun `audit start failure sends zero POST and consumes token`() = runTest {
        val fixture = fixture(failAudit = true)
        val first = fixture.executor.executeOnce(
            fixture.request,
            fixture.preview,
            fixture.gateInput,
        )
        assertEquals(PaperOrderSubmitStatus.FAILED, first.status)
        assertEquals(PaperOrderSubmitError.AUDIT_WRITE_FAILED, first.errorCode)
        assertEquals(0, fixture.http.callCount)
        assertTrue(fixture.tokenStore.peek(fixture.request.confirmationTokenId) == null)
    }

    @Test
    fun `compile feature OFF blocks locally with zero POST`() = runTest {
        val fixture = fixture(compileEnabled = false)
        val result = fixture.executor.executeOnce(
            fixture.request,
            fixture.preview,
            fixture.gateInput,
        )
        assertEquals(PaperOrderSubmitStatus.BLOCKED, result.status)
        assertEquals(PaperOrderSubmitError.FEATURE_DISABLED, result.errorCode)
        assertEquals(0, fixture.http.callCount)
    }

    @Test
    fun `emergency disable after start audit blocks immediately before POST`() = runTest {
        val fixture = fixture()
        fixture.dao.afterInsert = { event ->
            if (event.status == PaperOrderSubmitAuditRepository.ATTEMPT_STARTED) {
                fixture.feature.activateEmergencyDisable()
            }
        }

        val result = fixture.executor.executeOnce(
            fixture.request,
            fixture.preview,
            fixture.gateInput,
        )

        assertEquals(PaperOrderSubmitStatus.BLOCKED, result.status)
        assertEquals(PaperOrderSubmitError.EMERGENCY_DISABLED, result.errorCode)
        assertEquals(0, fixture.http.callCount)
        assertEquals(listOf("ATTEMPT_STARTED", "BLOCKED"), fixture.dao.rows.map { it.status })
    }

    @Test
    fun `final drift above threshold is rechecked and sends zero POST`() = runTest {
        val fixture = fixture(finalPriceSnapshot = submitTestPrice(price = 502.0))

        val result = fixture.executor.executeOnce(
            fixture.request,
            fixture.preview,
            fixture.gateInput,
        )

        assertEquals(PaperOrderSubmitStatus.BLOCKED, result.status)
        assertEquals(PaperOrderSubmitError.PRICE_DRIFT_EXCEEDED, result.errorCode)
        assertEquals(1, fixture.finalPriceCalls())
        assertEquals(0, fixture.http.callCount)
        assertEquals(listOf("ATTEMPT_STARTED", "BLOCKED"), fixture.dao.rows.map { it.status })
    }

    @Test
    fun `network failure never retries and token cannot be reused`() = runTest {
        val fixture = fixture()
        fixture.http.response = PaperSubmitHttpResult.NetworkError
        val first = fixture.executor.executeOnce(
            fixture.request,
            fixture.preview,
            fixture.gateInput,
        )
        val second = fixture.executor.executeOnce(
            fixture.request.copy(submitAttemptId = "attempt-submit-2"),
            fixture.preview,
            fixture.gateInput.copy(
                request = fixture.request.copy(submitAttemptId = "attempt-submit-2"),
            ),
        )
        assertEquals(PaperOrderSubmitStatus.FAILED, first.status)
        assertEquals(PaperOrderSubmitStatus.BLOCKED, second.status)
        assertEquals(1, fixture.http.callCount)
    }

    private fun fixture(
        failAudit: Boolean = false,
        compileEnabled: Boolean = true,
        finalPriceSnapshot: MarketPriceSnapshot = submitTestPrice(),
    ): ExecutorFixture {
        val preview = submitTestPreview()
        val tokenStore = PaperManualSubmitTokenStore(
            clock = { Instant.ofEpochMilli(SUBMIT_TEST_NOW) },
            tokenIdFactory = { "token-submit-1" },
        )
        val confirmation = (tokenStore.issue(
            preview,
            PaperManualSubmitTokenStore.requiredText(preview),
        ) as PaperManualSubmitTokenIssue.Issued).confirmation
        val request = submitTestRequest(confirmation.tokenId)
        val feature = PaperManualExecutionFeatureGate(compileEnabled)
        val gate = PaperManualSubmitGate(feature)
        val http = SubmitFakeHttpClient()
        val dao = SubmitFakeAuditDao(failInsert = failAudit)
        val repository = PaperOrderSubmitAuditRepository(dao)
        var finalPriceCalls = 0
        val executor = PaperManualSubmitExecutor(
            gate = gate,
            tokenStore = tokenStore,
            submitClient = PaperManualOrderSubmitClient(
                http,
                clock = { Instant.ofEpochMilli(SUBMIT_TEST_NOW) },
            ),
            auditRepository = repository,
            finalPriceSnapshotProvider = {
                finalPriceCalls += 1
                finalPriceSnapshot
            },
            clock = { Instant.ofEpochMilli(SUBMIT_TEST_NOW) },
        )
        return ExecutorFixture(
            preview = preview,
            request = request,
            gateInput = submitTestGateInput(
                confirmation = confirmation,
                request = request,
            ),
            tokenStore = tokenStore,
            feature = feature,
            http = http,
            dao = dao,
            finalPriceCalls = { finalPriceCalls },
            executor = executor,
        )
    }
}

private data class ExecutorFixture(
    val preview: com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview,
    val request: PaperOrderSubmitRequest,
    val gateInput: PaperManualSubmitGateInput,
    val tokenStore: PaperManualSubmitTokenStore,
    val feature: PaperManualExecutionFeatureGate,
    val http: SubmitFakeHttpClient,
    val dao: SubmitFakeAuditDao,
    val finalPriceCalls: () -> Int,
    val executor: PaperManualSubmitExecutor,
)
