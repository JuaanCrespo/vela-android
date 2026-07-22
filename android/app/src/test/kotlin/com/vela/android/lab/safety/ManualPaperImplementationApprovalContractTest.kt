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

/** Phase 2.u approval-package contract. It intentionally adds no execution surface. */
class ManualPaperImplementationApprovalContractTest {

    @Test
    fun `approval package requires Juan approval and names exact future phase`() {
        val text = approvalPackage().readText()

        assertTrue(text.startsWith("# Manual Paper implementation approval package (Phase 2.u)"))
        assertTrue(text.contains("## B. HUMAN APPROVAL REQUIRED BEFORE IMPLEMENTATION"))
        assertTrue(text.contains("No developer or AI agent may implement Paper submission until **Juan explicitly approves it**."))
        assertTrue(text.contains("Phase 2.v — Manual Paper submit implementation, Paper-only, one-shot, user-confirmed"))
        assertTrue(text.contains("be written in `docs/phase-1-progress.md` before implementation begins"))
    }

    @Test
    fun `approval package contains exact diff safety and GO NO-GO decisions`() {
        val text = approvalPackage().readText()
        val requiredSections = ('A'..'J').map { "## $it." }
        for (section in requiredSections) {
            assertTrue(text.contains(section), "Missing approval-package section: $section")
        }

        assertTrue(text.contains("PaperOrderSubmitClient.kt"))
        assertTrue(text.contains("PaperManualSubmitEndpointGuard.kt"))
        assertTrue(text.contains("PaperOrderSubmitAuditEntity.kt"))
        assertTrue(text.contains("PaperManualSubmitViewModel.kt"))
        assertTrue(text.contains("GO to review implementation plan"))
        assertTrue(text.contains("NO-GO to implement execution in this phase"))
        assertTrue(text.contains("NO-GO to run `POST /v2/orders`"))
        assertTrue(text.contains("Human approval is required before any future Phase 2.v implementation."))
    }

    @Test
    fun `Phase 2 u leaves current execution boundary disabled`() {
        assertFalse(PaperTradingExecutionGuard.canExecuteOrders)
        val httpMethods = AlpacaHttpClient::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .map { it.name }
            .toSet()
        assertEquals(setOf("executeGet"), httpMethods)

        val result = PaperDisabledOrderExecutor(
            clock = { Instant.ofEpochMilli(2_000L) },
        ).attemptDisabledExecution(safePreview())
        assertEquals(DisabledExecutionStatus.EXECUTION_DISABLED, result.result)
    }

    private fun approvalPackage(): File {
        val relativePath = "docs/manual-paper-implementation-approval-package.md"
        val candidates = listOf(
            File("../../$relativePath"),
            File("../$relativePath"),
            File(relativePath),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Cannot locate $relativePath from ${File(".").absolutePath}")
    }

    private fun safePreview(): PaperOrderPayloadPreview = PaperOrderPayloadPreview(
        previewId = "phase-2u-preview",
        linkedClientDryRunId = "phase-2u-dry-run",
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
