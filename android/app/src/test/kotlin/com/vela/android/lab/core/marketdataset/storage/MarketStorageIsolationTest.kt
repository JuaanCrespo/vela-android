package com.vela.android.lab.core.marketdataset.storage

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MarketStorageIsolationTest {
    private val app = listOf(File("."), File("app"), File("android/app")).first { File(it, "src/main/kotlin").isDirectory }
    private val main = File(app, "src/main/kotlin")
    private val root = File(main, "com/vela/android/lab/core/marketdataset")
    @Test fun `storage is separate with one explicit Android opening entry and no runtime wiring`() {
        val storage = File(root, "storage").walkTopDown().filter { it.extension == "kt" }.toList()
        assertEquals(5, storage.size)
        storage.forEach { source ->
            assertFalse(Regex("data\\.paper|ExecutionReferencePrice|authorization|RiskCore|db\\.room|MarketDataRepository|market_bars_1m|okhttp|retrofit|WorkManager|AlarmManager|ViewModel|Instant\\.now|currentTimeMillis|nanoTime|UUID\\.randomUUID").containsMatchIn(source.readText()), source.name)
            if (source.name != "AndroidMarketDatasetDatabase.kt") assertFalse(Regex("(?m)^import android\\.").containsMatchIn(source.readText()), source.name)
        }
        main.walkTopDown().filter { it.extension == "kt" && !it.toPath().startsWith(root.toPath()) }.forEach {
            assertFalse(it.readText().contains("marketdataset.storage"), it.name)
        }
        File(root, "domain").walkTopDown().filter { it.extension == "kt" }.forEach { assertFalse(it.readText().contains("marketdataset.storage")) }
        assertTrue(File(main, "com/vela/android/lab/db/room/VelaDatabase.kt").readText().contains("version = 9,"))
        assertNotEquals("vela-lab.db", MarketDatasetSchema.DATABASE_NAME)
        assertFalse(File(app, "schemas/com.vela.android.lab.db.room.VelaDatabase/10.json").exists())
    }
    @Test fun `DDL contains no destructive replacement clock float or provider ingestion`() {
        assertEquals(1, MarketDatasetSchema.VERSION)
        assertEquals(7, MarketDatasetSchema.tables.size); assertEquals(7, MarketDatasetSchema.indexes.size)
        assertFalse(Regex("(?i)INSERT OR REPLACE|CURRENT_TIMESTAMP|\\bREAL\\b|\\bFLOAT\\b|\\bDOUBLE\\b|DROP TABLE|ATTACH").containsMatchIn(MarketDatasetSchema.statements.joinToString("\n")))
        assertTrue(MarketDatasetSchema.statements.any { it.contains("raw_high_water") })
        assertTrue(MarketDatasetSchema.statements.any { it.contains("FOREIGN KEY(session_id,sequence)") })
    }
}
