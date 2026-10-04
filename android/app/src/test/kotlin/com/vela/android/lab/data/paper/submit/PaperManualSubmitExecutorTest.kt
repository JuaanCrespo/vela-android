package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.market.price.ExecutionPriceRejection
import com.vela.android.lab.data.market.price.ExecutionReferencePrice
import com.vela.android.lab.data.market.price.MarketPriceSnapshotProvider
import com.vela.android.lab.data.market.tick.MarketDataProvenance
import com.vela.android.lab.data.market.tick.MarketTick
import com.vela.android.lab.data.market.tick.MarketTickBuffer
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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
    fun `trusted price that disappears before final submit is rejected with zero POST`() = runTest {
        // Passed the review-time gate, then the live quote is gone at the final re-evaluation.
        val fixture = fixture(
            finalReference = ExecutionReferencePrice.Rejected("SPY", ExecutionPriceRejection.NO_LIVE_QUOTE),
        )

        val result = fixture.executor.executeOnce(
            fixture.request,
            fixture.preview,
            fixture.gateInput,
        )

        assertEquals(PaperOrderSubmitStatus.BLOCKED, result.status)
        assertEquals(PaperOrderSubmitError.NO_TRUSTED_EXECUTION_PRICE, result.errorCode)
        assertEquals(1, fixture.finalPriceCalls())
        assertEquals(0, fixture.http.callCount)
        assertEquals(listOf("ATTEMPT_STARTED", "BLOCKED"), fixture.dao.rows.map { it.status })
    }

    @Test
    fun `no trusted execution price at review or at submit sends zero POST`() = runTest {
        // A demo or Room-only situation: no live trusted quote exists when the executor runs.
        val fixture = fixture(
            finalReference = ExecutionReferencePrice.Rejected("SPY", ExecutionPriceRejection.NO_LIVE_QUOTE),
        )

        val result = fixture.executor.executeOnce(
            fixture.request,
            fixture.preview,
            fixture.gateInput.copy(executionReference = null),
        )

        assertEquals(PaperOrderSubmitStatus.BLOCKED, result.status)
        assertEquals(PaperOrderSubmitError.NO_TRUSTED_EXECUTION_PRICE, result.errorCode)
        assertEquals(0, fixture.http.callCount)
    }

    @Test
    fun `final price provider failure fails closed with zero POST and no fallback value`() = runTest {
        val fixture = fixture(finalProviderThrows = true)

        val result = fixture.executor.executeOnce(
            fixture.request,
            fixture.preview,
            fixture.gateInput,
        )

        assertEquals(PaperOrderSubmitStatus.BLOCKED, result.status)
        assertEquals(PaperOrderSubmitError.NO_TRUSTED_EXECUTION_PRICE, result.errorCode)
        assertEquals(0, fixture.http.callCount)
    }

    @Test
    fun `final drift above threshold is rechecked and sends zero POST`() = runTest {
        val fixture = fixture(finalReference = submitTestPrice(price = 502.0))

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

    @Test
    fun `trusted reference that ages out between review and submit is blocked with zero POST`() = runTest {
        // Review and the start audit read the clock at SUBMIT_TEST_NOW. The final gate reads it 9.001 s later.
        val calls = AtomicInteger(0)
        val fixture = fixture(
            executorClock = {
                val call = calls.incrementAndGet()
                Instant.ofEpochMilli(if (call <= 2) SUBMIT_TEST_NOW else SUBMIT_TEST_NOW + 9_001L)
            },
        )

        val result = fixture.executor.executeOnce(fixture.request, fixture.preview, fixture.gateInput)

        assertEquals(PaperOrderSubmitStatus.BLOCKED, result.status)
        assertEquals(PaperOrderSubmitError.PRICE_NOT_FRESH, result.errorCode)
        assertEquals(0, fixture.http.callCount)
    }

    @Test
    fun `latest visible price becoming a demo quote before submit blocks with zero POST`() = runTest {
        val review = trustedReviewReference(MarketDataProvenance.ALPACA_IEX_REAL_TIME)
        val buffer = MarketTickBuffer()
        buffer.pushQuote(executorTick(MarketDataProvenance.ALPACA_IEX_REAL_TIME, atMillis = SUBMIT_TEST_NOW - 500L))
        val provider = MarketPriceSnapshotProvider(tickBuffer = buffer, clock = { Instant.ofEpochMilli(SUBMIT_TEST_NOW) })
        // A demo quote becomes the newest visible price for the symbol before the final re-evaluation.
        buffer.pushQuote(executorTick(MarketDataProvenance.LOCAL_DEMO_SYNTHETIC, atMillis = SUBMIT_TEST_NOW - 100L))
        val fixture = fixture(finalProvider = { provider.executionReferenceFor(it) })

        val result = fixture.executor.executeOnce(
            fixture.request,
            fixture.preview,
            fixture.gateInput.copy(executionReference = review),
        )

        assertEquals(PaperOrderSubmitStatus.BLOCKED, result.status)
        assertEquals(PaperOrderSubmitError.NO_TRUSTED_EXECUTION_PRICE, result.errorCode)
        assertEquals(0, fixture.http.callCount)
    }

    @Test
    fun `latest visible price switching to the TEST stream before submit blocks with zero POST`() = runTest {
        val review = trustedReviewReference(MarketDataProvenance.ALPACA_IEX_REAL_TIME)
        val buffer = MarketTickBuffer()
        buffer.pushQuote(executorTick(MarketDataProvenance.ALPACA_IEX_REAL_TIME, atMillis = SUBMIT_TEST_NOW - 500L))
        val provider = MarketPriceSnapshotProvider(tickBuffer = buffer, clock = { Instant.ofEpochMilli(SUBMIT_TEST_NOW) })
        buffer.pushQuote(executorTick(MarketDataProvenance.ALPACA_TEST_SYNTHETIC, atMillis = SUBMIT_TEST_NOW - 100L))
        val fixture = fixture(finalProvider = { provider.executionReferenceFor(it) })

        val result = fixture.executor.executeOnce(
            fixture.request,
            fixture.preview,
            fixture.gateInput.copy(executionReference = review),
        )

        assertEquals(PaperOrderSubmitStatus.BLOCKED, result.status)
        assertEquals(PaperOrderSubmitError.NO_TRUSTED_EXECUTION_PRICE, result.errorCode)
        assertEquals(0, fixture.http.callCount)
    }

    /** A trusted review-time reference from the real provider path, fresh at [SUBMIT_TEST_NOW]. */
    private suspend fun trustedReviewReference(provenance: MarketDataProvenance): ExecutionReferencePrice {
        val buffer = MarketTickBuffer()
        buffer.pushQuote(executorTick(provenance, atMillis = SUBMIT_TEST_NOW - 500L))
        val reference = MarketPriceSnapshotProvider(
            tickBuffer = buffer,
            clock = { Instant.ofEpochMilli(SUBMIT_TEST_NOW) },
        ).executionReferenceFor("SPY")
        assertTrue(reference is ExecutionReferencePrice.Trusted)
        return reference
    }

    private fun executorTick(provenance: MarketDataProvenance, atMillis: Long): MarketTick = MarketTick(
        symbol = "SPY",
        bidPrice = 500.0,
        askPrice = 500.0,
        marketTimestampMillis = atMillis,
        receivedAtMillis = atMillis,
        source = "test-feed",
        provenance = provenance,
    )

    private fun fixture(
        failAudit: Boolean = false,
        compileEnabled: Boolean = true,
        finalReference: ExecutionReferencePrice = submitTestPrice(),
        finalProviderThrows: Boolean = false,
        executorClock: () -> Instant = { Instant.ofEpochMilli(SUBMIT_TEST_NOW) },
        finalProvider: (suspend (String) -> ExecutionReferencePrice)? = null,
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
            executionReferenceProvider = finalProvider ?: {
                finalPriceCalls += 1
                if (finalProviderThrows) error("simulated provider failure")
                finalReference
            },
            clock = executorClock,
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
