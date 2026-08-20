package com.vela.android.lab.db.room.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Additive Phase 2.w.1 schema. Historical submit audit rows are preserved verbatim and are
 * consolidated by the deterministic Kotlin resolver after Room opens the migrated database.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `paper_order_reconciliation` (
                `submitAttemptId` TEXT NOT NULL,
                `attemptStartedAuditEntryId` INTEGER,
                `submitResultAuditEntryId` INTEGER,
                `previewId` TEXT,
                `linkedClientDryRunId` TEXT,
                `alpacaOrderId` TEXT,
                `clientOrderId` TEXT,
                `symbol` TEXT,
                `side` TEXT,
                `quantity` REAL,
                `orderType` TEXT,
                `timeInForce` TEXT,
                `limitPriceUsd` REAL,
                `submittedAtEpochMillis` INTEGER,
                `localSubmitResult` TEXT,
                `mappingStatus` TEXT NOT NULL,
                `mappingDiagnostic` TEXT,
                `latestLifecycleStatus` TEXT,
                `latestLifecycleRawStatus` TEXT,
                `latestLifecycleObservedAtEpochMillis` INTEGER,
                `terminal` INTEGER NOT NULL,
                `filledQuantity` REAL,
                `filledAveragePriceUsd` REAL,
                `filledAtIso` TEXT,
                `lifecycleSource` TEXT,
                `lifecycleHttpStatusCode` INTEGER,
                `resetAcknowledgedAtEpochMillis` INTEGER,
                PRIMARY KEY(`submitAttemptId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_reconciliation_order_id` " +
                "ON `paper_order_reconciliation` (`alpacaOrderId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_reconciliation_client_order_id` " +
                "ON `paper_order_reconciliation` (`clientOrderId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_reconciliation_mapping_status` " +
                "ON `paper_order_reconciliation` (`mappingStatus`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_reconciliation_terminal_reset` " +
                "ON `paper_order_reconciliation` (`terminal`, `resetAcknowledgedAtEpochMillis`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_reconciliation_submitted_at` " +
                "ON `paper_order_reconciliation` (`submittedAtEpochMillis`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `paper_order_lifecycle_observation` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `observationKey` TEXT NOT NULL,
                `submitAttemptId` TEXT NOT NULL,
                `alpacaOrderId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `rawStatus` TEXT NOT NULL,
                `observedAtEpochMillis` INTEGER NOT NULL,
                `terminal` INTEGER NOT NULL,
                `filledQuantity` REAL,
                `filledAveragePriceUsd` REAL,
                `filledAtIso` TEXT,
                `source` TEXT NOT NULL,
                `httpStatusCode` INTEGER,
                `submitAuditEntryId` INTEGER,
                FOREIGN KEY(`submitAttemptId`) REFERENCES `paper_order_reconciliation`(`submitAttemptId`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ix_paper_lifecycle_observation_key` " +
                "ON `paper_order_lifecycle_observation` (`observationKey`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_lifecycle_attempt_id` " +
                "ON `paper_order_lifecycle_observation` (`submitAttemptId`, `id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_lifecycle_order_id` " +
                "ON `paper_order_lifecycle_observation` (`alpacaOrderId`, `id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_lifecycle_status` " +
                "ON `paper_order_lifecycle_observation` (`status`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_lifecycle_observed_at` " +
                "ON `paper_order_lifecycle_observation` (`observedAtEpochMillis`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_lifecycle_submit_audit_id` " +
                "ON `paper_order_lifecycle_observation` (`submitAuditEntryId`)",
        )
    }
}
