package com.vela.android.lab.data.paper.preflight

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperExecutionReadinessCheckerTest {

    private fun preview(symbol: String = "SPY"): PaperOrderPayloadPreview =
        PaperOrderPayloadPreview(
            previewId = "preview-r-1",
            linkedClientDryRunId = "dry-run-r-1",
            symbol = symbol,
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
                symbol = symbol,
                side = "buy",
                type = "market",
                timeInForce = "day",
                quantity = 1.0,
                limitPriceUsd = null,
            ),
        )

    private fun checker(): PaperExecutionReadinessChecker =
        PaperExecutionReadinessChecker(clock = { Instant.ofEpochMilli(999L) })

    @Test
    fun `valid payload preview is ready but execution disabled`() {
        val result = checker().check(
            preview = preview(),
            realLocked = true,
            credentialsConfigured = true,
        )

        assertEquals(PaperExecutionReadinessStatus.READY_BUT_EXECUTION_DISABLED, result.status)
        assertTrue(result.hasValidPreview)
        assertEquals("preview-r-1", result.previewId)
        assertEquals("dry-run-r-1", result.linkedClientDryRunId)
        assertEquals(999L, result.checkedAtEpochMillis)
    }

    @Test
    fun `all execution capability flags remain disabled and REAL remains locked`() {
        val result = checker().check(preview(), realLocked = true, credentialsConfigured = true)

        assertFalse(result.executionEnabled)
        assertTrue(result.realLocked)
        assertFalse(result.liveEndpointAllowed)
        assertFalse(result.paperPostOrdersAllowed)
        assertFalse(result.autoPaperEnabled)
        assertFalse(result.foregroundServiceEnabled)
    }

    @Test
    fun `readiness reasons explicitly retain every disabled capability`() {
        val result = checker().check(preview(), realLocked = true, credentialsConfigured = true)

        assertTrue(PaperExecutionReadinessReason.EXECUTION_DISABLED in result.blockingReasons)
        assertTrue(PaperExecutionReadinessReason.PAPER_POST_ORDERS_DISABLED in result.blockingReasons)
        assertTrue(PaperExecutionReadinessReason.LIVE_ENDPOINT_DISABLED in result.blockingReasons)
        assertTrue(PaperExecutionReadinessReason.AUTO_PAPER_DISABLED in result.blockingReasons)
        assertTrue(PaperExecutionReadinessReason.FOREGROUND_SERVICE_DISABLED in result.blockingReasons)
    }

    @Test
    fun `credential absence is a boolean-only warning and does not enable anything`() {
        val result = checker().check(preview(), realLocked = true, credentialsConfigured = false)

        assertEquals(PaperExecutionReadinessStatus.READY_BUT_EXECUTION_DISABLED, result.status)
        assertFalse(result.credentialsConfigured)
        assertEquals(
            listOf(PaperExecutionReadinessReason.CREDENTIALS_NOT_CONFIGURED),
            result.warnings,
        )
        assertFalse(result.toString().contains("key", ignoreCase = true))
        assertFalse(result.toString().contains("secret", ignoreCase = true))
    }

    @Test
    fun `invalid payload preview reports not ready`() {
        val result = checker().check(
            preview = preview(symbol = ""),
            realLocked = true,
            credentialsConfigured = true,
        )

        assertEquals(PaperExecutionReadinessStatus.NOT_READY, result.status)
        assertFalse(result.hasValidPreview)
        assertTrue(PaperExecutionReadinessReason.INVALID_PAYLOAD_PREVIEW in result.blockingReasons)
    }

    @Test
    fun `unlocked REAL input is blocked`() {
        val result = checker().check(preview(), realLocked = false, credentialsConfigured = true)

        assertEquals(PaperExecutionReadinessStatus.BLOCKED, result.status)
        assertFalse(result.realLocked)
        assertTrue(PaperExecutionReadinessReason.REAL_MODE_NOT_LOCKED in result.blockingReasons)
        assertFalse(result.executionEnabled)
    }

    @Test
    fun `snapshot copy cannot enable execution or Paper POST orders`() {
        val result = checker().check(preview(), realLocked = true, credentialsConfigured = true)

        assertThrows(IllegalArgumentException::class.java) {
            result.copy(executionEnabled = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            result.copy(paperPostOrdersAllowed = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            result.copy(liveEndpointAllowed = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            result.copy(autoPaperEnabled = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            result.copy(foregroundServiceEnabled = true)
        }
    }

    @Test
    fun `readiness snapshot persists no credential account or header fields`() {
        val forbidden = listOf("key", "secret", "password", "accountid", "header", "apca")
        val fieldNames = PaperExecutionReadinessSnapshot::class.java.declaredFields
            .map { it.name.lowercase() }

        for (name in fieldNames) {
            for (bad in forbidden) {
                assertFalse(name.contains(bad), "Readiness field '$name' contains '$bad'")
            }
        }
    }

    @Test
    fun `checker has no network dependency and no mutation method`() {
        val forbiddenTypes = listOf("AlpacaHttpClient", "OkHttp", "Request", "Credential")
        for (field in PaperExecutionReadinessChecker::class.java.declaredFields) {
            for (bad in forbiddenTypes) {
                assertFalse(
                    field.type.name.contains(bad, ignoreCase = true),
                    "Checker field '${field.name}' depends on ${field.type.name}",
                )
            }
        }

        val forbiddenMethods = listOf(
            "submit", "cancel", "replace", "closeposition", "post", "delete", "patch", "live",
        )
        for (method in PaperExecutionReadinessChecker::class.java.declaredMethods) {
            val name = method.name.lowercase()
            for (bad in forbiddenMethods) {
                assertFalse(name.contains(bad), "Checker method '$name' contains '$bad'")
            }
        }
    }

    @Test
    fun `execution guard remains false`() {
        assertFalse(PaperTradingExecutionGuard.canExecuteOrders)
    }
}
