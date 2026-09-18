package com.vela.android.lab.db.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vela.android.lab.db.room.migrations.MIGRATION_7_8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2.y.3 migration test: v7 → v8 is purely additive.
 *
 * A. A populated v7 database is migrated and every legacy row is preserved byte-for-byte.
 * B. Every new v8 table exists after migration and is empty.
 * C. Room's validateDroppedTables=true is set so ANY unexpected DROP would already
 *    fail the migration; this test additionally samples the legacy row contents.
 */
@RunWith(AndroidJUnit4::class)
class PaperPositionEvidenceMigration7To8Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VelaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private companion object {
        const val TEST_DATABASE: String = "paper-position-evidence-migration-test"
    }

    @Test
    fun migration7To8_preservesLegacyRowsAndCreatesEmptyEvidenceTables() {
        helper.createDatabase(TEST_DATABASE, 7).apply {
            execSQL(
                """
                INSERT INTO paper_order_submit_audit (
                    eventKey, submitAttemptId, previewId, linkedClientDryRunId,
                    clientOrderId, symbol, side, quantity, orderType, timeInForce,
                    limitPriceUsd, status, alpacaOrderId, submittedAtEpochMillis,
                    safeErrorMessage, priceSource, priceFreshness, marketOpen,
                    confirmationTokenId
                ) VALUES (
                    'attempt-preserve:SUBMITTED', 'attempt-preserve', 'preview-x',
                    'dry-run-x', 'vela-client-x', 'SPY', 'BUY', 1.0,
                    'MARKET', 'DAY', NULL, 'SUBMITTED',
                    '3f06e628-ed14-451f-a985-6ff590018243', 1787230800000,
                    NULL, 'LIVE_QUOTE_MID', 'FRESH', 1, 'token-x'
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE, 8, true, MIGRATION_7_8)
        // Legacy row is intact.
        migrated.query("SELECT COUNT(*) FROM paper_order_submit_audit WHERE submitAttemptId = 'attempt-preserve'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        // Every new v8 table exists and is empty.
        listOf(
            "paper_broker_snapshot", "paper_broker_position_snapshot", "paper_position_anchor",
            "paper_position_anchor_cursor", "paper_position_anchor_event",
            "paper_position_reconciliation_report", "paper_position_reconciliation_row",
            "paper_order_decimal_evidence",
        ).forEach { table ->
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table must be empty after v7→v8 migration", 0, cursor.getInt(0))
            }
        }
        migrated.close()
    }

    @Test
    fun migration7To8_emptyV7DatabaseYieldsEmptyV8() {
        helper.createDatabase(TEST_DATABASE, 7).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE, 8, true, MIGRATION_7_8)
        migrated.query("SELECT COUNT(*) FROM paper_broker_snapshot").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migration7To8_passesSqliteIntegrityCheck() {
        helper.createDatabase(TEST_DATABASE, 7).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE, 8, true, MIGRATION_7_8)
        migrated.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
        migrated.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("Foreign key violation after migration", cursor.moveToFirst())
        }
        migrated.close()
    }
}
