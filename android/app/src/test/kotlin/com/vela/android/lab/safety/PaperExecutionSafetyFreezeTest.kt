package com.vela.android.lab.safety

import com.vela.android.lab.data.market.source.MarketDataSource
import com.vela.android.lab.data.market.source.MarketDataConfig
import com.vela.android.lab.data.market.source.alpaca.AlpacaStreamEndpoint
import com.vela.android.lab.data.paper.AlpacaHttpClient
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint
import com.vela.android.lab.data.paper.submit.AlpacaPaperOrderSubmitHttpClient
import com.vela.android.lab.data.paper.submit.AlpacaPaperSubmitEndpoint
import com.vela.android.lab.data.paper.preflight.DisabledExecutionStatus
import com.vela.android.lab.data.paper.preflight.IntentSource
import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.data.paper.preflight.OrderType
import com.vela.android.lab.data.paper.preflight.PaperDisabledOrderExecutor
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessReason
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessSnapshot
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessStatus
import com.vela.android.lab.data.paper.preflight.PaperExecutionReadinessChecker
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadFields
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreview
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewBuilder
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewStatus
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightEngine
import com.vela.android.lab.data.paper.preflight.PaperOrderRequestDraftBuilder
import com.vela.android.lab.data.paper.preflight.PaperTradingExecutionGuard
import com.vela.android.lab.data.paper.preflight.TimeInForce
import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity
import com.vela.android.lab.db.room.entities.PaperOrderPayloadPreviewEntity
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity
import com.vela.android.lab.state.AppState
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Phase 2.s freeze tests. Each invariant comes from
 * `docs/paper-execution-safety-freeze.md` Section 4. The intent of
 * this file is that any future refactor that softens the safety
 * boundary fails here visibly before it merges.
 *
 * The legacy read-only invariants remain frozen while the approved
 * Phase 2.v call graph is constrained to its exact manual boundary.
 */
class PaperExecutionSafetyFreezeTest {

    // ---- Invariant 1 ---------------------------------------------------

    @Test
    fun `INV1 - AppState default has realModeLocked true`() {
        val state = AppState()
        assertTrue(state.realModeLocked, "AppState default must keep REAL locked.")
    }

    // ---- Invariant 2 ---------------------------------------------------

    @Test
    fun `INV2 - PaperTradingExecutionGuard canExecuteOrders is compile-time false`() {
        assertFalse(
            PaperTradingExecutionGuard.canExecuteOrders,
            "PaperTradingExecutionGuard.canExecuteOrders must remain false.",
        )
    }

    // ---- Invariant 3 ---------------------------------------------------

    @Test
    fun `INV3 - AlpacaHttpClient interface declares exactly one method, executeGet`() {
        val declared = AlpacaHttpClient::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
            .toSet()
        assertEquals(
            setOf("executeGet"),
            declared,
            "AlpacaHttpClient surface must remain exactly {executeGet}.",
        )
    }

    // ---- Invariant 4 ---------------------------------------------------

    @Test
    fun `INV4 - AlpacaPaperTradingEndpoint allowlist is exactly the three GET URLs`() {
        assertEquals(
            setOf(
                "https://paper-api.alpaca.markets/v2/account",
                "https://paper-api.alpaca.markets/v2/clock",
                "https://paper-api.alpaca.markets/v2/positions",
            ),
            AlpacaPaperTradingEndpoint.ALLOWED_READ_ONLY_URLS,
        )
    }

    // ---- Invariant 5 ---------------------------------------------------

    @Test
    fun `INV5 - every production endpoint guard rejects LIVE trading host`() {
        val liveUrls = listOf(
            "https://api.alpaca.markets/v2/account",
            "https://api.alpaca.markets/v2/orders",
            "https://api.alpaca.markets/v2/positions",
        )
        for (url in liveUrls) {
            assertFalse(
                AlpacaPaperTradingEndpoint.isSafePaperReadOnlyGet(url),
                "LIVE host must be rejected: $url",
            )
            assertThrows(IllegalArgumentException::class.java) {
                AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
            }
            assertFalse(
                AlpacaStreamEndpoint.isSafeReadOnlyEndpoint(url),
                "Test-stream endpoint guard must reject LIVE trading host: $url",
            )
            assertFalse(
                AlpacaStreamEndpoint.isSafeMarketDataEndpoint(url),
                "Market-data endpoint guard must reject LIVE trading host: $url",
            )
            assertThrows(IllegalArgumentException::class.java) {
                MarketDataConfig(
                    source = MarketDataSource.ALPACA_PAPER,
                    endpoint = url,
                )
            }
        }
        assertTrue(
            AlpacaStreamEndpoint.ALLOWED_MARKET_DATA_URLS.none {
                it.startsWith("https://api.alpaca.markets", ignoreCase = true)
            },
        )
    }

