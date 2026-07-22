package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.data.paper.AlpacaHttpClient
import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint
import com.vela.android.lab.core.OperationMode
import com.vela.android.lab.state.AppState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperOrderRequestDraftBuilderTest {

    private val builder = PaperOrderRequestDraftBuilder()

    private fun intent(
        type: OrderType = OrderType.MARKET,
        limitPriceUsd: Double? = null,
    ): PaperOrderIntent = PaperOrderIntent(
        clientDryRunId = "dry-run-spy-1",
        symbol = "SPY",
        side = OrderSide.BUY,
        type = type,
        tif = TimeInForce.DAY,
        quantity = 1.0,
        limitPriceUsd = limitPriceUsd,
        source = IntentSource.MANUAL_DRY_RUN,
        createdAtEpochMillis = 123_456L,
    )

    private fun result(
        status: PreflightStatus = PreflightStatus.ALLOWED_DRY_RUN,
        intent: PaperOrderIntent = intent(),
        blocks: List<PreflightBlockReason> = emptyList(),
        warnings: List<PreflightWarning> = emptyList(),
    ): PaperOrderPreflightResult = PaperOrderPreflightResult(
        intent = intent,
        status = status,
        estimatedNotionalUsd = 400.25,
        estimatedBuyingPowerAfterUsd = 199_599.75,
        allocationPercentAfter = 0.4,
        positionImpactQty = 1.0,
        relatedSignalState = "NEUTRAL",
        marketOpen = true,
        blockReasons = blocks,
        warnings = warnings,
        priceSource = "ROOM_BAR_CLOSE",
        priceFreshness = "FRESH",
        priceAgeMillis = 197_683L,
    )

    @Test
    fun `ALLOWED_DRY_RUN builds a complete local draft`() {
        val validation = builder.build(result())
        assertTrue(validation is PaperOrderRequestDraftValidation.Valid)
        val draft = (validation as PaperOrderRequestDraftValidation.Valid).draft

        assertEquals("dry-run-spy-1", draft.clientDryRunId)
        assertEquals("SPY", draft.symbol)
        assertEquals(OrderSide.BUY, draft.side)
        assertEquals(OrderType.MARKET, draft.type)
        assertEquals(TimeInForce.DAY, draft.timeInForce)
        assertEquals(1.0, draft.quantity)
        assertNull(draft.limitPriceUsd)
        assertEquals(400.25, draft.estimatedNotionalUsd)
        assertEquals("ROOM_BAR_CLOSE", draft.priceSource)
        assertEquals("FRESH", draft.priceFreshness)
        assertEquals(197_683L, draft.priceAgeMillis)
        assertEquals("NEUTRAL", draft.relatedSignalState)
        assertEquals(123_456L, draft.createdAtEpochMillis)
        assertEquals(PaperOrderRequestDraftStatus.READY_LOCAL, draft.status)
        assertTrue(draft.warningMessages.isEmpty())
        assertFalse(draft.executionEnabled)
    }

    @Test
    fun `WARNING_ONLY builds a draft and retains warning messages`() {
        val validation = builder.build(
            result(
                status = PreflightStatus.WARNING_ONLY,
                warnings = listOf(PreflightWarning.MarketClosed),
            ),
        ) as PaperOrderRequestDraftValidation.Valid

        assertEquals(
            PaperOrderRequestDraftStatus.READY_LOCAL_WITH_WARNINGS,
            validation.draft.status,
        )
        assertEquals(
            listOf("US market is closed at preflight time."),
            validation.draft.warningMessages,
        )
    }

    @Test
    fun `BLOCKED preflight cannot build a draft`() {
        val validation = builder.build(
            result(
                status = PreflightStatus.BLOCKED,
                blocks = listOf(PreflightBlockReason.MissingLatestPrice),
            ),
        )
        assertTrue(validation is PaperOrderRequestDraftValidation.Rejected)
        validation as PaperOrderRequestDraftValidation.Rejected
        assertEquals(PaperOrderRequestDraftRejection.BLOCKED_PREFLIGHT, validation.reason)
        assertTrue(validation.message.contains("rejected", ignoreCase = true))
        assertTrue(validation.message.contains("market data", ignoreCase = true))
    }

    @Test
    fun `approved-looking result with block reasons is rejected defensively`() {
        val validation = builder.build(
            result(blocks = listOf(PreflightBlockReason.AccountBlocked)),
        ) as PaperOrderRequestDraftValidation.Rejected
        assertEquals(PaperOrderRequestDraftRejection.BLOCKED_PREFLIGHT, validation.reason)
    }

    @Test
    fun `LIMIT draft retains validated limit price`() {
        val validation = builder.build(
            result(intent = intent(type = OrderType.LIMIT, limitPriceUsd = 399.5)),
        ) as PaperOrderRequestDraftValidation.Valid
        assertEquals(399.5, validation.draft.limitPriceUsd)
    }

    @Test
    fun `invalid LIMIT price is rejected`() {
        val validation = builder.build(
            result(intent = intent(type = OrderType.LIMIT, limitPriceUsd = null)),
        ) as PaperOrderRequestDraftValidation.Rejected
        assertEquals(PaperOrderRequestDraftRejection.INVALID_DRAFT_INPUT, validation.reason)
    }

    @Test
    fun `executionEnabled cannot be changed to true even through copy`() {
        val draft = (builder.build(result()) as PaperOrderRequestDraftValidation.Valid).draft
        assertFalse(draft.executionEnabled)
        assertThrows(IllegalArgumentException::class.java) {
            draft.copy(executionEnabled = true)
        }
    }

    @Test
    fun `draft has no endpoint credential API key or account id field`() {
        val forbidden = listOf(
            "endpoint", "url", "http", "secret", "apikey", "apca",
            "accountid", "credential", "password",
        )
        val fields = PaperOrderRequestDraft::class.java.declaredFields
            .map { it.name.lowercase() }
            .filterNot { it.contains('$') }
        assertNotNull(fields.singleOrNull { it == "executionenabled" })
        for (field in fields) {
            for (bad in forbidden) {
                assertFalse(field.contains(bad), "Draft field '$field' contains '$bad'")
            }
        }
    }

    @Test
    fun `draft string contains no credential or account value`() {
        val draft = (builder.build(result()) as PaperOrderRequestDraftValidation.Valid).draft
        val rendered = draft.toString()
        assertFalse(rendered.contains("topsecretvalue"))
        assertFalse(rendered.contains("PKABCDEF1234"))
        assertFalse(rendered.contains("accountId", ignoreCase = true))
    }

    @Test
    fun `builder has no network dependency`() {
        assertEquals(0, PaperOrderRequestDraftBuilder::class.java.constructors.single().parameterCount)
        val dependencyTypes = PaperOrderRequestDraftBuilder::class.java.declaredFields
            .map { it.type.name.lowercase() }
        assertTrue(dependencyTypes.none { type ->
            type.contains("okhttp") || type.contains("httpclient") ||
                type.contains("retrofit") || type.contains("network")
        })
    }

    @Test
    fun `builder exposes no submit cancel replace execute or mutation method`() {
        val forbidden = listOf(
            "submit", "cancel", "replace", "execute", "placeorder",
            "openposition", "closeposition", "post", "patch", "delete", "account",
        )
        val methods = PaperOrderRequestDraftBuilder::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        for (method in methods) {
            val lower = method.lowercase()
            for (bad in forbidden) {
                assertFalse(lower.contains(bad), "Builder method '$method' contains '$bad'")
            }
        }
    }

    @Test
    fun `execution guard and HTTP boundary remain disabled and GET-only`() {
        assertFalse(PaperTradingExecutionGuard.canExecuteOrders)
        assertEquals(
            setOf("executeGet"),
            AlpacaHttpClient::class.java.declaredMethods.map { it.name }.toSet(),
        )
    }

    @Test
    fun `paper order paths and LIVE host remain rejected`() {
        val forbidden = listOf(
            "https://paper-api.alpaca.markets/v2/orders",
            "https://paper-api.alpaca.markets/v2/orders/draft-id",
            "https://paper-api.alpaca.markets/v2/positions/SPY",
            "https://paper-api.alpaca.markets/v2/account/configurations",
            "https://api.alpaca.markets/v2/orders",
        )
        for (url in forbidden) {
            assertFalse(AlpacaPaperTradingEndpoint.isSafePaperReadOnlyGet(url), url)
        }
    }

    @Test
    fun `manual source and REAL lock remain the only safe state`() {
        assertEquals(setOf(IntentSource.MANUAL_DRY_RUN), IntentSource.entries.toSet())
        val state = AppState()
        assertTrue(state.realModeLocked)
        assertEquals(OperationMode.READ_ONLY, state.mode)
    }
}
