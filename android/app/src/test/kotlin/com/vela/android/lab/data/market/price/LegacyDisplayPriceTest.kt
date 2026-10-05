package com.vela.android.lab.data.market.price

import java.io.File
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 3.a.1-D display-only price.
 *
 * DISPLAY_PRICE_NON_AUTHORITATIVE: [LegacyDisplayPrice] holds a number and nothing that could make it look like
 * a trusted execution reference. It has no provenance, source, freshness, or trust field.
 *
 * DISPLAY_DOES_NOT_REENTER_EXECUTION: only the display files may reference it. No execution file may.
 *
 * The source scans read production code under `src/main/kotlin`. Comment lines are ignored where noted.
 */
class LegacyDisplayPriceTest {

    @Test
    fun `DISPLAY_PRICE_NON_AUTHORITATIVE carries only the price field`() {
        val instanceFields = LegacyDisplayPrice::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertEquals(listOf("price"), instanceFields)
    }

    @Test
    fun `DISPLAY_PRICE_NON_AUTHORITATIVE has no method that returns an execution reference`() {
        val returnsExecutionReference = LegacyDisplayPrice::class.java.declaredMethods
            .filter { ExecutionReferencePrice::class.java.isAssignableFrom(it.returnType) }
        assertTrue(returnsExecutionReference.isEmpty(), "unexpected: $returnsExecutionReference")
    }

    @Test
    fun `DISPLAY_PRICE_NON_AUTHORITATIVE label is the approved text`() {
        assertEquals("LEGACY PRICE - NOT FOR EXECUTION", LegacyDisplayPrice.LABEL)
    }

    @Test
    fun `DISPLAY_PRICE_NON_AUTHORITATIVE the exposure row renders the label constant`() {
        val screen = productionFiles().single { it.name == "OfflineDashboardScreen.kt" }.readText()
        assertTrue(screen.contains("\${LegacyDisplayPrice.LABEL}"), "the exposure row must render LegacyDisplayPrice.LABEL")
    }

    @Test
    fun `DISPLAY_DOES_NOT_REENTER_EXECUTION only display files reference the legacy display price`() {
        val referencing = productionFiles()
            .filter { it.name != DEFINITION_FILE && mentionsLegacyDisplay(it.readText()) }
            .map { it.invariantSeparatorsPath }
        assertTrue(
            referencing.any { it.endsWith("/PaperPortfolioRiskViewModel.kt") },
            "the scan must see the display producer, otherwise the check is vacuous",
        )
        val unexpected = referencing.filterNot { path -> DISPLAY_FILES.any { path.endsWith("/$it") } }
        assertEquals(emptyList<String>(), unexpected)
    }

    @Test
    fun `DISPLAY_DOES_NOT_REENTER_EXECUTION no execution file mentions the legacy display price`() {
        val executionHits = productionFiles()
            .filter { isExecutionFile(it) }
            .filter { mentionsLegacyDisplay(it.readText()) }
            .map { it.invariantSeparatorsPath }
        assertEquals(emptyList<String>(), executionHits)
    }

    @Test
    fun `DISPLAY_DOES_NOT_REENTER_EXECUTION no display file builds an execution reference in code`() {
        val offenders = productionFiles()
            .filter { mentionsLegacyDisplay(it.readText()) }
            .filter { file -> codeLines(file.readText()).any { "ExecutionReferencePrice" in it } }
            .map { it.invariantSeparatorsPath }
        assertEquals(emptyList<String>(), offenders)
    }

    private fun mentionsLegacyDisplay(text: String): Boolean =
        text.contains("LegacyDisplayPrice") || text.contains("legacyDisplayClose")

    private fun isExecutionFile(file: File): Boolean {
        val path = file.invariantSeparatorsPath
        return path.contains("/data/paper/preflight/") ||
            path.contains("/data/paper/submit/") ||
            file.name in EXECUTION_FILE_NAMES ||
            file.name.startsWith("PaperManualSubmit")
    }

    private fun codeLines(text: String): List<String> = text.lines()
        .map { it.trim() }
        .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }

    private fun productionFiles(): List<File> {
        val root = listOf(
            File("src/main/kotlin"),
            File("app/src/main/kotlin"),
        ).firstOrNull(File::isDirectory)
            ?: error("Cannot locate production Kotlin source from ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        const val DEFINITION_FILE = "LegacyDisplayPrice.kt"

        /** Display-only files allowed to read the legacy close. Each one renders it or counts it. */
        val DISPLAY_FILES = listOf(
            "PaperPortfolioModels.kt",
            "PaperPortfolioRiskViewModel.kt",
            "OfflineDashboardScreen.kt",
            "VelaDashboardSections.kt",
        )

        /** Execution-authority files. None may reference the legacy display price. */
        val EXECUTION_FILE_NAMES = setOf(
            "ExecutionReferencePrice.kt",
            "MarketPriceSnapshotProvider.kt",
            "PaperOrderPreflightViewModel.kt",
        )
    }
}
