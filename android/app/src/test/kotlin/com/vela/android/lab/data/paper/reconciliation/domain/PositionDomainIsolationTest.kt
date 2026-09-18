package com.vela.android.lab.data.paper.reconciliation.domain

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PositionDomainIsolationTest {
    private fun appRoot(): File = listOf(File("."), File("app"), File("android/app"))
        .first { File(it, "src/main/kotlin").isDirectory }
    private fun domainRoot() = File(appRoot(), "src/main/kotlin/com/vela/android/lab/data/paper/reconciliation/domain")

    @Test fun importsAreOnlyPureDomainAndReadOnlyCanonicalValueModels() {
        val allowed = setOf(
            "java.math.BigDecimal", "java.time.Instant", "java.util.Locale",
            "com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory",
            "com.vela.android.lab.data.paper.history.PaperHistoryIntegrityDiagnostic",
            "com.vela.android.lab.data.paper.history.PaperHistoryIntegrityStatus",
        )
        val files = domainRoot().walkTopDown().filter { it.extension == "kt" }.toList()
        assertEquals(5, files.size)
        files.forEach { file ->
            val text = file.readText()
            val imports = Regex("(?m)^import ([^\\r\\n]+)").findAll(text).map { it.groupValues[1] }.toSet()
            assertTrue(allowed.containsAll(imports), "Unexpected dependency in ${file.name}: ${imports - allowed}")
            val forbidden = Regex(
                "(?i)androidx?\\.|okhttp|retrofit|java\\.net|java\\.io|java\\.nio|kotlinx\\.coroutines|" +
                    "data\\.paper\\.(submit|preflight)|db\\.room|WorkManager|Thread\\(|Timer\\(|" +
                    "System\\.(currentTimeMillis|nanoTime)|Instant\\.now|Clock\\.system|expectedSignedPositionDelta|" +
                    "(?<![A-Za-z])IEX(?![A-Za-z])",
            )
            // Strip the package prefix shared by all VELA source before checking Android API imports.
            val checked = text.replace("com.vela.android.lab.", "vela.")
            assertFalse(forbidden.containsMatchIn(checked), "Impure dependency in ${file.name}")
            if (file.name != "DecimalQuantity.kt") assertFalse(Regex("\\bDouble\\b|toDouble\\(").containsMatchIn(text), file.name)
            assertFalse(Regex("BigDecimal\\([^)]*(?:double|value\\.toDouble)", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
    }

    @Test fun onlyTheExplicitEvidenceAdapterMayConsumeTheDomain() {
        val root = File(appRoot(), "src/main/kotlin")
        val outside = root.walkTopDown().filter { it.extension == "kt" && !it.toPath().startsWith(domainRoot().toPath()) }
        outside.forEach {
            if (!it.invariantSeparatorsPath.contains("/paper/reconciliation/evidence/")) {
                assertFalse(it.readText().contains("paper.reconciliation.domain"), "Unexpected wiring: ${it.name}")
            }
        }
    }

    @Test fun resultSurfaceContainsOnlyObservationsAndDiagnostics() {
        val expectedFields = mapOf(
            PositionReconciliationReport::class.java to setOf("snapshotId", "rows", "summary", "diagnostics"),
            PositionReconciliationRow::class.java to setOf("symbol", "brokerQty", "knownVelaDelta", "knownDeltaComplete", "anchorQty", "expectedQty", "difference", "state", "presence", "diagnostics", "anchorId", "cause"),
            PositionReconciliationSummary::class.java to setOf("matchedCount", "mismatchedCount", "unanchoredCount", "unknownCount"),
        )
        expectedFields.forEach { (type, fields) ->
            // Compose adds this static compiler marker even to otherwise pure Kotlin value classes.
            type.declaredFields.firstOrNull { it.name == "\$stable" }?.let {
                assertEquals(Int::class.javaPrimitiveType, it.type)
                assertTrue(java.lang.reflect.Modifier.isStatic(it.modifiers))
            }
            assertEquals(fields, type.declaredFields.filterNot { it.isSynthetic || it.name == "\$stable" }.map { it.name }.toSet())
            assertTrue(type.declaredFields.none { it.type.name.startsWith("kotlin.jvm.functions.") })
        }
        assertEquals(setOf("UNKNOWN"), PositionDifferenceCause.entries.map { it.name }.toSet())
    }

    @Test fun roomV8IsAdditiveAndDomainRemainsPersistenceFree() {
        val database = File(appRoot(), "src/main/kotlin/com/vela/android/lab/db/room/VelaDatabase.kt").readText()
        assertTrue(database.contains("version = 8,"))
        assertFalse(database.contains("reconciliation.domain"))
        assertTrue(File(appRoot(), "schemas/com.vela.android.lab.db.room.VelaDatabase/7.json").exists())
    }
}
