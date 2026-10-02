package com.vela.android.lab.db.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vela.android.lab.db.room.migrations.MIGRATION_8_9
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compiled in 2.y.5-C; execution on a device is expressly deferred to a separately approved phase. */
@RunWith(AndroidJUnit4::class)
class LegacyBootstrapMigration8To9Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), VelaDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun emptyV8MigratesWithoutBootstrapOrBackfill() {
        helper.createDatabase("bootstrap-empty", 8).close()
        helper.runMigrationsAndValidate("bootstrap-empty", 9, true, MIGRATION_8_9).use { db ->
            listOf("paper_position_anchor", "paper_position_reconciliation_report", "paper_order_decimal_evidence").forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        }
    }

    @Test fun populatedV8PreservesEveryOriginalValueIncludingOldAnchorsAndReports() {
        val name = "bootstrap-populated"
        val old = helper.createDatabase(name, 8)
        listOf("A", "B", "future").forEachIndexed { index, attempt ->
            insert(old, "paper_order_submit_audit", mapOf("id" to index + 1, "eventKey" to "$attempt:SUBMITTED", "submitAttemptId" to attempt,
                "quantity" to 1.0, "status" to "SUBMITTED", "symbol" to "SPY", "side" to "BUY"))
            insert(old, "paper_order_reconciliation", mapOf("submitAttemptId" to attempt, "terminal" to 1, "filledQuantity" to 1.0,
                "resetAcknowledgedAtEpochMillis" to 1000, "mappingStatus" to "EXACT"))
            insert(old, "paper_order_lifecycle_observation", mapOf("id" to index + 1, "observationKey" to "obs-$attempt", "submitAttemptId" to attempt,
                "rawStatus" to "filled", "status" to "FILLED", "terminal" to 1, "filledQuantity" to 1.0))
        }
        listOf("qty", "filled_qty").forEach { insert(old, "paper_order_decimal_evidence", mapOf("observationId" to 3, "field" to it,
            "attemptId" to "future", "rawDecimal" to "1.000", "canonicalDecimal" to "1", "provenance" to "EXACT_DECIMAL")) }
        listOf("COMPLETE", "FAILED").forEachIndexed { index, status ->
            insert(old, "paper_broker_snapshot", mapOf("snapshotId" to "s$index", "sequence" to index + 1, "manualRefreshId" to "m$index", "completeness" to status))
        }
        insert(old, "paper_broker_position_snapshot", mapOf("snapshotId" to "s0", "symbol" to "SPY", "qtyRawDecimal" to "8.00", "qtyCanonicalDecimal" to "8"))
        insert(old, "paper_position_anchor", mapOf("anchorId" to "old", "brokerSnapshotId" to "s0", "baselineQty" to "8", "status" to "ACTIVE", "version" to 1))
        insert(old, "paper_position_anchor_cursor", mapOf("anchorId" to "old", "attemptId" to "future", "includedFilledQty" to "1"))
        insert(old, "paper_position_anchor_event", mapOf("eventId" to "old:1", "anchorId" to "old", "version" to 1, "type" to "CREATED"))
        listOf("before", "later").forEach { id ->
            insert(old, "paper_position_reconciliation_report", mapOf("reportId" to id, "brokerSnapshotId" to "s0",
                "engineVersion" to "POSITION_ENGINE_2Y2_V1", "policyVersion" to "POSITION_POLICY_2Y3_V1", "inputsJson" to "{\"version\":1}"))
            insert(old, "paper_position_reconciliation_row", mapOf("reportId" to id, "symbol" to "SPY", "anchorId" to null,
                "state" to "UNKNOWN", "knownVelaDelta" to "2", "knownDeltaProvenance" to "LEGACY_DOUBLE_DERIVED"))
        }
        val tables = old.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'paper_%' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val columns = tables.associateWith { columns(old, it).map { column -> column.first } }
        val before = tables.associateWith { rows(old, it, columns.getValue(it)) }
        old.close()
        helper.runMigrationsAndValidate(name, 9, true, MIGRATION_8_9).use { migrated ->
            tables.forEach { assertEquals(it, before[it], rows(migrated, it, columns.getValue(it))) }
            migrated.query("SELECT coverageMode, bootstrapCutJson, bootstrapCutDigest FROM paper_position_anchor").use {
                assertTrue(it.moveToFirst()); assertEquals("EXACT_CURSORS_V1", it.getString(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
            }
            migrated.query("SELECT coverageJson FROM paper_position_reconciliation_row").use { while (it.moveToNext()) assertTrue(it.isNull(0)) }
            migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            migrated.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
        }
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<Triple<String, String, Boolean>> = db.query("PRAGMA table_info($table)").use { cursor ->
        buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(1), cursor.getString(2), cursor.getInt(3) == 1)) }
    }
    private fun insert(db: SupportSQLiteDatabase, table: String, overrides: Map<String, Any?>) {
        val fields = columns(db, table)
        val values = fields.map { (name, type, required) ->
            if (name in overrides) overrides[name] else if (!required) null else if (type == "TEXT") "fixture-$name" else 0
        }
        db.execSQL("INSERT INTO $table (${fields.joinToString { it.first }}) VALUES (${fields.joinToString { "?" }})", values.toTypedArray())
    }
    private fun rows(db: SupportSQLiteDatabase, table: String, fields: List<String>): List<List<String?>> =
        db.query("SELECT ${fields.joinToString()} FROM $table ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add(fields.indices.map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
}