    // ---- Invariant 6 ---------------------------------------------------

    @TestFactory
    fun `INV6 - Mutation-shape and live-substring URLs are rejected`(): List<DynamicTest> {
        val forbidden = listOf(
            "https://paper-api.alpaca.markets/v2/orders",
            "https://paper-api.alpaca.markets/v2/orders/abc-123",
            "https://paper-api.alpaca.markets/v2/positions/AAPL",
            "https://paper-api.alpaca.markets/v2/account/configurations",
            "https://paper-api.alpaca.markets/v2/account/activities",
            "https://paper-api.alpaca.markets/v2/portfolio/history",
            "https://paper-api.alpaca.markets/v2/livecheck",
            "https://paper-api.alpaca.markets/v2/LIVE",
        )
        return forbidden.map { url ->
            DynamicTest.dynamicTest("rejects: $url") {
                assertFalse(
                    AlpacaPaperTradingEndpoint.isSafePaperReadOnlyGet(url),
                    "URL must be rejected by paper guard: $url",
                )
            }
        }
    }

    // ---- Invariant 7 ---------------------------------------------------

    @Test
    fun `INV7 - PaperOrderPayloadPreview enforces DISABLED markers at construction`() {
        assertEquals("DISABLED", PaperOrderPayloadPreview.ENDPOINT_DISABLED)
        assertEquals("POST_DISABLED", PaperOrderPayloadPreview.HTTP_METHOD_POST_DISABLED)
        // Default constructor must succeed with the defaults.
        val ok = newSafePreview()
        assertEquals("DISABLED", ok.endpointPreview)
        assertEquals("POST_DISABLED", ok.httpMethodPreview)
        assertFalse(ok.executionEnabled)
        // .copy() that tries to flip executionEnabled must throw.
        assertThrows(IllegalArgumentException::class.java) {
            ok.copy(executionEnabled = true)
        }
        // .copy() that tries to change endpointPreview must throw.
        assertThrows(IllegalArgumentException::class.java) {
            ok.copy(endpointPreview = "https://paper-api.alpaca.markets/v2/orders")
        }
        // .copy() that tries to change httpMethodPreview must throw.
        assertThrows(IllegalArgumentException::class.java) {
            ok.copy(httpMethodPreview = "POST")
        }
    }

    // ---- Invariant 8 ---------------------------------------------------

    @TestFactory
    fun `INV8 - Persisted Room entity fields carry no credential or account-id shape`(): List<DynamicTest> {
        val forbiddenSubstrings = listOf(
            "secret", "apikey", "apca", "accountid", "credential",
            "password", "bearer", "authorization", "header",
        )
        val classes = listOf(
            PaperOrderDryRunAuditEntity::class.java,
            PaperOrderPayloadPreviewEntity::class.java,
            PaperOrderSubmitAuditEntity::class.java,
        )
        return classes.flatMap { cls ->
            cls.declaredFields.flatMap { field ->
                val lowerField = field.name.lowercase()
                forbiddenSubstrings.map { bad ->
                    DynamicTest.dynamicTest("${cls.simpleName}.${field.name} avoids '$bad'") {
                        assertFalse(
                            lowerField.contains(bad),
                            "${cls.simpleName} field '${field.name}' contains '$bad'",
                        )
                    }
                }
            }
        }
    }

    // ---- Invariant 9 ---------------------------------------------------

    @Test
    fun `INV9 - IntentSource enum has exactly one MANUAL_DRY_RUN value`() {
        val values = IntentSource.values().map { it.name }
        assertEquals(listOf("MANUAL_DRY_RUN"), values)
        assertFalse(values.any { it.contains("AUTO", ignoreCase = true) })
        assertFalse(values.any { it.contains("BACKGROUND", ignoreCase = true) })
    }

