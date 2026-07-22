package com.vela.android.lab.db.room.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive Phase 2.v migration. Existing dry-run and preview data is preserved. */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `paper_order_submit_audit` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `eventKey` TEXT NOT NULL,
                `submitAttemptId` TEXT NOT NULL,
                `previewId` TEXT NOT NULL,
                `linkedClientDryRunId` TEXT NOT NULL,
                `clientOrderId` TEXT NOT NULL,
                `symbol` TEXT NOT NULL,
                `side` TEXT NOT NULL,
                `quantity` REAL NOT NULL,
                `orderType` TEXT NOT NULL,
                `timeInForce` TEXT NOT NULL,
                `limitPriceUsd` REAL,
                `status` TEXT NOT NULL,
                `alpacaOrderId` TEXT,
                `submittedAtEpochMillis` INTEGER NOT NULL,
                `safeErrorMessage` TEXT,
                `priceSource` TEXT NOT NULL,
                `priceFreshness` TEXT NOT NULL,
                `marketOpen` INTEGER NOT NULL,
                `confirmationTokenId` TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `ix_paper_submit_event_key` " +
                "ON `paper_order_submit_audit` (`eventKey`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_submit_attempt_id` " +
                "ON `paper_order_submit_audit` (`submitAttemptId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_submit_preview_id` " +
                "ON `paper_order_submit_audit` (`previewId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_submit_client_order_id` " +
                "ON `paper_order_submit_audit` (`clientOrderId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_submit_symbol_time` " +
                "ON `paper_order_submit_audit` (`symbol`, `submittedAtEpochMillis`)",
        )
    }
}
