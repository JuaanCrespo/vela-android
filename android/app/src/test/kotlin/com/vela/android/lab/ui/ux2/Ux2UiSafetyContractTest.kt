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
            "enabled = state.compileTimeEnabled && state.previewId != null",
            "enabled = !state.isSubmitting",
            "enabled = !state.isRefreshing && !state.isSubmitting",
            "enabled = state.gateAllowed && !state.isSubmitting",
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