    // ---- Invariant 10 --------------------------------------------------

    @Test
    fun `INV10 - PaperDisabledOrderExecutor always returns EXECUTION_DISABLED`() {
        val executor = PaperDisabledOrderExecutor()
        val previews = listOf(
            newSafePreview(),
            newSafePreview().copy(
                previewId = "freeze-test-id-2",
                linkedClientDryRunId = "freeze-test-dryrun-2",
                symbol = "QQQ",
                side = OrderSide.SELL,
                quantity = 2.0,
                status = PaperOrderPayloadPreviewStatus.READY_PREVIEW_WITH_WARNINGS,
                warningMessages = listOf("local warning"),
                payloadFields = PaperOrderPayloadFields(
                    symbol = "QQQ",
                    side = "sell",
                    type = "market",
                    timeInForce = "day",
                    quantity = 2.0,
                    limitPriceUsd = null,
                ),
            ),
        )

        for (preview in previews) {
            val attempt = executor.attemptDisabledExecution(preview)
            assertEquals(DisabledExecutionStatus.EXECUTION_DISABLED, attempt.result)
            assertTrue(
                attempt.reason.lowercase().contains("execution is disabled"),
                "Reason must clearly state execution is disabled.",
            )
        }
    }

    // ---- Invariant 11 --------------------------------------------------

    @TestFactory
    fun `INV11 - Sensitive production classes declare no execution-shape method`(): List<DynamicTest> {
        val forbidden = listOf(
            "submitorder", "placeorder", "executeorder", "cancelorder",
            "replaceorder", "closeposition", "openposition", "executetrade",
        )
        val classes = listOf(
            AlpacaHttpClient::class.java,
            PaperTradingExecutionGuard::class.java,
            PaperDisabledOrderExecutor::class.java,
            PaperExecutionReadinessChecker::class.java,
            PaperOrderPayloadPreviewBuilder::class.java,
            PaperOrderPayloadPreviewRepository::class.java,
            PaperOrderRequestDraftBuilder::class.java,
            PaperOrderPreflightEngine::class.java,
            AlpacaPaperReadOnlyClient::class.java,
            AlpacaPaperTradingEndpoint::class.java,
        )
        return classes.flatMap { cls ->
            val methods = cls.declaredMethods
                .map { it.name }
                .filterNot { it.contains('$') }
            methods.flatMap { name ->
                val lower = name.lowercase()
                forbidden.map { bad ->
                    DynamicTest.dynamicTest("${cls.simpleName}.$name avoids '$bad'") {
                        assertFalse(
                            lower.contains(bad),
                            "${cls.simpleName} method '$name' contains forbidden '$bad'",
                        )
                    }
                }
            }
        }
    }

    // ---- Invariant 12 --------------------------------------------------

    @Test
    fun `INV12 - MarketDataSource enum has no ALPACA_LIVE value`() {
        val names = MarketDataSource.values().map { it.name }
        assertFalse(
            names.any { it.contains("LIVE", ignoreCase = false) },
            "MarketDataSource must never declare a LIVE value; got $names",
        )
    }

    // ---- Invariant 13 --------------------------------------------------

