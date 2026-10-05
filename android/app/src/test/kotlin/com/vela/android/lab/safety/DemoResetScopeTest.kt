package com.vela.android.lab.safety

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DEMO_RESET_SCOPE_NARROW (Phase 3.a.1-D).
 *
 * The demo reset must not delete persisted market, feature, signal, or journal rows. `market_bars_1m` holds
 * legacy rows that are preserved: they are not deleted, relabelled, trained on, imported, or promoted to trusted.
 *
 * These are source scans of production code, so they run on the JVM with no device. The Room DAO layer
 * (`db/room/dao`) keeps its delete queries, because removing them would change the fakes in about fifty tests.
 * Only production callers outside the DAO layer are checked here.
 *
 * Administrative deletes that are unrelated to stored market data are not forbidden. They are listed in
 * `docs/phase-3a1d-legacy-clear-display-hardening.md`: `SecureAlpacaCredentialsStore.clear()` and the
 * in-memory `MarketTickBuffer` maps.
 */
class DemoResetScopeTest {

    @Test
    fun `no production caller outside the DAO layer issues a broad market feature signal or journal delete`() {
        val forbidden = listOf(
            Regex("""\.clearAll\("""),
            Regex("""deleteBySymbol\("""),
            Regex(
                """(?i)(marketDataRepository|featureRepository|signalRepository|journalRepository|""" +
                    """marketRepo|featureRepo|signalRepo|journalRepo)\??\.clear\(""",
            ),
            Regex("""(?i)dao\w*\??\.(clear|deleteBySymbol)\("""),
            Regex("""DELETE\s+FROM"""),
        )
        val scanned = productionRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains("/db/room/dao/") }
            .toList()
        assertTrue(
            scanned.any { it.invariantSeparatorsPath.endsWith("ui/dashboard/OfflineDashboardViewModel.kt") },
            "the scan must see the demo ViewModel, otherwise the check is vacuous",
        )
        val offenders = scanned.flatMap { file ->
            val text = file.readText()
            forbidden.filter { it.containsMatchIn(text) }
                .map { "${file.invariantSeparatorsPath}: ${it.pattern}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the market feature signal and journal repositories expose no clear or delete function`() {
        val repositories = listOf(
            "data/repository/MarketDataRepository.kt",
            "data/repository/FeatureRepository.kt",
            "data/repository/SignalRepository.kt",
            "data/repository/JournalRepository.kt",
        )
        val deleteLike = Regex("""fun\s+(clear\w*|delete\w*|remove\w*)\s*\(""")
        for (relative in repositories) {
            val declared = deleteLike.findAll(source(relative)).map { it.value }.toList()
            assertEquals(emptyList<String>(), declared, "$relative exposes a delete-like function")
        }
    }

    @Test
    fun `the demo reset body performs no delete, clear, persist or repository access`() {
        val text = source("ui/dashboard/OfflineDashboardViewModel.kt")
        assertFalse(text.contains("fun clearDemoState"), "the destructive demo clear must not return")

        val start = text.indexOf("fun resetDemoStatus()")
        assertTrue(start >= 0, "resetDemoStatus must exist")
        val end = text.indexOf("private suspend fun dispatchUpdate", start)
        assertTrue(end > start, "resetDemoStatus must end before dispatchUpdate")

        val body = text.substring(start, end).lowercase()
        for (word in listOf("delete", "clear", "dao", "repository", "persist")) {
            assertFalse(body.contains(word), "resetDemoStatus body must not mention '$word'")
        }
    }

    private fun source(relative: String): String = File(productionRoot(), relative).readText()

    private fun productionRoot(): File = listOf(
        File("src/main/kotlin/com/vela/android/lab"),
        File("app/src/main/kotlin/com/vela/android/lab"),
    ).firstOrNull(File::isDirectory)
        ?: error("Cannot locate app main sources from ${File(".").absolutePath}")
}
