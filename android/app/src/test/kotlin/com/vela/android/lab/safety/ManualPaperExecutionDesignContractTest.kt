package com.vela.android.lab.safety

import com.vela.android.lab.data.paper.AlpacaHttpClient
import com.vela.android.lab.data.paper.preflight.DisabledExecutionStatus
import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.data.paper.preflight.OrderType
import com.vela.android.lab.data.paper.preflight.PaperDisabledOrderExecutor
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadFields
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewStatus
import com.vela.android.lab.data.paper.preflight.PaperTradingExecutionGuard
import com.vela.android.lab.data.paper.preflight.TimeInForce
import java.io.File
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Phase 2.t historical design contract plus the approved Phase 2.v default-off amendment. */
class ManualPaperExecutionDesignContractTest {

    @Test
    fun `design document preserves all required sections and historical Phase 2 t NO-GO`() {
        val text = designDocument().readText()
        val requiredSections = ('A'..'L').map { "## $it." }

        assertTrue(text.startsWith("# Manual Paper execution design specification (Phase 2.t)"))
        for (section in requiredSections) {
            assertTrue(text.contains(section), "Missing design section: $section")
        }
        assertTrue(text.contains("**NO-GO for implementation in this phase.**"))
        assertTrue(text.contains("This subsection is the historical Phase 2.t decision."))
        assertTrue(text.contains("It is safe to start a future implementation phase only after human approval"))
    }

    @Test
    fun `design records implemented default-off POST boundary and separated components`() {
        val text = designDocument().readText()
        val requiredFutureComponents = listOf(
            "PaperOrderSubmitClient",
            "PaperOrderSubmitRequest",
            "PaperOrderSubmitResult",
            "PaperOrderSubmitAuditEntity",
            "PaperManualSubmitViewModel",
            "PaperManualSubmitExecutor",
        )

        assertTrue(text.contains("**PHASE 2.v IMPLEMENTED, DEFAULT OFF.**"))
        assertTrue(text.contains("`AlpacaHttpClient` remains GET-only and unchanged."))
        for (component in requiredFutureComponents) {
            assertTrue(text.contains(component), "Missing future design component: $component")
        }
    }

    @Test
    fun `Phase 2 t leaves frozen execution boundaries disabled`() {
        assertFalse(PaperTradingExecutionGuard.canExecuteOrders)
        val httpMethods = AlpacaHttpClient::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .map { it.name }
            .toSet()
        assertEquals(setOf("executeGet"), httpMethods)

        val disabledResult = PaperDisabledOrderExecutor(
            clock = { Instant.ofEpochMilli(2_000L) },
        ).attemptDisabledExecution(safePreview())
        assertEquals(DisabledExecutionStatus.EXECUTION_DISABLED, disabledResult.result)
    }

    private fun designDocument(): File {
        val relativePath = "docs/manual-paper-execution-design.md"
        val candidates = listOf(
            File("../../$relativePath"),
            File("../$relativePath"),
            File(relativePath),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Cannot locate $relativePath from ${File(".").absolutePath}")
    }

    private fun safePreview(): PaperOrderPayloadPreview = PaperOrderPayloadPreview(
        previewId = "phase-2t-preview",
        linkedClientDryRunId = "phase-2t-dry-run",
        symbol = "SPY",
        side = OrderSide.BUY,
        type = OrderType.MARKET,
        timeInForce = TimeInForce.DAY,
        quantity = 1.0,
        limitPriceUsd = null,
        estimatedNotionalUsd = 500.0,
        priceSource = "ROOM_BAR_CLOSE",
        priceFreshness = "FRESH",
        relatedSignalState = "NEUTRAL",
        generatedAtEpochMillis = 1_000L,
        status = PaperOrderPayloadPreviewStatus.READY_PREVIEW,
        warningMessages = emptyList(),
        payloadFields = PaperOrderPayloadFields(
            symbol = "SPY",
            side = "buy",
            type = "market",
            timeInForce = "day",
            quantity = 1.0,
            limitPriceUsd = null,
        ),
        executionEnabled = false,
        endpointPreview = PaperOrderPayloadPreview.ENDPOINT_DISABLED,
        httpMethodPreview = PaperOrderPayloadPreview.HTTP_METHOD_POST_DISABLED,
    )
}
