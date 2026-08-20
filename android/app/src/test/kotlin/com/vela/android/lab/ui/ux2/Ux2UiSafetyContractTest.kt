package com.vela.android.lab.ui.ux2

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ux2UiSafetyContractTest {

    @Test
    fun `global shell always renders the six-state safety banner`() {
        val shell = source("ui/navigation/VelaAppShell.kt")
        val banner = source("ui/theme/VelaComponents.kt")

        assertTrue(shell.contains("VelaSafetyBanner("))
        listOf(
            "Mode",
            "REAL locked",
            "Paper-only",
            "No LIVE endpoint",
            "Auto Paper disabled",
            "Manual submit compiled=false",
        ).forEach { label -> assertTrue(banner.contains(label), "Missing safety label: $label") }
    }

    @Test
    fun `manual Paper card keeps the frozen enabled expressions and protected rows`() {
        val dashboard = source("ui/dashboard/OfflineDashboardScreen.kt")

        listOf(
            "state.previewId != null,",
            "preparationReady,",
            "PaperGuidedPreparationStage.READY",
            "enabled = !state.isSubmitting",
            "enabled = confirmationGate.mayType",
            "enabled = confirmationGate.maySubmit",
            "paperManualConfirmationUiGate(",
            "MANUAL_CONFIRMATION_RAW_AGE_ABORT_THRESHOLD_MILLIS",
            "confirmationExpiresAtEpochMillis",
            "Confirmación válida:",
            "Escribí exactamente:",
            "value = state.confirmationInput",
            "onValueChange = onConfirmationChange",
            "Confirmación manual exacta",
            "KeyboardCapitalization.Characters",
            "autoCorrect = false",
            "ImeAction.Done",
            "LiveRegionMode.Assertive",
            "Final price raw age (ms)",
            "Future skew tolerance (ms)",
            "Allowed drift threshold",
            "Submit method",
            "Submit endpoint",
            "Gate reasons:",
        ).forEach { contract ->
            assertTrue(dashboard.contains(contract), "Manual Paper contract changed: $contract")
        }
        assertEquals(1, dashboard.windowed("paperManualSubmitViewModel?.submitOnce()".length)
            .count { it == "paperManualSubmitViewModel?.submitOnce()" })
        assertEquals(1, dashboard.windowed("onClick = onSubmit".length)
            .count { it == "onClick = onSubmit" })
        assertFalse(dashboard.contains("Actualizar gates (invalida confirmación)"))
        assertFalse(dashboard.contains("onConfirmationChange(state.requiredConfirmationText)"))
        assertFalse(dashboard.contains("Confirmar manualmente esta orden"))
    }

    @Test
    fun `guided preparation stops before all manual execution surfaces`() {
        val preflight = source("ui/dashboard/PaperOrderPreflightViewModel.kt")
        val sections = source("ui/dashboard/VelaDashboardSections.kt")
        val dashboard = source("ui/dashboard/OfflineDashboardScreen.kt")

        assertTrue(preflight.contains("fun prepareGuidedLocalChain()"))
        assertTrue(preflight.contains("PaperGuidedPreparationStage.READY"))
        listOf(
            "armSession(",
            "onConfirmationInputChange(",
            "submitOnce(",
            "PaperManualSubmitTokenStore",
            "PaperManualSubmitExecutor",
        ).forEach { forbidden ->
            assertFalse(
                preflight.contains(forbidden),
                "Guided preparation reached manual execution surface: $forbidden",
            )
        }
        assertTrue(sections.contains("preflightPrepareGuided"))
        assertTrue(sections.contains("preparedPreviewIsSynchronized("))
        assertTrue(dashboard.contains("fun preparedPreviewIsSynchronized("))
        assertTrue(dashboard.contains("manual?.previewId == preview?.previewId"))
        assertTrue(dashboard.contains("result?.priceSource == MarketPriceSource.LIVE_QUOTE_MID.name"))
        assertTrue(dashboard.contains("result?.priceFreshness == PriceFreshness.FRESH.name"))
        assertTrue(dashboard.contains("paperManualSubmitViewModel?.armSession()"))
        assertTrue(preflight.contains("account?.status =="))
        assertTrue(preflight.contains("!outcome.auditPersisted"))
    }

    @Test
    fun `production Paper section exposes only one guided pre arm action`() {
        val sections = source("ui/dashboard/VelaDashboardSections.kt")
        val paperSection = sections
            .substringAfter("private fun PaperSection(")
            .substringBefore("private fun RiskSection(")

        assertTrue(paperSection.contains("PaperOrderPreparationCard("))
        assertTrue(paperSection.contains("onPrepare = actions.preflightPrepareGuided"))
        assertTrue(paperSection.contains("PaperManualSubmitCard("))
        listOf(
            "technicalStepsExpanded",
            "Mostrar pasos técnicos",
            "Ocultar pasos técnicos",
            "PaperOrderPreflightCard(",
            "PaperExecutionReadinessCard(",
            "actions.preflightRun",
            "actions.preflightBuildDraft",
            "actions.preflightBuildPreview",
            "actions.readinessCheck",
            "actions.disabledExecutionAttempt",
            "PaperOrderPayloadPreviewQueueCard(",
            "PaperDryRunAuditCard(",
        ).forEach { forbidden ->
            assertFalse(
                paperSection.contains(forbidden),
                "Production Paper section exposes technical surface: $forbidden",
            )
        }
    }

    @Test
    fun `Paper lifecycle lookup is explicit GET only and reset stays local`() {
        val dashboard = source("ui/dashboard/OfflineDashboardScreen.kt")
        val sections = source("ui/dashboard/VelaDashboardSections.kt")
        val application = source("VelaLabApplication.kt")
        val activity = source("MainActivity.kt")
        val statusSources = sourceDirectory("data/paper/status").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        listOf(
            "Consultar estado Alpaca · solo GET",
            "Preparar otra orden Paper",
            "Estado VELA",
            "Attempt audit row",
            "Result audit row",
            "Lifecycle observations",
            "MULTIPLE_UNRESOLVED_PAPER_ORDERS",
            "paperManualSubmitViewModel?.refreshOrderStatus()",
            "paperManualSubmitViewModel?.canResetForNewPreparation() == true",
            "paperOrderPreflightViewModel?.canResetForNewPreparation() == true",
            "paperOrderPreflightViewModel?.resetForNewPreparation()",
            "paperManualSubmitViewModel?.resetForNewPreparation {",
        ).forEach { contract ->
            assertTrue(dashboard.contains(contract), "Missing lifecycle UI contract: $contract")
        }
        val durableResetCallback = dashboard
            .substringAfter("manualPaperNewPreparation = {")
            .substringBefore("candleSymbolChanged =")
        val durableAckIndex = durableResetCallback.indexOf(
            "paperManualSubmitViewModel?.resetForNewPreparation {",
        )
        val preflightResetIndex = durableResetCallback.indexOf(
            "paperOrderPreflightViewModel?.resetForNewPreparation()",
        )
        assertTrue(durableAckIndex >= 0)
        assertTrue(preflightResetIndex > durableAckIndex)
        assertFalse(
            dashboard.contains(
                "paperOrderPreflightViewModel?.resetForNewPreparation() == true",
            ),
        )
        assertTrue(sections.contains("manualPaperRefreshOrderStatus"))
        assertTrue(sections.contains("manualPaperNewPreparation"))
        assertTrue(sections.contains("pendingSubmittedOrder"))
        assertTrue(application.contains("OkHttpAlpacaPaperOrderStatusHttpClient()"))
        assertTrue(application.contains("database.paperOrderReconciliationDao()"))
        assertTrue(activity.contains("orderStatusClient = app.alpacaPaperOrderStatusReadOnlyClient"))
        assertTrue(
            activity.contains(
                "orderStatusTrackerRepository = app.paperOrderStatusTrackerRepository",
            ),
        )
        assertTrue(statusSources.contains(".get()"))
        assertFalse(Regex("""\.\s*(?:post|delete|patch)\s*\(""", RegexOption.IGNORE_CASE)
            .containsMatchIn(statusSources))
        assertFalse(statusSources.contains("submitOnce("))
        assertFalse(statusSources.contains("LaunchedEffect"))
        assertFalse(statusSources.contains("while ("))
        assertFalse(statusSources.contains("delay("))
        assertFalse(
            Regex("""\b(?:cancel|replace|closePosition)\s*\(""", RegexOption.IGNORE_CASE)
                .containsMatchIn(statusSources),
        )
    }

    @Test
    fun `guided preparation and arm callbacks recheck persistent reconciliation`() {
        val dashboard = source("ui/dashboard/OfflineDashboardScreen.kt")
        val manualViewModel = source("ui/dashboard/PaperManualSubmitViewModel.kt")
        val preflightCallback = dashboard
            .substringAfter("preflightPrepareGuided = {")
            .substringBefore("dryRunAuditRefresh =")
        val armCallback = dashboard
            .substringAfter("manualPaperArm = {")
            .substringBefore("manualPaperDisarm =")
        val synchronizationGate = dashboard
            .substringAfter("internal fun preparedPreviewIsSynchronized(")
            .substringBefore("internal data class PaperManualConfirmationUiGate")

        assertTrue(preflightCallback.contains("!manual.blocksNewPaperPreparation"))
        assertTrue(armCallback.contains("manual?.blocksNewPaperPreparation == false"))
        assertTrue(synchronizationGate.contains("manual?.blocksNewPaperPreparation == false"))
        assertTrue(manualViewModel.contains("orderStatusTrackerRepository.consolidateFromAudit()"))
        assertTrue(manualViewModel.contains("orderStatusTrackerRepository.persistLifecycle("))
        assertTrue(manualViewModel.contains("orderStatusTrackerRepository.acknowledgeTerminalReset("))
    }

    @Test
    fun `chart settings and navigation own no network client or URL`() {
        val roots = listOf("ui/candles", "ui/settings", "ui/navigation")
        val forbidden = listOf(
            "OkHttpClient",
            "AlpacaHttpClient",
            "AlpacaPaperReadOnlyClient",
            "WebSocket",
            "http://",
            "https://",
            "wss://",
        )
        val violations = roots.flatMap { relative ->
            sourceDirectory(relative).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    forbidden.asSequence()
                        .filter { token -> file.readText().contains(token) }
                        .map { token -> "${file.name}:$token" }
                }
                .toList()
        }
        assertTrue(violations.isEmpty(), "UI-only network surface found: $violations")
    }

    @Test
    fun `production section host has no direct submit destination or automatic navigation`() {
        val sections = source("ui/dashboard/VelaDashboardSections.kt")
        val destinations = source("ui/navigation/VelaDestination.kt")

        assertFalse(destinations.contains("route = \"submit"))
        assertFalse(sections.contains("LaunchedEffect"))
        assertTrue(sections.contains("VelaDestination.PAPER -> PaperSection"))
        assertTrue(sections.contains("VelaActionZone("))
    }

    private fun source(relative: String): String =
        File(sourceRoot(), relative).readText()

    private fun sourceDirectory(relative: String): File =
        File(sourceRoot(), relative).also { directory ->
            check(directory.isDirectory) { "Missing source directory: $directory" }
        }

    private fun sourceRoot(): File = listOf(
        File("src/main/kotlin/com/vela/android/lab"),
        File("app/src/main/kotlin/com/vela/android/lab"),
    ).firstOrNull(File::isDirectory)
        ?: error("Cannot locate app main sources from ${File(".").absolutePath}")
}
