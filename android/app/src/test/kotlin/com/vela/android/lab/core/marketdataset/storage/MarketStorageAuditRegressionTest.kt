package com.vela.android.lab.core.marketdataset.storage

import com.vela.android.lab.core.marketdataset.domain.*
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MarketStorageAuditRegressionTest {
    @TempDir lateinit var directory: Path
    private fun file() = directory.resolve("audit-market.sqlite")
    private fun open(sql: JdbcMarketConnection = JdbcMarketConnection(file())) = MarketDatasetStore.open(sql, storageDataset)

    @Test fun `writer contention is retryable SQL_BUSY not a permanent evidence conflict`() {
        val ownerSql = JdbcMarketConnection(file())
        val owner = open(ownerSql)
        owner.append(MarketAppendBatch(sessions = listOf(registration())))
        val contenderSql = JdbcMarketConnection(file())
        val contender = open(contenderSql)
        try {
            contenderSql.execute("PRAGMA busy_timeout=0")
            ownerSql.execute("BEGIN IMMEDIATE")
            try {
                val failure = assertThrows(MarketStorageException::class.java) {
                    contender.append(MarketAppendBatch(receipts = listOf(storageEvent())))
                }
                assertEquals("SQL_BUSY", failure.code.name)
                assertEquals(0, ownerSql.count("raw_receipts"))
            } finally { ownerSql.execute("ROLLBACK") }
            assertEquals(1, contender.append(MarketAppendBatch(receipts = listOf(storageEvent()))).insertedReceipts)
            assertEquals(IngestSequence(0), owner.readHighWater(IngestSessionId("A")))
            assertTrue(contender.verifyIntegrity().valid)
        } finally { contender.close(); owner.close() }
    }

    @ParameterizedTest @ValueSource(strings = ["session", "provider", "feed", "source_class"])
    fun `integrity visits identity fields of every session even without raw receipts`(field: String) {
        val sql = JdbcMarketConnection(file())
        open(sql).use { store ->
            assertTrue(store.verifyIntegrity().valid)
            val fields = linkedMapOf<String, String?>("session_id" to "empty-ledger", "provider" to "ALPACA", "feed" to "IEX", "source_class" to "REAL_PROVIDER")
            if (field == "session") fields["session_id"] = "" else fields[field] = "invalid-lowercase"
            sql.insert("ingest_sessions", fields)
            sql.insert("session_high_water", linkedMapOf("session_id" to fields.getValue("session_id"), "sequence" to null))
            assertTrue(sql.query("PRAGMA foreign_key_check").isEmpty())
            val changes = sql.query("SELECT total_changes() AS amount").single().number("amount")
            val report = store.verifyIntegrity()
            assertFalse(report.valid)
            assertTrue(report.issues.any { it.reason == "SESSION_IDENTITY" })
            assertEquals(changes, sql.query("SELECT total_changes() AS amount").single().number("amount"))
        }
    }

    @ParameterizedTest @ValueSource(strings = ["ahead", "missing", "retry"])
    fun `append rejects corrupted preexisting high water even for an identical retry`(defect: String) {
        val sql = JdbcMarketConnection(file())
        open(sql).use { store ->
            store.append(MarketAppendBatch(listOf(registration()), listOf(storageEvent())))
            if (defect == "missing") {
                sql.execute("DROP TRIGGER session_high_water_delete_blocked")
                sql.execute("DELETE FROM session_high_water")
                sql.execute(MarketDatasetSchema.triggers.getValue("session_high_water_delete_blocked"))
            } else {
                sql.execute("DROP TRIGGER high_water_guard")
                sql.execute("UPDATE session_high_water SET sequence=9")
                sql.execute(MarketDatasetSchema.triggers.getValue("high_water_guard"))
            }
            val next = if (defect == "retry") storageEvent() else storageEvent(if (defect == "ahead") 10 else 0)
            val newSession = IngestSessionRegistration(IngestSessionId("must-rollback"), storageSource)
            storeError(MarketStorageError.DATA_INTEGRITY_ERROR) { store.append(MarketAppendBatch(listOf(newSession), listOf(next))) }
            assertEquals(1, sql.count("raw_receipts")); assertEquals(1, sql.count("ingest_sessions"))
            assertEquals(if (defect == "missing") 0 else 1, sql.count("session_high_water"))
            assertFalse(store.verifyIntegrity().valid)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["market_metadata", "ingest_sessions", "session_high_water", "raw_receipts", "canonical_revisions", "bar_lineage", "bar_quality_flags"])
    fun `direct UPDATE DELETE REPLACE and UPSERT cannot mutate any historical table`(table: String) {
        val sql = JdbcMarketConnection(file())
        open(sql).use { store ->
            store.append(MarketAppendBatch(listOf(registration()), listOf(storageEvent()), listOf(storageBar())))
            val before = sql.query("SELECT * FROM $table")
            val fields = before.single()
            val column = fields.keys.first()
            val insert = "INTO $table (${fields.keys.joinToString()}) VALUES (${fields.keys.joinToString { "?" }})"
            val attempts = listOf(
                "UPDATE $table SET $column=$column" to emptyList(),
                "DELETE FROM $table" to emptyList(),
                "INSERT OR REPLACE $insert" to fields.values.toList(),
                "INSERT $insert ON CONFLICT DO UPDATE SET $column=excluded.$column" to fields.values.toList(),
            )
            for ((statement, arguments) in attempts) {
                val failure = assertThrows(java.sql.SQLException::class.java) { sql.execute(statement, arguments) }
                assertEquals(19, failure.errorCode and 255) // constraint/trigger rejection, not a syntax error
                assertEquals(before, sql.query("SELECT * FROM $table"))
                assertEquals(IngestSequence(0), store.readHighWater(IngestSessionId("A")))
                assertTrue(store.verifyIntegrity().valid)
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = ["missing_table", "wrong_column", "missing_trigger", "missing_index", "format", "schema_fingerprint", "db_version", "dataset_id"])
    fun `every structural or metadata mismatch fails closed without recreation`(defect: String) {
        open().use { it.append(MarketAppendBatch(listOf(registration()), listOf(storageEvent()), listOf(storageBar()))) }
        JdbcMarketConnection(file()).use { sql ->
            when (defect) {
                "missing_table" -> sql.execute("DROP TABLE bar_quality_flags")
                "wrong_column" -> sql.execute("ALTER TABLE raw_receipts ADD COLUMN unexpected TEXT")
                "missing_trigger" -> sql.execute("DROP TRIGGER raw_sequence_guard")
                "missing_index" -> sql.execute("DROP INDEX raw_content")
                else -> {
                    sql.execute("DROP TRIGGER market_metadata_update_blocked")
                    sql.execute("UPDATE market_metadata SET $defect=?", listOf(if (defect == "db_version") "2" else "invalid"))
                    sql.execute(MarketDatasetSchema.triggers.getValue("market_metadata_update_blocked"))
                }
            }
        }
        storeError(if (defect == "dataset_id") MarketStorageError.WRONG_DATASET else MarketStorageError.INCOMPATIBLE_SCHEMA) { open() }
        JdbcMarketConnection(file()).use { sql ->
            assertEquals(1, sql.count("raw_receipts")); assertEquals(1, sql.count("canonical_revisions"))
            when (defect) {
                "missing_table" -> assertTrue(sql.query("SELECT name FROM sqlite_master WHERE name='bar_quality_flags'").isEmpty())
                "wrong_column" -> assertTrue(sql.query("PRAGMA table_info(raw_receipts)").any { it["name"] == "unexpected" })
                "missing_trigger" -> assertTrue(sql.query("SELECT name FROM sqlite_master WHERE name='raw_sequence_guard'").isEmpty())
                "missing_index" -> assertTrue(sql.query("SELECT name FROM sqlite_master WHERE name='raw_content'").isEmpty())
                else -> assertEquals(if (defect == "db_version") "2" else "invalid", sql.query("SELECT $defect FROM market_metadata").single().text(defect))
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `sqlite underscore is a literal internal prefix not a LIKE wildcard`(alreadyMarketDatabase: Boolean) {
        if (alreadyMarketDatabase) open().close()
        JdbcMarketConnection(file()).use { sql ->
            assertEquals("1", sql.query("SELECT 'sqliteXmarker' LIKE 'sqlite_%' AS old_filter").single().text("old_filter"))
            assertEquals("0", sql.query("SELECT 'sqliteXmarker' GLOB 'sqlite_*' AS exact_filter").single().text("exact_filter"))
            sql.execute("CREATE TABLE sqliteXmarker (value TEXT)")
            sql.execute("INSERT INTO sqliteXmarker VALUES ('preserve')")
        }
        storeError(MarketStorageError.INCOMPATIBLE_SCHEMA) { open().close() }
        JdbcMarketConnection(file()).use { sql ->
            assertEquals("preserve", sql.query("SELECT value FROM sqliteXmarker").single().text("value"))
            assertEquals(if (alreadyMarketDatabase) "1" else "0", sql.query("PRAGMA user_version").single().values.single())
            if (!alreadyMarketDatabase) assertTrue(sql.query("SELECT name FROM sqlite_master WHERE name='market_metadata'").isEmpty())
        }
    }

    @Test fun `all nanosecond time components and negative bar boundaries survive reopen`() {
        val first = storageEvent().copy(eventTime = storageTime(-1, 999999998), receivedAt = storageTime(60, 999999998), availableAt = storageTime(61, 0))
        val second = storageEvent(1, bid = "1.00").copy(eventTime = storageTime(-1, 999999999), receivedAt = storageTime(60, 999999999), availableAt = storageTime(61, 1))
        val original = storageBar(first).copy(identity = storageBar(first).identity.copy(startTime = storageTime(-60)), endTime = storageTime(0))
        val revised = original.copy(receivedAt = second.receivedAt, availableAt = second.availableAt, finality = BarFinality.REVISED,
            revision = BarRevision(1, original.contentFingerprint), evidence = BarEvidence(listOf(second.reference()), listOf(BarQualityFlag.LATE)))
        val sql = JdbcMarketConnection(file())
        open(sql).use { store ->
            store.append(MarketAppendBatch(listOf(registration()), listOf(first, second), listOf(original, revised)))
            val actual = sql.query("SELECT event_sec,event_nano,received_sec,received_nano,available_sec,available_nano FROM raw_receipts WHERE sequence=0").single()
            assertEquals(mapOf("event_sec" to "-1", "event_nano" to "999999998", "received_sec" to "60", "received_nano" to "999999998", "available_sec" to "61", "available_nano" to "0"), actual)
        }
        open().use { store ->
            assertEquals(first, store.readReceipt(first.ingestPosition)); assertEquals(second, store.readReceipt(second.ingestPosition))
            assertEquals(original, store.readRevision(original.revisionIdentity)); assertEquals(revised, store.readRevision(revised.revisionIdentity))
            val read = store.readRevision(original.revisionIdentity)!!
            assertEquals(-60L, read.identity.startTime.epochSecond); assertEquals(0L, read.endTime.epochSecond)
            assertTrue(store.readRevision(original.revisionIdentity)!!.availableAt < store.readRevision(revised.revisionIdentity)!!.availableAt)
            assertTrue(store.verifyIntegrity().valid)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["schema", "canonicalization"])
    fun `revision two uses revision one content and processing versions keep chains separate`(dimension: String) {
        val events = (0L..2L).map { index -> storageEvent(index).copy(receivedAt = storageTime(60 + index * 60), availableAt = storageTime(60 + index * 60)) }
        val zero = storageBar(events[0])
        val one = zero.copy(receivedAt = events[1].receivedAt, availableAt = events[1].availableAt, revision = BarRevision(1, zero.contentFingerprint),
            finality = BarFinality.REVISED, evidence = BarEvidence(listOf(events[1].reference()), emptyList()))
        val two = one.copy(receivedAt = events[2].receivedAt, availableAt = events[2].availableAt, revision = BarRevision(2, one.contentFingerprint),
            evidence = BarEvidence(listOf(events[2].reference()), emptyList()))
        val versionedZero = if (dimension == "schema") zero.copy(schemaVersion = DatasetSchemaVersion(2)) else zero.copy(canonicalizationVersion = CanonicalizationVersion(2))
        val versionedOne = versionedZero.copy(receivedAt = one.receivedAt, availableAt = one.availableAt, finality = BarFinality.REVISED,
            revision = BarRevision(1, versionedZero.contentFingerprint), evidence = one.evidence)
        open().use { store ->
            store.append(MarketAppendBatch(listOf(registration()), events, listOf(zero, one, two, versionedZero)))
            storeError(MarketStorageError.REVISION_PARENT) { store.append(MarketAppendBatch(revisions = listOf(versionedOne.copy(revision = BarRevision(1, zero.contentFingerprint))))) }
            store.append(MarketAppendBatch(revisions = listOf(versionedOne)))
        }
        open().use { store ->
            for (expected in listOf(zero, one, two, versionedZero, versionedOne)) assertEquals(expected, store.readRevision(expected.revisionIdentity))
            assertEquals(zero.identity, versionedZero.identity); assertNotEquals(zero.revisionIdentity, versionedZero.revisionIdentity)
            assertEquals(one.contentFingerprint, store.readRevision(two.revisionIdentity)!!.revision.previousRevisionFingerprint)
            assertTrue(store.verifyIntegrity().valid)
        }
    }

    @Test fun `independent schema manifest verifies columns affinities keys and index definitions`() {
        val sql = JdbcMarketConnection(file())
        open(sql).use { store ->
            fun columns(table: String, text: String, integer: String) {
                val expected = text.split(' ').filter { it.isNotEmpty() }.associateWith { "TEXT" } + integer.split(' ').filter { it.isNotEmpty() }.associateWith { "INTEGER" }
                assertEquals(expected, sql.query("PRAGMA table_info($table)").associate { it.text("name") to it.text("type") }, table)
            }
            val identity = "provider feed source_class asset_class canonical_symbol provider_symbol instrument_fp"
            columns("market_metadata", "format dataset_id schema_fingerprint", "singleton db_version")
            columns("ingest_sessions", "session_id provider feed source_class", "")
            columns("session_high_water", "session_id", "sequence")
            columns("raw_receipts", "session_id $identity event_type provider_event_id event_identity_kind event_identity_fp content_fp receipt_fp bid ask bid_size ask_size trade_price trade_size open_value high_value low_value close_value volume vwap digest_fp",
                "sequence event_sec event_nano received_sec received_nano available_sec available_nano dataset_schema_version parser_version trade_count")
            columns("canonical_revisions", "revision_id logical_id $identity open_value high_value low_value close_value volume vwap finality previous_content_fp content_fp",
                "interval_seconds start_sec start_nano end_sec end_nano received_sec received_nano available_sec available_nano trade_count revision_number dataset_schema_version canonical_version")
            columns("bar_lineage", "revision_id session_id content_fp", "sequence dataset_schema_version parser_version")
            columns("bar_quality_flags", "revision_id flag", "")
            val primaryKeys = mapOf("market_metadata" to listOf("singleton"), "ingest_sessions" to listOf("session_id"), "session_high_water" to listOf("session_id"),
                "raw_receipts" to listOf("session_id", "sequence"), "canonical_revisions" to listOf("revision_id"), "bar_lineage" to listOf("revision_id", "session_id", "sequence"), "bar_quality_flags" to listOf("revision_id", "flag"))
            primaryKeys.forEach { (table, key) -> assertEquals(key, sql.query("PRAGMA table_info($table)").filter { it.number("pk") > 0 }.sortedBy { it.number("pk") }.map { it.text("name") }) }
            val expectedIndexes = mapOf("raw_content" to "content_fp", "raw_event_identity" to "event_identity_kind event_identity_fp",
                "raw_series_time" to "$identity event_sec event_nano".replace(" instrument_fp", ""), "raw_availability" to "available_sec available_nano",
                "bar_availability" to "available_sec available_nano", "bar_series_time" to "provider feed source_class asset_class canonical_symbol provider_symbol interval_seconds start_sec start_nano",
                "lineage_receipt" to "session_id sequence")
            assertEquals(expectedIndexes.keys, sql.query("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'").map { it.text("name") }.toSet())
            expectedIndexes.forEach { (index, fields) -> assertEquals(fields.split(' '), sql.query("PRAGMA index_info($index)").sortedBy { it.number("seqno") }.map { it.text("name") }) }
            assertEquals(listOf("canonical_revisions"), sql.query("PRAGMA foreign_key_list(canonical_revisions)").map { it.text("table") }.distinct())
            assertEquals("content_fp", sql.query("PRAGMA foreign_key_list(canonical_revisions)").single().text("to"))
            assertEquals(setOf("canonical_revisions", "raw_receipts"), sql.query("PRAGMA foreign_key_list(bar_lineage)").map { it.text("table") }.toSet())
            assertTrue(sql.query("SELECT sql FROM sqlite_master WHERE type='table'").none { it.text("sql").contains("CASCADE", ignoreCase = true) })
            assertEquals("a539bc2a020922419c27ae782d8c4bd43f02af4c642b1930dd8c59bc0bea6e47", sql.query("SELECT schema_fingerprint FROM market_metadata").single().text("schema_fingerprint"))
            assertEquals(16, sql.query("SELECT name FROM sqlite_master WHERE type='trigger'").size)
            assertTrue(store.verifyIntegrity().valid)
        }
    }
}
