package com.vela.android.lab.db.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vela.android.lab.db.room.converters.InstantConverter
import com.vela.android.lab.db.room.dao.FeatureDao
import com.vela.android.lab.db.room.dao.JournalDao
import com.vela.android.lab.db.room.dao.MarketBarDao
import com.vela.android.lab.db.room.dao.PaperOrderDryRunAuditDao
import com.vela.android.lab.db.room.dao.PaperOrderPayloadPreviewDao
import com.vela.android.lab.db.room.dao.PaperOrderSubmitAuditDao
import com.vela.android.lab.db.room.dao.SignalDao
import com.vela.android.lab.db.room.entities.JournalEventEntity
import com.vela.android.lab.db.room.entities.MarketBar1mEntity
import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity
import com.vela.android.lab.db.room.entities.PaperOrderPayloadPreviewEntity
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity
import com.vela.android.lab.db.room.entities.SymbolFeaturesEntity
import com.vela.android.lab.db.room.entities.SymbolSignalEntity
import com.vela.android.lab.db.room.migrations.MIGRATION_4_5

/**
 * Phase 1.c Room database: the offline persistence foundation for
 * VELA Android. Stored under app-private `filesDir` only.
 *
 *  - **No** Alpaca tables yet (account snapshots, observer reports,
 *    auto-paper decisions, learning sources). Those land in their
 *    respective feature phases.
 *  - **No** schema sharing with the Windows `vela.db`. The Android
 *    lab database starts at schema version 1, independent of the
 *    Windows ladder.
 *  - **No** auto-import from any external location. The database is
 *    created fresh on first run.
 *
 * Schema JSON files are exported by the Room compiler to
 * `app/schemas/com.vela.android.lab.db.room.VelaDatabase/1.json`.
 */
@Database(
    version = 5,
    exportSchema = true,
    entities = [
        MarketBar1mEntity::class,
        SymbolFeaturesEntity::class,
        SymbolSignalEntity::class,
        JournalEventEntity::class,
        PaperOrderDryRunAuditEntity::class,
        PaperOrderPayloadPreviewEntity::class,
        PaperOrderSubmitAuditEntity::class,
    ],
)
@TypeConverters(InstantConverter::class)
abstract class VelaDatabase : RoomDatabase() {

    abstract fun marketBarDao(): MarketBarDao
    abstract fun featureDao(): FeatureDao
    abstract fun signalDao(): SignalDao
    abstract fun journalDao(): JournalDao
    abstract fun paperOrderDryRunAuditDao(): PaperOrderDryRunAuditDao
    abstract fun paperOrderPayloadPreviewDao(): PaperOrderPayloadPreviewDao
    abstract fun paperOrderSubmitAuditDao(): PaperOrderSubmitAuditDao

    companion object {
        const val DATABASE_NAME: String = "vela-lab.db"

        /**
         * On-disk app-private database. The Android framework places
         * the file under `Context.filesDir/../databases/<name>.db`,
         * which is sandboxed to this app's user ID and never visible
         * to other apps. No backup agent will read it because
         * `allowBackup=false` in the manifest.
         */
        fun create(context: Context): VelaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                VelaDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_4_5)
                // Phase 2.q: dev-lab database; throwaway Phase 1.e
                // bars / features / signals / journal rows are
                // acceptable losses on schema bump. Watchlist + Paper
                // credentials live in SharedPreferences / Keystore and
                // survive the rebuild.
                .fallbackToDestructiveMigration()
                .build()

        /**
         * In-memory database for tests and one-shot diagnostics.
         */
        fun createInMemory(context: Context): VelaDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                VelaDatabase::class.java,
            ).allowMainThreadQueries().build()
    }
}
