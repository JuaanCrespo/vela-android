package com.vela.android.lab.db.room.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive metadata only. No source evidence or historical result is rewritten. */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) { BOOTSTRAP_V9_SQL.forEach { db.execSQL(it) } }
}

internal val BOOTSTRAP_V9_SQL = listOf(
    "ALTER TABLE paper_position_anchor ADD COLUMN coverageMode TEXT NOT NULL DEFAULT 'EXACT_CURSORS_V1'",
    "ALTER TABLE paper_position_anchor ADD COLUMN bootstrapCutJson TEXT",
    "ALTER TABLE paper_position_anchor ADD COLUMN bootstrapCutDigest TEXT",
    "ALTER TABLE paper_position_reconciliation_row ADD COLUMN coverageJson TEXT",
)