    @Test
    fun `INV13 - PaperExecutionReadinessSnapshot rejects every execution-enabling copy`() {
        val baseline = newSafeReadiness()
        assertFalse(baseline.executionEnabled)
        assertFalse(baseline.liveEndpointAllowed)
        assertFalse(baseline.paperPostOrdersAllowed)
        assertFalse(baseline.autoPaperEnabled)
        assertFalse(baseline.foregroundServiceEnabled)

        assertThrows(IllegalArgumentException::class.java) {
            baseline.copy(executionEnabled = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            baseline.copy(liveEndpointAllowed = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            baseline.copy(paperPostOrdersAllowed = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            baseline.copy(autoPaperEnabled = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            baseline.copy(foregroundServiceEnabled = true)
        }
        // Removing the always-required reasons must also throw.
        assertThrows(IllegalArgumentException::class.java) {
            baseline.copy(blockingReasons = emptyList())
        }
    }

    // ---- Invariant 14 --------------------------------------------------

    @Test
    fun `INV14 - production source exposes no order mutation method`() {
        val forbiddenNames = setOf(
            "submitorder",
            "placeorder",
            "cancelorder",
            "replaceorder",
            "closeposition",
            "executeorder",
        )
        val functionDeclaration = Regex(
            """\bfun\s+(?:<[^>]+>\s*)?([A-Za-z_][A-Za-z0-9_]*)""",
        )
        val violations = productionKotlinFiles().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (line.isCommentOnly()) return@mapIndexedNotNull null
                val methodName = functionDeclaration.find(line)?.groupValues?.get(1)
                    ?: return@mapIndexedNotNull null
                val normalized = methodName.lowercase()
                if (forbiddenNames.any { normalized.contains(it) }) {
                    "${file.relativeTo(productionSourceRoot()).path}:${index + 1}: $methodName"
                } else {
                    null
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Production order-mutation methods found:\n${violations.joinToString("\n")}",
        )
    }

    // ---- Invariant 15 --------------------------------------------------

    @Test
    fun `INV15 - production source contains only the approved Paper POST and no other mutation`() {
        val mutationShapes = listOf(
            Regex("""\.\s*(?:post|delete|patch)\s*\(""", RegexOption.IGNORE_CASE),
            Regex("""@(?:POST|DELETE|PATCH)\s*\("""),
            Regex(
                """\.method\s*\(\s*\"(?:POST|DELETE|PATCH)\"""",
                RegexOption.IGNORE_CASE,
            ),
        )
        val executablePaperMutationUrl = Regex(
            """\"https://paper-api\.alpaca\.markets/v2/(?:orders|positions/|account/configurations)""",
            RegexOption.IGNORE_CASE,
        )
        val violations = productionKotlinFiles().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (line.isCommentOnly()) return@mapIndexedNotNull null
                val relativePath = file.relativeTo(productionSourceRoot()).invariantSeparatorsPath
                val allowedPost = relativePath.endsWith(
                    "data/paper/submit/AlpacaPaperOrderSubmitHttpClient.kt",
                ) && Regex("""\.\s*post\s*\(""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(line)
                val allowedOrdersUrl = relativePath.endsWith(
                    "data/paper/submit/AlpacaPaperSubmitEndpoint.kt",
                ) && executablePaperMutationUrl.containsMatchIn(line)
                val httpPutShape = file.name.startsWith("OkHttp") &&
                    Regex("""^\s*\.put\s*\(""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(line)
                if ((mutationShapes.any { it.containsMatchIn(line) } && !allowedPost) ||
                    httpPutShape ||
                    (executablePaperMutationUrl.containsMatchIn(line) && !allowedOrdersUrl)
                ) {
                    "${file.relativeTo(productionSourceRoot()).path}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Production HTTP mutation implementation found:\n${violations.joinToString("\n")}",
        )
    }

    // ---- Invariant 17 --------------------------------------------------

    @Test
    fun `INV17 - Phase 2 v mutation surface is exactly one Paper POST method and endpoint`() {
        val methods = AlpacaPaperOrderSubmitHttpClient::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .map { it.name }
            .toSet()
        assertEquals(setOf("executePostOrder"), methods)
        assertEquals("POST", AlpacaPaperSubmitEndpoint.METHOD)
        assertEquals(
            "https://paper-api.alpaca.markets/v2/orders",
            AlpacaPaperSubmitEndpoint.ORDERS_URL,
        )
        assertTrue(
            AlpacaPaperSubmitEndpoint.isSafeManualPaperOrder(
                AlpacaPaperSubmitEndpoint.METHOD,
                AlpacaPaperSubmitEndpoint.ORDERS_URL,
            ),
        )
        assertFalse(
            AlpacaPaperSubmitEndpoint.isSafeManualPaperOrder(
                "POST",
                "https://api.alpaca.markets/v2/orders",
            ),
        )
    }

    // ---- Invariant 18 --------------------------------------------------

    @Test
    fun `INV18 - approved POST call graph has no production bypass`() {
        val postCalls = productionKotlinFiles().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (line.isCommentOnly() || !line.contains(".executePostOrder(")) {
                    return@mapIndexedNotNull null
                }
                "${file.relativeTo(productionSourceRoot()).invariantSeparatorsPath}:${index + 1}"
            }
        }
        val submitClientCalls = productionKotlinFiles().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (line.isCommentOnly() || !line.contains("submitClient.submitOnce(")) {
                    return@mapIndexedNotNull null
                }
                "${file.relativeTo(productionSourceRoot()).invariantSeparatorsPath}:${index + 1}"
            }
        }

        assertEquals(
            1,
            postCalls.size,
            "executePostOrder must have exactly one production caller: $postCalls",
        )
        assertTrue(
            postCalls.single().startsWith(
                "com/vela/android/lab/data/paper/submit/PaperManualOrderSubmitClient.kt:",
            ),
            "Only PaperManualOrderSubmitClient may call executePostOrder: $postCalls",
        )
        assertEquals(
            1,
            submitClientCalls.size,
            "submitOnce must have exactly one production caller: $submitClientCalls",
        )
        assertTrue(
            submitClientCalls.single().startsWith(
                "com/vela/android/lab/data/paper/submit/PaperManualSubmitExecutor.kt:",
            ),
            "Only PaperManualSubmitExecutor may call the submit client: $submitClientCalls",
        )
    }

    // ---- Invariant 16 --------------------------------------------------

    @Test
    fun `INV16 - production never unlocks REAL or enables automated execution`() {
        val enablingAssignments = Regex(
            """\b(?:canExecuteOrders|executionEnabled|autoPaperEnabled|foregroundServiceEnabled)\s*=\s*true\b""",
            RegexOption.IGNORE_CASE,
        )
        val unlockCall = Regex("""\bunlockRealMode\s*\(""")
        val unlockDeclaration = Regex("""\bfun\s+unlockRealMode\s*\(""")
        val forbiddenAutoState = Regex("""\bAUTO_PAPER(?!_DISABLED)\b""")
        val violations = productionKotlinFiles().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (line.isCommentOnly()) return@mapIndexedNotNull null
                val unlocksReal = unlockCall.containsMatchIn(line) &&
                    !unlockDeclaration.containsMatchIn(line)
                if (enablingAssignments.containsMatchIn(line) || unlocksReal ||
                    forbiddenAutoState.containsMatchIn(line)
                ) {
                    "${file.relativeTo(productionSourceRoot()).path}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Production execution-enabling state found:\n${violations.joinToString("\n")}",
        )
    }

    // ---- helpers -------------------------------------------------------

    private fun newSafePreview(): PaperOrderPayloadPreview = PaperOrderPayloadPreview(
        previewId = "freeze-test-id",
        linkedClientDryRunId = "freeze-test-dryrun",
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

    private fun newSafeReadiness(): PaperExecutionReadinessSnapshot =
        PaperExecutionReadinessSnapshot(
            previewId = "freeze-test-id",
            linkedClientDryRunId = "freeze-test-dryrun",
            hasValidPreview = true,
            executionEnabled = false,
            realLocked = true,
            liveEndpointAllowed = false,
            paperPostOrdersAllowed = false,
            autoPaperEnabled = false,
            foregroundServiceEnabled = false,
            credentialsConfigured = true,
            status = PaperExecutionReadinessStatus.READY_BUT_EXECUTION_DISABLED,
            blockingReasons = listOf(
                PaperExecutionReadinessReason.EXECUTION_DISABLED,
                PaperExecutionReadinessReason.PAPER_POST_ORDERS_DISABLED,
            ),
            warnings = emptyList(),
            checkedAtEpochMillis = 0L,
        )

    private fun productionKotlinFiles(): List<File> = productionSourceRoot()
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun productionSourceRoot(): File {
        val candidates = listOf(
            File("src/main/kotlin"),
            File("app/src/main/kotlin"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "Cannot locate production Kotlin source from ${File(".").absolutePath}",
            )
    }

    private fun String.isCommentOnly(): Boolean {
        val trimmed = trimStart()
        return trimmed.startsWith("//") ||
            trimmed.startsWith("/*") ||
            trimmed.startsWith("*") ||
            trimmed.startsWith("*/")
    }
}
