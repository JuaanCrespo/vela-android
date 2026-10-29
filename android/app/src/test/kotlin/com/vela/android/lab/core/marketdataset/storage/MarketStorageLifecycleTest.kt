package com.vela.android.lab.core.marketdataset.storage

import com.vela.android.lab.core.marketdataset.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MarketStorageLifecycleTest {
    @TempDir lateinit var directory: Path
    private fun file() = directory.resolve("market.sqlite")
    private fun open() = MarketDatasetStore.open(JdbcMarketConnection(file()), storageDataset)

    @Test fun `close reopen retains exact evidence revisions high water and 10 03 cut`() {
        val first = storageEvent(); val bar = storageBar(first)
        val late = storageEvent(1).copy(receivedAt = storageTime(300), availableAt = storageTime(300))
        val revised = bar.copy(receivedAt = late.receivedAt, availableAt = late.availableAt, revision = BarRevision(1, bar.contentFingerprint),
            finality = BarFinality.REVISED, evidence = BarEvidence(listOf(late.reference()), listOf(BarQualityFlag.LATE)))
        open().use { it.append(MarketAppendBatch(listOf(registration()), listOf(first, late), listOf(bar, revised))) }
        open().use { store ->
            assertEquals(first, store.readReceipt(first.ingestPosition)); assertEquals(late, store.readReceipt(late.ingestPosition))
            assertEquals(IngestSequence(1), store.readHighWater(first.ingestPosition.session))
            assertEquals(bar, store.readRevision(bar.revisionIdentity)); assertEquals(revised, store.readRevision(revised.revisionIdentity))
            val cut = DatasetSnapshotSpec(storageDataset, DatasetSchemaVersion(1), ParserVersion(1), CanonicalizationVersion(1), QualityPolicyVersion(1),
                DatasetSnapshotScope(listOf(first.source), listOf(first.instrument), listOf(late.ingestPosition)), MarketTimeRange(storageTime(0), storageTime(360)), storageTime(180))
            assertTrue(cut.includes(store.readRevision(bar.revisionIdentity)!!)); assertFalse(cut.includes(store.readRevision(revised.revisionIdentity)!!))
            assertTrue(store.verifyIntegrity().valid)
        }
    }
    @Test fun `connection loss with uncommitted SQL rolls back raw and high water on reopen`() {
        open().use { it.append(MarketAppendBatch(sessions = listOf(registration()))) }
        val abandoned = JdbcMarketConnection(file())
        abandoned.execute("BEGIN IMMEDIATE")
        abandoned.insert("raw_receipts", MarketRowCodec.raw(storageEvent()))
        assertEquals(1, abandoned.count("raw_receipts"))
        abandoned.close() // Simulates loss of a writer connection, not an Android/device process kill.
        open().use { store ->
            assertNull(store.readReceipt(storageEvent().ingestPosition)); assertNull(store.readHighWater(IngestSessionId("A")))
            assertEquals(1, store.append(MarketAppendBatch(receipts = listOf(storageEvent()))).insertedReceipts)
            assertTrue(store.verifyIntegrity().valid)
        }
    }
    @ParameterizedTest @ValueSource(booleans = [true, false])
    fun `two writer connections serialize identical retries or conflicting receipt claims`(sameEvidence: Boolean) {
        open().use { it.append(MarketAppendBatch(sessions = listOf(registration()))) }
        val first = open(); val second = open()
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2); val start = CountDownLatch(1)
        try {
            val futures = listOf(first to storageEvent(), second to storageEvent(bid = if (sameEvidence) "1.0" else "1.1")).map { (store, event) ->
                pool.submit<String> {
                    ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                    try { val result = store.append(MarketAppendBatch(receipts = listOf(event))); if (result.insertedReceipts == 1) "INSERTED" else "RETRY" }
                    catch (failure: MarketStorageException) { failure.code.name }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown()
            assertEquals(if (sameEvidence) listOf("INSERTED", "RETRY") else listOf("INSERTED", "RECEIPT_CONFLICT"), futures.map { it.get(15, TimeUnit.SECONDS) }.sorted())
            assertEquals(IngestSequence(0), first.readHighWater(IngestSessionId("A"))); assertTrue(first.verifyIntegrity().valid)
            assertEquals(first.readReceipt(storageEvent().ingestPosition), second.readReceipt(storageEvent().ingestPosition))
        } finally { start.countDown(); pool.shutdownNow(); first.close(); second.close() }
        open().use { assertTrue(it.verifyIntegrity().valid) }
    }
    @ParameterizedTest @ValueSource(ints = [-1, 2, 9, 2147483647])
    fun `unknown newer schema fails closed and preserves unrelated marker evidence`(version: Int) {
        JdbcMarketConnection(file()).use { sql -> sql.execute("CREATE TABLE marker (value TEXT)"); sql.execute("INSERT INTO marker VALUES ('preserve')"); sql.execute("PRAGMA user_version=$version") }
        storeError(MarketStorageError.UNSUPPORTED_SCHEMA) { open() }
        JdbcMarketConnection(file()).use { assertEquals("preserve", it.query("SELECT value FROM marker").single().text("value")); assertEquals(version.toString(), it.query("PRAGMA user_version").single().values.single()) }
    }
    @Test fun `nonempty version zero database is not treated as fresh or recreated`() {
        JdbcMarketConnection(file()).use { it.execute("CREATE TABLE marker (value TEXT)"); it.execute("INSERT INTO marker VALUES ('preserve')") }
        storeError(MarketStorageError.INCOMPATIBLE_SCHEMA) { open() }
        JdbcMarketConnection(file()).use { assertEquals("preserve", it.query("SELECT value FROM marker").single().text("value")) }
    }
    @Test fun `wrong dataset namespace does not attach to or recreate an existing market store`() {
        open().use { it.append(MarketAppendBatch(listOf(registration()), listOf(storageEvent()))) }
        storeError(MarketStorageError.WRONG_DATASET) { MarketDatasetStore.open(JdbcMarketConnection(file()), DatasetId("another-dataset")) }
        open().use { assertEquals(storageEvent(), it.readReceipt(storageEvent().ingestPosition)) }
    }
    @Test fun `incompatible schema fails without dropping or repairing evidence`() {
        open().use { it.append(MarketAppendBatch(listOf(registration()), listOf(storageEvent()))) }
        JdbcMarketConnection(file()).use { it.execute("DROP INDEX raw_content") }
        storeError(MarketStorageError.INCOMPATIBLE_SCHEMA) { open() }
        JdbcMarketConnection(file()).use { assertEquals(1, it.count("raw_receipts")); assertTrue(it.query("SELECT name FROM sqlite_master WHERE name='raw_content'").isEmpty()) }
    }
    @Test fun `integrity verifier is read only and diagnoses corrupted high water without repair`() {
        val sql = JdbcMarketConnection(file())
        MarketDatasetStore.open(sql, storageDataset).use { store ->
            store.append(MarketAppendBatch(listOf(registration()), listOf(storageEvent()), listOf(storageBar())))
            val before = sql.query("SELECT total_changes() AS amount").single().number("amount")
            assertTrue(store.verifyIntegrity().valid)
            assertEquals(before, sql.query("SELECT total_changes() AS amount").single().number("amount"))
            sql.execute("DROP TRIGGER high_water_guard"); sql.execute("UPDATE session_high_water SET sequence=9"); sql.execute(MarketDatasetSchema.triggers.getValue("high_water_guard"))
            val corruptChanges = sql.query("SELECT total_changes() AS amount").single().number("amount")
            val report = store.verifyIntegrity()
            assertFalse(report.valid); assertTrue(report.issues.any { it.reason == "HIGH_WATER" })
            assertEquals("HIGH_WATER", storeError(MarketStorageError.DATA_INTEGRITY_ERROR) { store.readHighWater(IngestSessionId("A")) }.diagnostic)
            assertEquals("9", sql.query("SELECT sequence FROM session_high_water").single().text("sequence"))
            assertEquals(corruptChanges, sql.query("SELECT total_changes() AS amount").single().number("amount"))
        }
    }
    @Test fun `rehydration rejects fingerprint mismatch and ignored payload field corruption`() {
        val sql = JdbcMarketConnection(file())
        MarketDatasetStore.open(sql, storageDataset).use { store ->
            store.append(MarketAppendBatch(listOf(registration()), listOf(storageEvent()), listOf(storageBar())))
            sql.execute("DROP TRIGGER raw_receipts_update_blocked")
            sql.execute("UPDATE raw_receipts SET content_fp=?", listOf("0".repeat(64)))
            sql.execute(MarketDatasetSchema.triggers.getValue("raw_receipts_update_blocked"))
            storeError(MarketStorageError.DATA_INTEGRITY_ERROR) { store.readReceipt(storageEvent().ingestPosition) }
            storeError(MarketStorageError.DATA_INTEGRITY_ERROR) { store.readRevision(storageBar().revisionIdentity) }
            assertTrue(store.verifyIntegrity().issues.any { it.reason == "RAW_CONTENT_OR_IDENTITY" })
            sql.execute("DROP TRIGGER raw_receipts_update_blocked")
            sql.execute("UPDATE raw_receipts SET content_fp=?,trade_price='999'", listOf(storageEvent().contentFingerprint.hex))
            sql.execute(MarketDatasetSchema.triggers.getValue("raw_receipts_update_blocked"))
            storeError(MarketStorageError.DATA_INTEGRITY_ERROR) { store.readReceipt(storageEvent().ingestPosition) }
        }
    }
    @Test fun `canonical and lineage tampering produce deterministic integrity errors`() {
        val sql = JdbcMarketConnection(file())
        MarketDatasetStore.open(sql, storageDataset).use { store ->
            store.append(MarketAppendBatch(listOf(registration()), listOf(storageEvent()), listOf(storageBar())))
            sql.execute("DROP TRIGGER canonical_revisions_update_blocked")
            sql.execute("UPDATE canonical_revisions SET content_fp=?", listOf("0".repeat(64)))
            sql.execute(MarketDatasetSchema.triggers.getValue("canonical_revisions_update_blocked"))
            storeError(MarketStorageError.DATA_INTEGRITY_ERROR) { store.readRevision(storageBar().revisionIdentity) }
            sql.execute("DROP TRIGGER canonical_revisions_update_blocked")
            sql.execute("UPDATE canonical_revisions SET content_fp=?", listOf(storageBar().contentFingerprint.hex))
            sql.execute(MarketDatasetSchema.triggers.getValue("canonical_revisions_update_blocked"))
            sql.execute("DROP TRIGGER canonical_revisions_update_blocked"); sql.execute("UPDATE canonical_revisions SET open_value='1.00'")
            sql.execute(MarketDatasetSchema.triggers.getValue("canonical_revisions_update_blocked"))
            storeError(MarketStorageError.DATA_INTEGRITY_ERROR) { store.readRevision(storageBar().revisionIdentity) }
            sql.execute("DROP TRIGGER canonical_revisions_update_blocked"); sql.execute("UPDATE canonical_revisions SET open_value='1'")
            sql.execute(MarketDatasetSchema.triggers.getValue("canonical_revisions_update_blocked"))
            sql.execute("DROP TRIGGER bar_lineage_update_blocked"); sql.execute("UPDATE bar_lineage SET parser_version=2")
            sql.execute(MarketDatasetSchema.triggers.getValue("bar_lineage_update_blocked"))
            val failure = storeError(MarketStorageError.DATA_INTEGRITY_ERROR) { store.readRevision(storageBar().revisionIdentity) }
            assertEquals("LINEAGE_CONTENT", failure.diagnostic)
            assertFalse(store.verifyIntegrity().valid)
        }
    }
    @Test fun `close is idempotent and closed store access fails explicitly`() {
        val store = open(); store.close(); store.close()
        storeError(MarketStorageError.CLOSED) { store.readReceipt(storageEvent().ingestPosition) }
    }
    @Test fun `unsupported live version and disabled FK connection fail closed`() {
        val sql = JdbcMarketConnection(file())
        MarketDatasetStore.open(sql, storageDataset).use { store ->
            sql.execute("PRAGMA user_version=2")
            storeError(MarketStorageError.UNSUPPORTED_SCHEMA) { store.readReceipt(storageEvent().ingestPosition) }
            sql.execute("PRAGMA user_version=1"); sql.execute("PRAGMA foreign_keys=OFF")
            storeError(MarketStorageError.INCOMPATIBLE_SCHEMA) { store.append(MarketAppendBatch()) }
            assertFalse(store.verifyIntegrity().valid)
        }
    }
}
