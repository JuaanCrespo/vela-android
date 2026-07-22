package com.vela.android.lab.data.paper.preflight

import com.vela.android.lab.core.OperationMode
import com.vela.android.lab.data.paper.AlpacaHttpClient
import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint
import com.vela.android.lab.state.AppState
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperOrderPayloadPreviewBuilderTest {

    private fun draft(
        status: PaperOrderRequestDraftStatus = PaperOrderRequestDraftStatus.READY_LOCAL,
        warnings: List<String> = emptyList(),
        quantity: Double = 1.0,
        type: OrderType = OrderType.MARKET,
        limitPriceUsd: Double? = null,
    ): PaperOrderRequestDraft = PaperOrderRequestDraft(
        clientDryRunId = "dry-run-spy-1",
        symbol = "SPY",
        side = OrderSide.BUY,
        type = type,
        timeInForce = TimeInForce.DAY,
        quantity = quantity,
        limitPriceUsd = limitPriceUsd,
        estimatedNotionalUsd = 400.25,
        priceSource = "ROOM_BAR_CLOSE",
        priceFreshness = "FRESH",
        priceAgeMillis = 129_852L,
        relatedSignalState = "NEUTRAL",
        createdAtEpochMillis = 10L,
        status = status,
        warningMessages = warnings,
        executionEnabled = false,
    )

    private fun deterministicBuilder(): PaperOrderPayloadPreviewBuilder =
        PaperOrderPayloadPreviewBuilder(
            previewIdFactory = { "preview-1" },
            clock = { Instant.ofEpochMilli(123_456L) },
        )

    @Test
    fun `valid local draft builds complete payload preview`() {
        val validation = deterministicBuilder().build(draft())
        assertTrue(validation is PaperOrderPayloadPreviewValidation.Valid)
        val preview = (validation as PaperOrderPayloadPreviewValidation.Valid).preview

        assertEquals("preview-1", preview.previewId)
        assertEquals("dry-run-spy-1", preview.linkedClientDryRunId)
        assertEquals("SPY", preview.symbol)
        assertEquals(OrderSide.BUY, preview.side)
        assertEquals(OrderType.MARKET, preview.type)
        assertEquals(TimeInForce.DAY, preview.timeInForce)
        assertEquals(1.0, preview.quantity)
        assertNull(preview.limitPriceUsd)
        assertEquals(400.25, preview.estimatedNotionalUsd)
        assertEquals("ROOM_BAR_CLOSE", preview.priceSource)
        assertEquals("FRESH", preview.priceFreshness)
        assertEquals("NEUTRAL", preview.relatedSignalState)
        assertEquals(123_456L, preview.generatedAtEpochMillis)
        assertEquals(PaperOrderPayloadPreviewStatus.READY_PREVIEW, preview.status)
        assertFalse(preview.executionEnabled)
        assertEquals("DISABLED", preview.endpointPreview)
        assertEquals("POST_DISABLED", preview.httpMethodPreview)
    }

    @Test
    fun `warning local draft builds warning payload preview`() {
        val preview = (
            deterministicBuilder().build(
                draft(
                    status = PaperOrderRequestDraftStatus.READY_LOCAL_WITH_WARNINGS,
                    warnings = listOf("US market is closed at preflight time."),
                ),
            ) as PaperOrderPayloadPreviewValidation.Valid
            ).preview

        assertEquals(PaperOrderPayloadPreviewStatus.READY_PREVIEW_WITH_WARNINGS, preview.status)
        assertEquals(listOf("US market is closed at preflight time."), preview.warningMessages)
    }

    @Test
    fun `invalid draft cannot build payload preview`() {
        val validation = deterministicBuilder().build(draft(quantity = 0.0))
        assertTrue(validation is PaperOrderPayloadPreviewValidation.Rejected)
        validation as PaperOrderPayloadPreviewValidation.Rejected
        assertEquals(PaperOrderPayloadPreviewRejection.INVALID_DRAFT, validation.reason)
    }

    @Test
    fun `draft status surface has no blocked or executable value`() {
        assertEquals(
            setOf(
                PaperOrderRequestDraftStatus.READY_LOCAL,
                PaperOrderRequestDraftStatus.READY_LOCAL_WITH_WARNINGS,
            ),
            PaperOrderRequestDraftStatus.entries.toSet(),
        )
    }

    @Test
    fun `payload fields contain only theoretical order values`() {
        val fields = (
            deterministicBuilder().build(draft()) as PaperOrderPayloadPreviewValidation.Valid
            ).preview.payloadFields
        assertEquals("SPY", fields.symbol)
        assertEquals("buy", fields.side)
        assertEquals("market", fields.type)
        assertEquals("day", fields.timeInForce)
        assertEquals(1.0, fields.quantity)
        assertNull(fields.limitPriceUsd)
    }

    @Test
    fun `LIMIT payload preview retains limit field`() {
        val fields = (
            deterministicBuilder().build(
                draft(type = OrderType.LIMIT, limitPriceUsd = 399.5),
            ) as PaperOrderPayloadPreviewValidation.Valid
            ).preview.payloadFields
        assertEquals("limit", fields.type)
        assertEquals(399.5, fields.limitPriceUsd)
    }

    @Test
    fun `repeated previews receive distinct ids`() {
        val builder = PaperOrderPayloadPreviewBuilder()
        val first = (builder.build(draft()) as PaperOrderPayloadPreviewValidation.Valid).preview
        val second = (builder.build(draft()) as PaperOrderPayloadPreviewValidation.Valid).preview
        assertNotEquals(first.previewId, second.previewId)
    }

    @Test
    fun `disabled safety markers cannot be changed through copy`() {
        val preview = (
            deterministicBuilder().build(draft()) as PaperOrderPayloadPreviewValidation.Valid
            ).preview
        assertThrows(IllegalArgumentException::class.java) {
            preview.copy(executionEnabled = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            preview.copy(endpointPreview = "https://paper-api.alpaca.markets/v2/orders")
        }
        assertThrows(IllegalArgumentException::class.java) {
            preview.copy(httpMethodPreview = "POST")
        }
    }

    @Test
    fun `preview and payload fields contain no credential account or header field`() {
        val forbidden = listOf(
            "secret", "apikey", "apca", "accountid", "credential", "password", "header",
        )
        val fields = (
            PaperOrderPayloadPreview::class.java.declaredFields.asSequence() +
                PaperOrderPayloadFields::class.java.declaredFields.asSequence()
            ).map { it.name.lowercase() }.filterNot { it.contains('$') }.toList()
        for (field in fields) {
            for (bad in forbidden) {
                assertFalse(field.contains(bad), "Preview field '$field' contains '$bad'")
            }
        }
    }

    @Test
    fun `preview string contains no credential or account value`() {
        val preview = (
            deterministicBuilder().build(draft()) as PaperOrderPayloadPreviewValidation.Valid
            ).preview.toString()
        assertFalse(preview.contains("topsecretvalue"))
        assertFalse(preview.contains("PKABCDEF1234"))
        assertFalse(preview.contains("accountId", ignoreCase = true))
    }

    @Test
    fun `builder has no network or HTTP request dependency`() {
        val dependencyTypes = PaperOrderPayloadPreviewBuilder::class.java.declaredFields
            .map { it.type.name.lowercase() }
        assertTrue(dependencyTypes.none { type ->
            type.contains("okhttp") || type.contains("request") ||
                type.contains("httpclient") || type.contains("retrofit") || type.contains("network")
        })
    }

    @Test
    fun `builder exposes no submit cancel replace execute or mutation method`() {
        val forbidden = listOf(
            "submit", "cancel", "replace", "execute", "placeorder", "post",
            "patch", "delete", "openposition", "closeposition", "account",
        )
        val methods = PaperOrderPayloadPreviewBuilder::class.java.declaredMethods
            .map { it.name }.filterNot { it.contains('$') }
        for (method in methods) {
            for (bad in forbidden) {
                assertFalse(method.lowercase().contains(bad), "Builder method '$method' contains '$bad'")
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
    fun `paper mutation paths and LIVE trading host remain rejected`() {
        val forbidden = listOf(
            "https://paper-api.alpaca.markets/v2/orders",
            "https://paper-api.alpaca.markets/v2/orders/id",
            "https://paper-api.alpaca.markets/v2/positions/SPY",
            "https://paper-api.alpaca.markets/v2/account/configurations",
            "https://api.alpaca.markets/v2/orders",
        )
        for (url in forbidden) {
            assertFalse(AlpacaPaperTradingEndpoint.isSafePaperReadOnlyGet(url), url)
        }
    }

    @Test
    fun `REAL remains locked in read only mode`() {
        val state = AppState()
        assertTrue(state.realModeLocked)
        assertEquals(OperationMode.READ_ONLY, state.mode)
    }
}
