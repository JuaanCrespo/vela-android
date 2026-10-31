package com.vela.android.lab.core.marketdataset.storage

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Source contracts only: these checks do NOT execute Android or prove its connection behavior. */
class AndroidMarketDatasetAdapterContractTest {
    private val app = listOf(File("."), File("app"), File("android/app"))
        .first { File(it, "src/main/kotlin").isDirectory }
    private fun source(name: String) = File(app, "src/main/kotlin/com/vela/android/lab/core/marketdataset/storage/$name").readText()
    private fun code(text: String) = text.replace(Regex("(?s)/\\*.*?\\*/|(?m)//[^\\r\\n]*"), "")
    private val adapter get() = code(source("AndroidMarketDatasetDatabase.kt"))
    private val busySetter = Regex("(?i)PRAGMA\\s+busy_timeout\\s*(?:=|\\()")

    @Test fun `Android never issues a busy timeout setter through an execution or query workaround`() {
        assertFalse(busySetter.containsMatchIn(adapter))
        // The guard detects the original defect, whitespace/case changes and an alternate setter API.
        for (regression in listOf("db.execSQL(\"PRAGMA busy_timeout=5000\")",
            "db.execSQL(\"pragma  BUSY_TIMEOUT = 5000\", emptyArray())",
            "db.rawQuery(\"PRAGMA busy_timeout(5000)\", null)")) {
            assertTrue(busySetter.containsMatchIn(regression))
        }
        assertFalse(Regex("Thread\\.sleep|execPerConnectionSQL|while\\s*\\(\\s*true\\s*\\)").containsMatchIn(adapter))
    }

    @Test fun `minSdk29 factory retains public OpenParams DELETE FULL and corruption preservation`() {
        assertTrue(File(app, "build.gradle.kts").readText().contains("minSdk = 29"))
        for (required in listOf("SQLiteDatabase.OpenParams.Builder()", "SQLiteDatabase.OPEN_READWRITE",
            "SQLiteDatabase.CREATE_IF_NECESSARY", "SQLiteDatabase.NO_LOCALIZED_COLLATORS",
            ".setJournalMode(\"DELETE\").setSynchronousMode(\"FULL\")",
            "SQLiteDatabase.openDatabase(file, parameters)", "setErrorHandler(DatabaseErrorHandler",
            "MarketStorageError.DATA_INTEGRITY_ERROR", "SQLITE_CORRUPTION")) {
            assertTrue(adapter.contains(required), required)
        }
        assertFalse(Regex("enableWriteAheadLogging|ENABLE_WRITE_AHEAD_LOGGING|setIdleConnectionTimeout").containsMatchIn(adapter))
    }

    @Test fun `required connection settings are configured and audited before schema and transactions`() {
        val handoff = adapter.indexOf("MarketDatasetStore.open(AndroidConnection(db), datasetId)")
        for (required in listOf("db.setForeignKeyConstraintsEnabled(true)", "db.execSQL(\"PRAGMA recursive_triggers=ON\")",
            "db.rawQuery(\"PRAGMA journal_mode\"", "db.rawQuery(\"PRAGMA synchronous\"")) {
            assertTrue(adapter.indexOf(required) in 0 until handoff, required)
        }
        val schema = code(source("MarketDatasetSchema.kt"))
        assertTrue(schema.contains("connection.query(\"PRAGMA foreign_keys\")"))
        assertTrue(schema.contains("connection.query(\"PRAGMA recursive_triggers\")"))
        assertTrue(adapter.contains("db.beginTransactionNonExclusive()"))
        assertTrue(adapter.contains("finally { db.endTransaction() }"))
    }

    @Test fun `both Android contention exceptions retain SQL_BUSY without fallback swallowing`() {
        for (exception in listOf("SQLiteDatabaseLockedException", "SQLiteTableLockedException")) {
            assertTrue(Regex("catch \\(_: $exception\\) \\{ throw MarketStorageException\\(MarketStorageError.SQL_BUSY\\) }").containsMatchIn(adapter))
        }
        assertTrue(adapter.contains("catch (failure: Throwable) { db.close(); throw failure }"))
        assertTrue(adapter.contains("fun open(context: Context, datasetId: DatasetId): MarketDatasetStore = reportBusy"))
        assertTrue(adapter.contains("override fun <T> transaction(block: () -> T): T = reportBusy"))
        assertFalse(adapter.contains("MarketStorageError.RECEIPT_CONFLICT"))
    }

    @Test fun `JDBC retains its independent explicit timeout policy and real contention regression`() {
        val testRoot = File(app, "src/test/kotlin/com/vela/android/lab/core/marketdataset/storage")
        assertTrue(File(testRoot, "MarketStorageFixtures.kt").readText().contains("execute(\"PRAGMA busy_timeout=5000\")"))
        val contention = File(testRoot, "MarketStorageAuditRegressionTest.kt").readText()
        assertTrue(contention.contains("contenderSql.execute(\"PRAGMA busy_timeout=0\")"))
        assertTrue(contention.contains("assertEquals(\"SQL_BUSY\", failure.code.name)"))
    }

    @Test fun `Android configuration case replaces fixed timeout with retained contention invariants`() {
        val runtime = File(app, "src/androidTest/kotlin/com/vela/android/lab/core/marketdataset/runtime/MarketDatasetAndroidRuntimeIntegrityTest.kt").readText()
        val configuration = runtime.substringAfter("@Test fun b_configuration_isObservedOnTheActualFactoryConnection() {")
            .substringBefore("@Test fun c_exactDecimalsAndOriginalRawLexemesRoundTrip()")
        assertFalse(configuration.contains("assertEquals(\"5000\""))
        assertTrue(configuration.contains("PLATFORM_MANAGED"))
        assertTrue(configuration.contains("storageError(MarketStorageError.SQL_BUSY)"))
        assertTrue(configuration.contains("db.beginTransactionNonExclusive()"))
        assertTrue(configuration.contains("finally { db.endTransaction() }"))
        for (expected in listOf("assertEquals(rawBefore", "assertEquals(revisionsBefore",
            "assertEquals(IngestSequence(0), contender.readHighWater(session))",
            "assertNull(contender.readReceipt(next.ingestPosition))",
            "assertEquals(originalBar, contender.readRevision(originalBar.revisionIdentity))",
            "assertEquals(MarketAppendResult(1, 0, 0, 0)",
            "assertEquals(IngestSequence(1), owner.readHighWater(session))")) {
            assertTrue(configuration.contains(expected), expected)
        }
        assertEquals(13, Regex("@Test fun ").findAll(runtime).count())
        assertFalse(Regex("@Ignore|@Disabled|assumeTrue").containsMatchIn(runtime))
    }
}
