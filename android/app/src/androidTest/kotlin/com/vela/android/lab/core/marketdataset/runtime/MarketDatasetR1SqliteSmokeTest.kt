package com.vela.android.lab.core.marketdataset.runtime

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Process
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vela.android.lab.BuildConfig
import com.vela.android.lab.core.marketdataset.domain.DatasetId
import com.vela.android.lab.core.marketdataset.storage.AndroidMarketDatasetDatabase
import com.vela.android.lab.core.marketdataset.storage.MarketDatasetStore
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** ONE separately authorized SQLite smoke. Never delegates to or authorizes the thirteen cases. */
@RunWith(AndroidJUnit4::class)
class MarketDatasetR1SqliteSmokeTest {
    @Test fun openV1SchemaAndRequiredConfiguration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals(MarketDatasetR1Runner::class.java, instrumentation.javaClass)
        assertEquals(MarketDatasetR1Authorization.SMOKE_SELECTOR, InstrumentationRegistry.getArguments().getString("class"))
        assertEquals(MarketDatasetR1Authorization.SMOKE_VALUE,
            InstrumentationRegistry.getArguments().getString(MarketDatasetR1Authorization.ARGUMENT))
        val target = instrumentation.targetContext
        assertEquals("com.vela.android.lab", target.packageName)
        assertEquals(target.applicationInfo.uid, Process.myUid())
        assertEquals(Application::class.java, target.applicationContext.javaClass)
        assertFalse(BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED)
        assertEquals("", BuildConfig.ALPACA_TEST_KEY_ID); assertEquals("", BuildConfig.ALPACA_TEST_SECRET)
        val oldPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build())
        try {
            val owned = MarketDatasetR1Sandbox(target)
            val directory = owned.directory
            val parent = owned.cacheParent
            val report = linkedMapOf<String, String>()
            owned.use {
                owned.verifyOwnership()
                assertSame(owned.context, owned.context.applicationContext)
                assertFalse(owned.database.exists())
                assertEquals(directory, owned.database.canonicalFile.parentFile)
                lateinit var db: SQLiteDatabase
                AndroidMarketDatasetDatabase.open(owned.context, DatasetId("R1_FIX2_SMOKE-${owned.owner}")).use { store ->
                    db = factoryConnection(store)
                    assertEquals(owned.database.canonicalFile, File(db.path).canonicalFile)
                    assertTrue(db.isOpen)
                    assertFalse(db.isWriteAheadLoggingEnabled)
                    assertEquals("1", scalar(db, "PRAGMA user_version"))
                    assertEquals("7", scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*'"))
                    assertEquals("7", scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name NOT GLOB 'sqlite_*'"))
                    assertEquals("16", scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'"))
                    val fingerprint = scalar(db, "SELECT schema_fingerprint FROM market_metadata")
                    assertEquals("a539bc2a020922419c27ae782d8c4bd43f02af4c642b1930dd8c59bc0bea6e47", fingerprint)
                    assertTrue(store.verifyIntegrity().valid)
                    // Check the actual factory handle while pinned to the transaction connection.
                    db.beginTransactionNonExclusive()
                    try {
                        for ((pragma, expected) in linkedMapOf("journal_mode" to "delete", "synchronous" to "2",
                            "foreign_keys" to "1", "recursive_triggers" to "1")) {
                            val observed = scalar(db, "PRAGMA $pragma")
                            assertEquals(pragma, expected, observed)
                            report[pragma] = observed
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                    assertEquals("ok", scalar(db, "PRAGMA integrity_check"))
                    db.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals(0, it.count) }
                    report["sqlite_version"] = scalar(db, "SELECT sqlite_version()")
                    report["user_version"] = scalar(db, "PRAGMA user_version")
                    report["busy_timeout_observed_not_contract"] = scalar(db, "PRAGMA busy_timeout")
                    report["schema_fingerprint"] = fingerprint
                    report["test_owned_path"] = db.path
                    report["integrity_check"] = "ok"
                    report["foreign_key_check_rows"] = "0"
                }
                assertFalse(db.isOpen) // Close occurs before owner-checked sandbox deletion.
            }
            assertFalse(directory.exists()); assertTrue(parent.isDirectory)
            report["process_uid"] = Process.myUid().toString()
            report["cache_parent"] = parent.path
            report["owned_directory"] = directory.path
            report["close_verified"] = "true"; report["cleanup_verified"] = "true"
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "\nR1_SQLITE_SMOKE_EVIDENCE_BEGIN\n" +
                    report.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\nR1_SQLITE_SMOKE_EVIDENCE_END\n")
            })
        } finally { StrictMode.setThreadPolicy(oldPolicy) }
    }

    private fun factoryConnection(store: MarketDatasetStore): SQLiteDatabase {
        val sql = MarketDatasetStore::class.java.getDeclaredField("sql").apply { isAccessible = true }.get(store)
        return sql.javaClass.getDeclaredField("db").apply { isAccessible = true }.get(sql) as SQLiteDatabase
    }

    private fun scalar(db: SQLiteDatabase, sql: String): String = db.rawQuery(sql, null).use {
        assertEquals(1, it.count); assertEquals(1, it.columnCount); assertTrue(it.moveToFirst())
        checkNotNull(it.getString(0))
    }
}
