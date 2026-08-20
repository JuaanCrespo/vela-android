package com.vela.android.lab.db.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vela.android.lab.db.room.migrations.MIGRATION_5_6
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaperOrderReconciliationMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VelaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration5To6_preservesSubmitAuditAndCreatesEmptyReconciliationTables() {
        helper.createDatabase(TEST_DATABASE, 5).apply {
            execSQL(
                """
                INSERT INTO paper_order_submit_audit (
                    eventKey, submitAttemptId, previewId, linkedClientDryRunId,
                    clientOrderId, symbol, side, quantity, orderType, timeInForce,
                    limitPriceUsd, status, alpacaOrderId, submittedAtEpochMillis,
                    safeErrorMessage, priceSource, priceFreshness, marketOpen,
                    confirmationTokenId
                ) VALUES (
                    'attempt-legacy:SUBMITTED', 'attempt-legacy', 'preview-legacy',
                    'dry-run-legacy', 'vela-client-legacy', 'SPY', 'BUY', 1.0,
                    'MARKET', 'DAY', NULL, 'SUBMITTED',
                    '3f06e628-ed14-451f-a985-6ff590018243', 1787230800000,
                    NULL, 'LIVE_QUOTE_MID', 'FRESH', 1, 'token-legacy'
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            6,
            true,
            MIGRATION_5_6,
        ).use { database ->
            database.query(
                "SELECT submitAttemptId, clientOrderId, alpacaOrderId " +
                    "FROM paper_order_submit_audit",
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("attempt-legacy", cursor.getString(0))
                assertEquals("vela-client-legacy", cursor.getString(1))
                assertEquals("3f06e628-ed14-451f-a985-6ff590018243", cursor.getString(2))
            }
            database.query("SELECT COUNT(*) FROM paper_order_reconciliation").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM paper_order_lifecycle_observation").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE: String = "paper-reconciliation-migration-test"
    }
}
