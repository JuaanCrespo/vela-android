package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.market.price.ExecutionReferencePriceEvaluator
import com.vela.android.lab.data.market.price.LiveQuoteObservation
import com.vela.android.lab.data.market.price.MarketPriceSource
import com.vela.android.lab.data.market.tick.MarketDataProvenance
import com.vela.android.lab.data.paper.preflight.PreflightStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class PaperManualSubmitGateTest {
    @Test
    fun `allows only fully valid armed manual Paper flow`() {
        val decision = enabledGate().evaluate(submitTestGateInput())
        assertEquals(PaperManualSubmitGateDecision.Allowed, decision)
    }

    @TestFactory
    fun `blocks each required fail-closed condition`(): List<DynamicTest> {
        val cases = listOf<Pair<PaperOrderSubmitError, PaperManualSubmitGateInput>>(
            PaperOrderSubmitError.HUMAN_APPROVAL_MISSING to
                submitTestGateInput().copy(humanApprovalRecorded = false),
            PaperOrderSubmitError.REAL_NOT_LOCKED to
                submitTestGateInput().copy(realLocked = false),
            PaperOrderSubmitError.LIVE_NOT_DISABLED to
                submitTestGateInput().copy(liveEnabled = true),
            PaperOrderSubmitError.AUTO_PAPER_NOT_DISABLED to
                submitTestGateInput().copy(autoPaperEnabled = true),
            PaperOrderSubmitError.CREDENTIALS_MISSING to
                submitTestGateInput().copy(credentialsConfigured = false),
            PaperOrderSubmitError.ACCOUNT_BLOCKED to
                submitTestGateInput().copy(accountBlocked = true),
            PaperOrderSubmitError.TRADING_BLOCKED to
                submitTestGateInput().copy(tradingBlocked = true),
            PaperOrderSubmitError.ACCOUNT_STALE to
                submitTestGateInput().copy(accountRefreshedAtEpochMillis = null),
            PaperOrderSubmitError.CLOCK_STALE to
                submitTestGateInput().copy(clockRefreshedAtEpochMillis = null),
            PaperOrderSubmitError.MARKET_CLOSED to
                submitTestGateInput().copy(marketOpen = false),
            // Trusted when reviewed (1 s old), then re-evaluated 9.001 s later at submit.
            PaperOrderSubmitError.PRICE_NOT_FRESH to
                submitTestGateInput().copy(
                    executionReference = submitTestPrice(ageMillis = 1_000L),
                    nowEpochMillis = SUBMIT_TEST_NOW + 9_001L,
                ),
            PaperOrderSubmitError.NO_TRUSTED_EXECUTION_PRICE to
                submitTestGateInput().copy(executionReference = null),
            PaperOrderSubmitError.NO_TRUSTED_EXECUTION_PRICE to
                submitTestGateInput().copy(
                    executionReference = ExecutionReferencePriceEvaluator().evaluate(
                        symbol = "SPY",
                        observation = LiveQuoteObservation(
                            bid = 500.0,
                            ask = 500.0,
                            provenance = MarketDataProvenance.ALPACA_TEST_SYNTHETIC,
                            eventTimeEpochMillis = SUBMIT_TEST_NOW - 1_000L,
                            receivedAtEpochMillis = SUBMIT_TEST_NOW - 1_000L,
                        ),
                        nowEpochMillis = SUBMIT_TEST_NOW,
                    ),
                ),
            PaperOrderSubmitError.PRICE_NOT_FRESH to
                submitTestGateInput().copy(executionReference = submitTestPrice(symbol = "QQQ")),
            PaperOrderSubmitError.PRICE_NOT_FRESH to
                submitTestGateInput().copy(
                    preview = submitTestPreview(priceSource = MarketPriceSource.ROOM_BAR_CLOSE),
                ),
            PaperOrderSubmitError.PRICE_DRIFT_EXCEEDED to
                submitTestGateInput().copy(executionReference = submitTestPrice(price = 501.26)),
            PaperOrderSubmitError.PREFLIGHT_BLOCKED to
                submitTestGateInput().copy(preflight = submitTestPreflight(PreflightStatus.BLOCKED)),
            PaperOrderSubmitError.READINESS_MISSING to
                submitTestGateInput().copy(disabledReadinessStatus = null),
            PaperOrderSubmitError.REVIEW_ROW_MISSING to
                submitTestGateInput().copy(reviewQueueMatch = false),
            PaperOrderSubmitError.PREVIEW_MISMATCH to
                submitTestGateInput().copy(
                    request = submitTestRequest().copy(quantity = 2.0),
                ),
            PaperOrderSubmitError.CONFIRMATION_MISSING to
                submitTestGateInput(confirmation = null),
            PaperOrderSubmitError.CONFIRMATION_EXPIRED to
                submitTestGateInput().copy(
                    confirmation = submitTestConfirmation().copy(expiresAtEpochMillis = 9_999L),
                ),
            PaperOrderSubmitError.DUPLICATE_PREVIEW to
                submitTestGateInput().copy(duplicatePreview = true),
            PaperOrderSubmitError.DUPLICATE_CLIENT_ORDER_ID to
                submitTestGateInput().copy(duplicateClientOrderId = true),
            PaperOrderSubmitError.SUBMIT_ALREADY_IN_FLIGHT to
                submitTestGateInput().copy(submitInFlight = true),
        )
        return cases.map { (expected, input) ->
            DynamicTest.dynamicTest("blocks $expected") {
                val decision = enabledGate().evaluate(input)
                assertTrue(decision is PaperManualSubmitGateDecision.Blocked)
                assertTrue(
                    (decision as PaperManualSubmitGateDecision.Blocked).reasons.contains(expected),
                    "Expected $expected in ${decision.reasons}",
                )
            }
        }
    }

    @Test
    fun `compile feature OFF and session disarmed both block`() {
        val compileOff = PaperManualSubmitGate(PaperManualExecutionFeatureGate(false))
            .evaluate(submitTestGateInput()) as PaperManualSubmitGateDecision.Blocked
        assertTrue(compileOff.reasons.contains(PaperOrderSubmitError.FEATURE_DISABLED))

        val sessionOff = enabledGate().evaluate(submitTestGateInput(sessionArmed = false))
            as PaperManualSubmitGateDecision.Blocked
        assertTrue(sessionOff.reasons.contains(PaperOrderSubmitError.FEATURE_DISABLED))
    }

    @Test
    fun `approved fresher trusted quote within drift tolerance passes full gate`() {
        val decision = enabledGate().evaluate(
            submitTestGateInput().copy(
                executionReference = submitTestPrice(price = 500.50, ageMillis = 100L),
            ),
        )
        assertEquals(PaperManualSubmitGateDecision.Allowed, decision)
    }

    @Test
    fun `a legacy Room preview with a trusted final quote is blocked, never allowed`() {
        val decision = enabledGate().evaluate(
            submitTestGateInput().copy(
                preview = submitTestPreview(priceSource = MarketPriceSource.ROOM_BAR_CLOSE),
            ),
        )
        assertTrue(decision is PaperManualSubmitGateDecision.Blocked)
        assertTrue(
            (decision as PaperManualSubmitGateDecision.Blocked).reasons
                .contains(PaperOrderSubmitError.PRICE_NOT_FRESH),
        )
    }

    @Test
    fun `emergency disable overrides armed valid flow`() {
        val feature = PaperManualExecutionFeatureGate(true)
        feature.activateEmergencyDisable()
        val decision = PaperManualSubmitGate(feature).evaluate(submitTestGateInput())
            as PaperManualSubmitGateDecision.Blocked
        assertTrue(decision.reasons.contains(PaperOrderSubmitError.EMERGENCY_DISABLED))
    }

    private fun enabledGate(): PaperManualSubmitGate =
        PaperManualSubmitGate(PaperManualExecutionFeatureGate(true))
}
