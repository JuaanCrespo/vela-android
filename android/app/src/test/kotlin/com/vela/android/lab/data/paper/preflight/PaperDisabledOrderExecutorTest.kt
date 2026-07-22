package com.vela.android.lab.data.paper.preflight

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PaperDisabledOrderExecutorTest {

    private fun preview(): PaperOrderPayloadPreview = PaperOrderPayloadPreview(
        previewId = "preview-disabled-1",
        linkedClientDryRunId = "dry-run-disabled-1",
        symbol = "SPY",
        side = OrderSide.BUY,
        type = OrderType.MARKET,
        timeInForce = TimeInForce.DAY,
        quantity = 1.0,
        limitPriceUsd = null,
        estimatedNotionalUsd = 400.25,
        priceSource = "ROOM_BAR_CLOSE",
        priceFreshness = "FRESH",
        relatedSignalState = "NEUTRAL",
        generatedAtEpochMillis = 100L,
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
    )

    @Test
    fun `disabled executor always returns EXECUTION_DISABLED`() {
        val executor = PaperDisabledOrderExecutor(clock = { Instant.ofEpochMilli(321L) })

        val result = executor.attemptDisabledExecution(preview())

        assertEquals(DisabledExecutionStatus.EXECUTION_DISABLED, result.result)
        assertEquals(DisabledExecutionResult.REASON, result.reason)
        assertEquals(321L, result.createdAtEpochMillis)
    }

    @Test
    fun `disabled result links only local preview identifiers`() {
        val result = PaperDisabledOrderExecutor().attemptDisabledExecution(preview())

        assertEquals("preview-disabled-1", result.previewId)
        assertEquals("dry-run-disabled-1", result.linkedClientDryRunId)
    }

    @Test
    fun `disabled executor exposes exactly one action method`() {
        val publicActions = PaperDisabledOrderExecutor::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .map { it.name }
            .toSet()

        assertEquals(setOf("attemptDisabledExecution"), publicActions)
    }

    @Test
    fun `disabled executor exposes no mutation-shaped methods`() {
        val forbidden = listOf(
            "submit", "cancel", "replace", "closeposition", "post", "delete", "patch", "live",
        )
        for (method in PaperDisabledOrderExecutor::class.java.declaredMethods) {
            val name = method.name.lowercase()
            for (bad in forbidden) {
                assertFalse(name.contains(bad), "Executor method '$name' contains '$bad'")
            }
        }
    }

    @Test
    fun `disabled executor has no network HTTP or credential dependency`() {
        val forbidden = listOf("AlpacaHttpClient", "OkHttp", "Request", "Credential", "Account")
        for (field in PaperDisabledOrderExecutor::class.java.declaredFields) {
            val typeName = field.type.name
            for (bad in forbidden) {
                assertFalse(
                    typeName.contains(bad, ignoreCase = true),
                    "Executor field '${field.name}' depends on $typeName",
                )
            }
        }
    }

    @Test
    fun `disabled result stores no credential account or API header field`() {
        val forbidden = listOf("key", "secret", "password", "account", "header", "apca")
        for (field in DisabledExecutionResult::class.java.declaredFields) {
            val name = field.name.lowercase()
            for (bad in forbidden) {
                assertFalse(name.contains(bad), "Result field '$name' contains '$bad'")
            }
        }
    }

    @Test
    fun `disabled result reason cannot be changed by copy`() {
        val result = PaperDisabledOrderExecutor().attemptDisabledExecution(preview())

        assertThrows(IllegalArgumentException::class.java) {
            result.copy(reason = "allowed")
        }
    }

    @Test
    fun `repeated attempts remain local disabled results`() {
        val executor = PaperDisabledOrderExecutor()

        repeat(3) {
            assertEquals(
                DisabledExecutionStatus.EXECUTION_DISABLED,
                executor.attemptDisabledExecution(preview()).result,
            )
        }
    }
}
