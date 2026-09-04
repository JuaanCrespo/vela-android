package com.vela.android.lab.db.room.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive Phase 2.x.2 submit-evidence migration. Legacy values intentionally remain null. */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `paper_order_submit_audit` " +
                "ADD COLUMN `submitHttpStatusCode` INTEGER",
        )
        db.execSQL(
            "ALTER TABLE `paper_order_submit_audit` " +
                "ADD COLUMN `initialAlpacaStatus` TEXT",
        )
        db.execSQL(
            "ALTER TABLE `paper_order_submit_audit` " +
                "ADD COLUMN `alpacaSubmittedAtIso` TEXT",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_submit_alpaca_order_id` " +
                "ON `paper_order_submit_audit` (`alpacaOrderId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_submit_time` " +
                "ON `paper_order_submit_audit` (`submittedAtEpochMillis`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `ix_paper_submit_side_time` " +
                "ON `paper_order_submit_audit` (`side`, `submittedAtEpochMillis`)",
        )
    }
}
