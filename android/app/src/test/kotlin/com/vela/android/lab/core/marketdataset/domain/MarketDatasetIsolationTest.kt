package com.vela.android.lab.core.marketdataset.domain

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MarketDatasetIsolationTest {
    private val appRoot = listOf(File("."), File("app"), File("android/app")).first { File(it, "src/main/kotlin").isDirectory }
    private val main = File(appRoot, "src/main/kotlin")
    private val domain = File(main, "com/vela/android/lab/core/marketdataset/domain")
    private fun sources(): List<File> = domain.walkTopDown().filter { it.extension == "kt" }.toList().also { assertEquals(7, it.size) }

    @Test fun `domain dependency graph contains only pure JDK and Kotlin`() {
        val allowed = setOf("java.math.BigDecimal", "java.security.MessageDigest", "java.time.Instant", "java.util.Collections", "java.util.Locale")
        sources().forEach { file ->
            val text = file.readText()
            val imports = Regex("(?m)^import ([^\\r\\n]+)").findAll(text).map { it.groupValues[1] }.toSet()
            assertTrue(allowed.containsAll(imports), "${file.name}: $imports")
            val checked = text.replace("com.vela.android.lab.", "vela.")
            assertFalse(Regex("androidx?\\.|okhttp|retrofit|java\\.net|java\\.io|java\\.nio|db\\.room|@Entity|@Dao|data\\.paper|ExecutionReferencePrice|ViewModel").containsMatchIn(checked), file.name)
        }
    }
    @Test fun `no nondeterminism float authority legacy importer or mutable training permission`() {
        sources().forEach { file ->
            assertFalse(Regex("Instant\\.now|System\\.(currentTimeMillis|nanoTime)|Random|UUID\\.randomUUID|Locale\\.getDefault|TimeZone\\.getDefault|\\bDouble\\b|\\bFloat\\b|toDouble\\(|market_bars_1m|MarketBar1mEntity|trainingEligible").containsMatchIn(file.readText()), file.name)
        }
    }
    @Test fun `no external production consumer and no execution coupling`() {
        main.walkTopDown().filter { it.extension == "kt" && !it.toPath().startsWith(domain.toPath()) }.forEach {
            assertFalse(it.readText().contains("core.marketdataset"), "Unexpected runtime wiring: ${it.name}")
        }
    }
    @Test fun `operational Room remains v9 and no schema or migration introduced`() {
        val db = File(main, "com/vela/android/lab/db/room/VelaDatabase.kt")
        assertTrue(db.isFile); assertTrue(db.readText().contains("version = 9,"))
        assertTrue(File(appRoot, "schemas/com.vela.android.lab.db.room.VelaDatabase/9.json").isFile)
        assertFalse(File(appRoot, "schemas/com.vela.android.lab.db.room.VelaDatabase/10.json").exists())
        assertFalse(main.walkTopDown().any { it.name.contains("Migration9To10") })
    }
}
