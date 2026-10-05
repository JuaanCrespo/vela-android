package com.vela.android.lab.safety

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 3.a.1-D.1 release audit, read from production source.
 *
 * Compose UI is instrumented in this project, so the Diagnostics wiring cannot run on the JVM. These scans pin that
 * wiring, and they do not depend on the build variant. Each scan first checks that it found its anchor file, so none
 * can pass vacuously.
 */
class DemoGeneratorReleaseGateScanTest {

    @Test
    fun `RELEASE_DEMO_CARD_HIDDEN the diagnostics section composes the demo card only behind the availability flag`() {
        val diagnostics = source("ui/dashboard/VelaDashboardSections.kt").substringAfter("private fun DiagnosticsSection(")
        val gate = diagnostics.indexOf("if (data.dashboard.demoGeneratorsAvailable) {")
        val card = diagnostics.indexOf("ControlsCard(")
        val status = diagnostics.indexOf("SummaryRow(\"Demo status\"")
        val readOnly = diagnostics.indexOf("StatusCard(data.dashboard)")

        assertTrue(gate > 0, "DiagnosticsSection must gate the demo card on data.dashboard.demoGeneratorsAvailable")
        assertTrue(gate < card && card < status, "the demo card and its status row must sit inside the gate")
        assertTrue(status < readOnly, "the gate must close before the read-only diagnostics cards")
    }

    @Test
    fun `RELEASE_DEMO_CARD_HIDDEN the screen content composes the demo card only behind the state flag`() {
        val screen = source("ui/dashboard/OfflineDashboardScreen.kt")
        val gate = screen.indexOf("if (state.demoGeneratorsAvailable) {")
        val card = screen.indexOf("ControlsCard(")

        assertTrue(gate > 0, "OfflineDashboardContent must gate the demo card on state.demoGeneratorsAvailable")
        assertTrue(gate < card, "the demo card must sit inside the gate")
    }

    @Test
    fun `DEMO_BUILD_FLAG_IS_READ_ONLY_IN_THE_GATE only the gate file reads the demo build flag`() {
        assertTrue(source("ui/dashboard/DemoGeneratorGate.kt").contains("BuildConfig.DEBUG"))
        val receivers = listOf(
            "ui/dashboard/OfflineDashboardViewModel.kt",
            "ui/dashboard/OfflineDashboardUiState.kt",
            "ui/dashboard/OfflineDashboardScreen.kt",
            "ui/dashboard/VelaDashboardSections.kt",
        )
        for (relative in receivers) {
            assertFalse(source(relative).contains("BuildConfig.DEBUG"), "$relative must receive the flag, not read it")
        }
    }

    @Test
    fun `DEMO_VIEW_MODEL_IS_CONSTRUCTED_ONLY_BY_THE_COMPOSITION_ROOT the composition root passes the gate`() {
        val constructing = productionFiles()
            .filter { mentionsCode(it.readText(), "OfflineDashboardViewModel(") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("MainActivity.kt", "OfflineDashboardViewModel.kt"), constructing)
        assertTrue(source("MainActivity.kt").contains("demoGeneratorsEnabled = demoGeneratorsEnabledForThisBuild(),"))
    }

    @Test
    fun `DEMO_GENERATOR_ENTRY_POINTS_ARE_LIMITED_TO_THE_GATED_CARD the generators are wired only through the screen`() {
        val callers = productionFiles()
            .filter { mentionsCode(it.readText(), "generateBtcUpdate") || mentionsCode(it.readText(), "generateSpyUpdate") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("OfflineDashboardScreen.kt", "OfflineDashboardViewModel.kt"), callers)
    }

    @Test
    fun `DEMO_GENERATOR_NO_RUNTIME_TOGGLE no preference or setting controls demo generation`() {
        val offenders = productionFiles()
            .filter { file ->
                val path = file.invariantSeparatorsPath
                path.contains("/ui/settings/") || path.contains("/data/")
            }
            .filter { it.readText().contains("demoGenerator", ignoreCase = true) }
            .map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `DEMO_PATH_CANNOT_REACH_EXECUTION the demo ViewModel holds no price or preflight authority`() {
        val code = codeLines(source("ui/dashboard/OfflineDashboardViewModel.kt")).joinToString("\n")
        assertTrue(code.contains("class OfflineDashboardViewModel("), "the anchor class must be found")
        for (token in listOf(
            "ExecutionReferencePrice",
            "MarketTickBuffer",
            "MarketPriceSnapshotProvider",
            "MarketTick(",
            "PaperOrderPreflight",
            "LegacyDisplayPrice",
        )) {
            assertFalse(code.contains(token), "the demo ViewModel must not reference $token")
        }
    }

    @Test
    fun `RELEASE_MARKET_WRITE_PATHS_ZERO only the gated demo and the debug bridge reach the coordinator`() {
        val addUpdateCallers = productionFiles()
            .filter { mentionsCode(it.readText(), "coordinator.addUpdate(") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("AlpacaTestStreamPipelineBridge.kt", "OfflineDashboardViewModel.kt"), addUpdateCallers)

        val persistCallers = productionFiles()
            .filter { mentionsCode(it.readText(), "persistBar(") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("MarketDataRepository.kt", "OfflineMarketPipelineCoordinator.kt"), persistCallers)
    }

    @Test
    fun `RELEASE_MARKET_WRITE_PATHS_ZERO the stream ViewModels stay debug-only in the composition root`() {
        val main = source("MainActivity.kt")
        for (line in listOf(
            "alpacaViewModel = if (BuildConfig.DEBUG) alpacaViewModel else null",
            "alpacaStockViewModel = if (BuildConfig.DEBUG) alpacaStockViewModel else null",
            "watchlistViewModel = if (BuildConfig.DEBUG) watchlistViewModel else null",
        )) {
            assertTrue(main.contains(line), "MainActivity must keep the debug gate: $line")
        }
    }

    private fun productionRoot(): File = listOf(
        File("src/main/kotlin/com/vela/android/lab"),
        File("app/src/main/kotlin/com/vela/android/lab"),
    ).firstOrNull(File::isDirectory)
        ?: error("Cannot locate app main sources from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(productionRoot(), relative).readText()

    private fun productionFiles(): List<File> = productionRoot()
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun codeLines(text: String): List<String> = text.lines()
        .map { it.trim() }
        .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }

    private fun mentionsCode(text: String, token: String): Boolean = codeLines(text).any { token in it }
}
