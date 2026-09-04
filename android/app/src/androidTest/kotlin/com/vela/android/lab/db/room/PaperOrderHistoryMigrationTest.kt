package com.vela.android.lab.db.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vela.android.lab.db.room.migrations.MIGRATION_6_7
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaperOrderHistoryMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VelaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration6To7_preservesAuditIdentityLifecycleFillAndResetWithLegacyNulls() {
        helper.createDatabase(POPULATED_DATABASE, 6).apply {
            execSQL(
                """
                INSERT INTO paper_order_submit_audit (
                    id, eventKey, submitAttemptId, previewId, linkedClientDryRunId,
                    clientOrderId, symbol, side, quantity, orderType, timeInForce,
                    limitPriceUsd, status, alpacaOrderId, submittedAtEpochMillis,
                    safeErrorMessage, priceSource, priceFreshness, marketOpen,
                    confirmationTokenId
                ) VALUES
                    (10, 'attempt-legacy:ATTEMPT_STARTED', 'attempt-legacy', 'preview-legacy',
                     'dry-legacy', 'client-legacy', 'SPY', 'BUY', 1.0, 'MARKET', 'DAY',
                     NULL, 'ATTEMPT_STARTED', NULL, 1000, NULL, 'LIVE_QUOTE_MID',
                     'FRESH', 1, 'token-legacy'),
                    (11, 'attempt-legacy:SUBMITTED', 'attempt-legacy', 'preview-legacy',
                     'dry-legacy', 'client-legacy', 'SPY', 'BUY', 1.0, 'MARKET', 'DAY',
                     NULL, 'SUBMITTED', '3f06e628-ed14-451f-a985-6ff590018243', 1100,
                     NULL, 'LIVE_QUOTE_MID', 'FRESH', 1, 'token-legacy')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO paper_order_reconciliation (
                    submitAttemptId, attemptStartedAuditEntryId, submitResultAuditEntryId,
                    previewId, linkedClientDryRunId, alpacaOrderId, clientOrderId, symbol,
                    side, quantity, orderType, timeInForce, limitPriceUsd,
                    submittedAtEpochMillis, localSubmitResult, mappingStatus, mappingDiagnostic,
                    latestLifecycleStatus, latestLifecycleRawStatus,
                    latestLifecycleObservedAtEpochMillis, terminal, filledQuantity,
                    filledAveragePriceUsd, filledAtIso, lifecycleSource,
                    lifecycleHttpStatusCode, resetAcknowledgedAtEpochMillis
                ) VALUES (
                    'attempt-legacy', 10, 11, 'preview-legacy', 'dry-legacy',
                    '3f06e628-ed14-451f-a985-6ff590018243', 'client-legacy', 'SPY',
                    'BUY', 1.0, 'MARKET', 'DAY', NULL, 1100, 'SUBMITTED', 'EXACT', NULL,
                    'FILLED', 'filled', 1300, 1, 1.0, 770.27,
                    '2026-08-07T19:31:00Z', 'ALPACA_PAPER_ORDER_GET', 200, 1400
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO paper_order_lifecycle_observation (
                    id, observationKey, submitAttemptId, alpacaOrderId, status, rawStatus,
                    observedAtEpochMillis, terminal, filledQuantity, filledAveragePriceUsd,
                    filledAtIso, source, httpStatusCode, submitAuditEntryId
                ) VALUES
                    (20, 'local-submit-legacy', 'attempt-legacy',
                     '3f06e628-ed14-451f-a985-6ff590018243', 'SUBMITTED', 'submitted',
                     1100, 0, 0.0, NULL, NULL, 'LOCAL_SUBMIT_AUDIT', NULL, 11),
                    (21, 'filled-legacy', 'attempt-legacy',
                     '3f06e628-ed14-451f-a985-6ff590018243', 'FILLED', 'filled',
                     1300, 1, 1.0, 770.27, '2026-08-07T19:31:00Z',
                     'ALPACA_PAPER_ORDER_GET', 200, 11)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            POPULATED_DATABASE,
            7,
            true,
            MIGRATION_6_7,
        ).use { database ->
            database.query(
                "SELECT id, submitAttemptId, alpacaOrderId, submitHttpStatusCode, " +
                    "initialAlpacaStatus, alpacaSubmittedAtIso " +
                    "FROM paper_order_submit_audit ORDER BY id ASC",
            ).use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(10L, cursor.getLong(0))
                assertTrue(cursor.isNull(3))
                assertTrue(cursor.isNull(4))
                assertTrue(cursor.isNull(5))
                assertTrue(cursor.moveToNext())
                assertEquals(11L, cursor.getLong(0))
                assertEquals("attempt-legacy", cursor.getString(1))
                assertEquals("3f06e628-ed14-451f-a985-6ff590018243", cursor.getString(2))
                assertTrue(cursor.isNull(3))
                assertTrue(cursor.isNull(4))
                assertTrue(cursor.isNull(5))
            }
            database.query(
                "SELECT submitAttemptId, terminal, filledQuantity, filledAveragePriceUsd, " +
                    "filledAtIso, resetAcknowledgedAtEpochMillis " +
                    "FROM paper_order_reconciliation",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("attempt-legacy", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(1.0, cursor.getDouble(2), 0.0)
                assertEquals(770.27, cursor.getDouble(3), 0.0)
                assertEquals("2026-08-07T19:31:00Z", cursor.getString(4))
                assertEquals(1400L, cursor.getLong(5))
            }
            database.query(
                "SELECT id, status, filledQuantity, filledAveragePriceUsd, filledAtIso " +
                    "FROM paper_order_lifecycle_observation ORDER BY id ASC",
            ).use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToLast())
                assertEquals(21L, cursor.getLong(0))
                assertEquals("FILLED", cursor.getString(1))
                assertEquals(1.0, cursor.getDouble(2), 0.0)
                assertEquals(770.27, cursor.getDouble(3), 0.0)
                assertEquals("2026-08-07T19:31:00Z", cursor.getString(4))
            }
        }
    }

    @Test
    fun migration6To7_emptyDatabaseRemainsEmptyAndValid() {
        helper.createDatabase(EMPTY_DATABASE, 6).close()

        helper.runMigrationsAndValidate(EMPTY_DATABASE, 7, true, MIGRATION_6_7).use { database ->
            database.query("SELECT COUNT(*) FROM paper_order_submit_audit").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
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
        const val POPULATED_DATABASE = "paper-history-v6-populated"
        const val EMPTY_DATABASE = "paper-history-v6-empty"
    }
}
