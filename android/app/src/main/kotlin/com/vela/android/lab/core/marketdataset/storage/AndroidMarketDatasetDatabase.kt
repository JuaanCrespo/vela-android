package com.vela.android.lab.core.marketdataset.storage

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteTableLockedException
import com.vela.android.lab.core.marketdataset.domain.DatasetId

/** Calling open is the only entry point. Merely loading this object does no IO. */
object AndroidMarketDatasetDatabase {
    const val DATABASE_NAME = "vela_market_dataset.db"

    fun open(context: Context, datasetId: DatasetId): MarketDatasetStore = reportBusy {
        val file = context.applicationContext.getDatabasePath(DATABASE_NAME)
        val directory = file.parentFile ?: throw MarketStorageException(MarketStorageError.SQL_FAILURE, "PRIVATE_DIRECTORY")
        demand(directory.isDirectory || directory.mkdirs(), MarketStorageError.SQL_FAILURE, "PRIVATE_DIRECTORY")
        val parameters = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            .setJournalMode("DELETE").setSynchronousMode("FULL")
            // Android's default corruption handler can delete files. This store must preserve evidence.
            .setErrorHandler(DatabaseErrorHandler { throw MarketStorageException(MarketStorageError.DATA_INTEGRITY_ERROR, "SQLITE_CORRUPTION") })
            .build()
        val db = SQLiteDatabase.openDatabase(file, parameters)
        try {
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA recursive_triggers=ON")
            // Android owns busy handling on its connections; no fixed millisecond guarantee.
            // Keep lock exceptions distinct via reportBusy, without adapter-level retries.
            demand(db.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) } == "delete", MarketStorageError.INCOMPATIBLE_SCHEMA, "JOURNAL_MODE")
            demand(db.rawQuery("PRAGMA synchronous", null).use { it.moveToFirst(); it.getInt(0) } == 2, MarketStorageError.INCOMPATIBLE_SCHEMA, "SYNCHRONOUS")
        } catch (failure: Throwable) { db.close(); throw failure }
        MarketDatasetStore.open(AndroidConnection(db), datasetId)
    }

    private fun <T> reportBusy(block: () -> T): T = try { block() }
    catch (_: SQLiteDatabaseLockedException) { throw MarketStorageException(MarketStorageError.SQL_BUSY) }
    catch (_: SQLiteTableLockedException) { throw MarketStorageException(MarketStorageError.SQL_BUSY) }

    private class AndroidConnection(private val db: SQLiteDatabase) : MarketSqlConnection {
        override fun execute(sql: String, arguments: List<String?>) {
            db.execSQL(sql, arguments.toTypedArray())
        }
        override fun query(sql: String, arguments: List<String?>): List<Map<String, String?>> =
            db.rawQuery(sql, arguments.toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.columnNames.withIndex().associate { (index, name) -> name to if (cursor.isNull(index)) null else cursor.getString(index) })
                }
            }
        override fun <T> transaction(block: () -> T): T = reportBusy {
            db.beginTransactionNonExclusive()
            try { val result = block(); db.setTransactionSuccessful(); result }
            finally { db.endTransaction() }
        }
        override fun close() = db.close()
    }
}
