package com.vela.android.lab.core.marketdataset.storage

import com.vela.android.lab.core.marketdataset.domain.*
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MarketDatasetStoreTest {
    @TempDir lateinit var directory: Path
    private fun opened(block: (MarketDatasetStore, JdbcMarketConnection) -> Unit) {
        val sql = JdbcMarketConnection(directory.resolve("market.sqlite"))
        MarketDatasetStore.open(sql, storageDataset).use { block(it, sql) }
    }
    @Test fun `fresh database has exact production v1 schema indexes FKs and integrity`() = opened { store, sql ->
        assertEquals("1", sql.query("PRAGMA user_version").single().values.single())
        assertEquals("1", sql.query("PRAGMA foreign_keys").single().values.single())
        assertEquals("delete", sql.query("PRAGMA journal_mode").single().values.single())
        assertEquals("2", sql.query("PRAGMA synchronous").single().values.single())
        assertEquals(MarketDatasetSchema.tables.keys, sql.query("SELECT name FROM sqlite_master WHERE type='table'").map { it.text("name") }.toSet())
        assertTrue(sql.query("PRAGMA foreign_key_check").isEmpty())
        assertTrue(store.verifyIntegrity().valid)
        assertNull(store.readReceipt(storageEvent().ingestPosition)); assertNull(store.readHighWater(IngestSessionId("missing")))
    }
    @Test fun `all typed raw payloads round trip including original decimal lexemes and optional fields`() = opened { store, sql ->
        val payloads = listOf(
            RawMarketPayload.Quote(RawMarketDecimal("1.0"), RawMarketDecimal("2.00")),
            RawMarketPayload.Quote(RawMarketDecimal("1.00"), RawMarketDecimal("2.00"), RawMarketDecimal("0"), RawMarketDecimal("3.000")),
            RawMarketPayload.Trade(RawMarketDecimal("0.100000000000000001"), RawMarketDecimal("0.100000000000000002")),
            RawMarketPayload.Bar(RawMarketDecimal("1.0"), RawMarketDecimal("2.00"), RawMarketDecimal("1.00"), RawMarketDecimal("2.0"), null),
            RawMarketPayload.Bar(RawMarketDecimal("1"), RawMarketDecimal("2"), RawMarketDecimal("1"), RawMarketDecimal("2"), RawMarketDecimal("0.00"), Long.MAX_VALUE, RawMarketDecimal("1.5000"), RawMarketEventType.BAR_UPDATE),
        ) + listOf(RawMarketEventType.STATUS, RawMarketEventType.CONTROL, RawMarketEventType.UNKNOWN).map { RawMarketPayload.DigestOnly(it, storageEvent().contentFingerprint) }
        val events = payloads.mapIndexed { index, payload -> storageEvent(index.toLong()).copy(payload = payload, providerEventId = ProviderEventId("native-$index")) }
        assertEquals(events.size, store.append(MarketAppendBatch(listOf(registration()), events)).insertedReceipts)
        events.forEach { event ->
            val read = store.readReceipt(event.ingestPosition)!!
            assertEquals(event, read); assertEquals(event.contentFingerprint, read.contentFingerprint)
            assertEquals(event.eventIdentity, read.eventIdentity); assertEquals(event.receiptIdentity, read.receiptIdentity)
        }
        assertEquals("1.0", (store.readReceipt(events[0].ingestPosition)!!.payload as RawMarketPayload.Quote).bid.text)
        assertEquals("1.00", (store.readReceipt(events[1].ingestPosition)!!.payload as RawMarketPayload.Quote).bid.text)
        assertEquals("text", sql.query("SELECT typeof(trade_price) AS kind FROM raw_receipts WHERE sequence=2").single().text("kind"))
        assertTrue(store.verifyIntegrity().valid)
    }
    @Test fun `canonical sub-double prices nullable values flags and lexical normalization round trip`() = opened { store, sql ->
        val event = storageEvent().copy(payload = RawMarketPayload.Quote(RawMarketDecimal("0.100000000000000001"), RawMarketDecimal("0.100000000000000002")))
        val a = ExactMarketDecimal.parse("0.100000000000000001"); val b = ExactMarketDecimal.parse("0.100000000000000002")
        val bar = storageBar(event).copy(open = a, low = a, high = b, close = b, volume = a, tradeCount = Long.MAX_VALUE, vwap = b)
        store.append(MarketAppendBatch(listOf(registration()), listOf(event), listOf(bar)))
        val read = store.readRevision(bar.revisionIdentity)!!
        assertEquals(bar, read); assertNotEquals(read.open, read.close); assertEquals(bar.contentFingerprint, read.contentFingerprint)
        assertEquals("text", sql.query("SELECT typeof(open_value) AS kind FROM canonical_revisions").single().text("kind"))
        assertEquals("0.100000000000000001", sql.query("SELECT open_value FROM canonical_revisions").single().text("open_value"))
    }
    @ParameterizedTest @ValueSource(longs = [-31557014167219200, -1, 0, 31556889864403199])
    fun `nanosecond and negative distant timestamp components are lossless`(second: Long) = opened { store, _ ->
        val first = storageEvent().copy(eventTime = storageTime(second, 1))
        val secondEvent = storageEvent(1).copy(eventTime = storageTime(second, 2))
        store.append(MarketAppendBatch(listOf(registration()), listOf(first, secondEvent)))
        assertEquals(first.eventTime, store.readReceipt(first.ingestPosition)!!.eventTime)
        assertTrue(store.readReceipt(first.ingestPosition)!!.eventTime < store.readReceipt(secondEvent.ingestPosition)!!.eventTime)
    }
    @Test fun `same SPY on IEX and TEST survives in distinct sources`() = opened { store, sql ->
        val a = storageEvent(); val b = storageEvent(session = "TEST", source = storageSource.copy(feed = MarketFeedId.TEST, sourceClass = MarketSourceClass.SYNTHETIC))
        val aBar = storageBar(a); val bBar = storageBar(b)
        store.append(MarketAppendBatch(listOf(registration(a), registration(b)), listOf(a, b), listOf(aBar, bBar)))
        assertEquals(2, sql.count("raw_receipts")); assertNotEquals(store.readReceipt(a.ingestPosition)!!.contentFingerprint, store.readReceipt(b.ingestPosition)!!.contentFingerprint)
        assertFalse(DatasetSourcePolicy.isCandidate(b.instrument, b.source)); assertTrue(store.verifyIntegrity().valid)
        assertNotEquals(aBar.identity.fingerprint, bBar.identity.fingerprint)
        assertEquals(aBar, store.readRevision(aBar.revisionIdentity)); assertEquals(bBar, store.readRevision(bBar.revisionIdentity))
    }
    @Test fun `Unicode punctuation and SQL metacharacters are bound exactly as evidence`() = opened { store, sql ->
        val event = storageEvent(session = "session-'😀").copy(instrument = storageEvent().instrument.copy(
            canonicalSymbol = CanonicalMarketSymbol("銘柄:😀"), providerSymbol = ProviderMarketSymbol("O'BRK;|")), providerEventId = ProviderEventId("native:'|😀"))
        store.append(MarketAppendBatch(listOf(registration(event)), listOf(event)))
        assertEquals(event, store.readReceipt(event.ingestPosition)); assertEquals(1, sql.count("raw_receipts")); assertTrue(store.verifyIntegrity().valid)
    }
    @Test fun `identical retry is idempotent while distinct receipt positions preserve duplicate evidence`() = opened { store, sql ->
        val a = storageEvent().copy(providerEventId = ProviderEventId("native")); val second = a.copy(ingestPosition = storageEvent(1).ingestPosition)
        val bar = storageBar(a); val batch = MarketAppendBatch(listOf(registration()), listOf(a), listOf(bar))
        assertEquals(MarketAppendResult(1, 0, 1, 0), store.append(batch))
        assertEquals(MarketAppendResult(0, 1, 0, 1), store.append(batch))
        store.append(MarketAppendBatch(receipts = listOf(second)))
        assertEquals(2, sql.count("raw_receipts")); assertEquals(1, sql.count("canonical_revisions"))
        assertEquals(a.contentFingerprint, store.readReceipt(second.ingestPosition)!!.contentFingerprint)
        assertNotEquals(a.receiptIdentity, second.receiptIdentity); assertEquals(IngestSequence(1), store.readHighWater(a.ingestPosition.session))
    }
    @Test fun `reused receipt identity rejects different evidence and different local availability`() = opened { store, sql ->
        val a = storageEvent(); store.append(MarketAppendBatch(listOf(registration()), listOf(a)))
        for (conflict in listOf(storageEvent(bid = "1.1"), a.copy(availableAt = storageTime(61)))) {
            storeError(MarketStorageError.RECEIPT_CONFLICT) { store.append(MarketAppendBatch(receipts = listOf(conflict))) }
        }
        assertEquals(1, sql.count("raw_receipts")); assertEquals(a, store.readReceipt(a.ingestPosition))
    }
    @Test fun `contradictory provider-native identity is explicitly rejected without advancing high water`() = opened { store, sql ->
        val a = storageEvent().copy(providerEventId = ProviderEventId("native")); store.append(MarketAppendBatch(listOf(registration()), listOf(a)))
        val conflict = storageEvent(1, bid = "1.1").copy(providerEventId = a.providerEventId)
        storeError(MarketStorageError.PROVIDER_ID_CONFLICT) { store.append(MarketAppendBatch(receipts = listOf(conflict))) }
        assertEquals(1, sql.count("raw_receipts")); assertEquals(IngestSequence(0), store.readHighWater(a.ingestPosition.session))
    }
    @Test fun `session ledger and contiguous zero-based sequences reject gaps and reuse`() = opened { store, sql ->
        store.append(MarketAppendBatch(sessions = listOf(registration())))
        assertNull(store.readHighWater(IngestSessionId("A")))
        for (sequence in listOf(1L, Long.MAX_VALUE)) storeError(MarketStorageError.SEQUENCE_GAP) { store.append(MarketAppendBatch(receipts = listOf(storageEvent(sequence)))) }
        storeError(MarketStorageError.SESSION_CONFLICT) { store.append(MarketAppendBatch(sessions = listOf(registration().copy(source = storageSource.copy(sourceClass = MarketSourceClass.UNKNOWN))))) }
        storeError(MarketStorageError.SESSION_CONFLICT) { store.append(MarketAppendBatch(receipts = listOf(storageEvent(session = "unknown")))) }
        assertEquals(0, sql.count("raw_receipts")); assertTrue(store.verifyIntegrity().valid)
    }
    @Test fun `revision chain retains history and uses predecessor content fingerprint`() = opened { store, sql ->
        val a = storageEvent(); val initial = storageBar(a)
        val late = storageEvent(1).copy(receivedAt = storageTime(300), availableAt = storageTime(300))
        val revised = initial.copy(receivedAt = late.receivedAt, availableAt = late.availableAt, finality = BarFinality.REVISED,
            revision = BarRevision(1, initial.contentFingerprint), evidence = BarEvidence(listOf(late.reference()), listOf(BarQualityFlag.LATE)))
        store.append(MarketAppendBatch(listOf(registration()), listOf(a), listOf(initial)))
        store.append(MarketAppendBatch(receipts = listOf(late), revisions = listOf(revised)))
        assertEquals(initial, store.readRevision(initial.revisionIdentity)); assertEquals(revised, store.readRevision(revised.revisionIdentity))
        assertEquals(2, sql.count("canonical_revisions")); assertTrue(store.verifyIntegrity().valid)
        storeError(MarketStorageError.REVISION_CONFLICT) { store.append(MarketAppendBatch(revisions = listOf(initial.copy(close = ExactMarketDecimal.parse("1.5"))))) }
        storeError(MarketStorageError.REVISION_PARENT) { store.append(MarketAppendBatch(revisions = listOf(initial.copy(revision = BarRevision(2, initial.contentFingerprint))))) }
        storeError(MarketStorageError.REVISION_PARENT) { store.append(MarketAppendBatch(revisions = listOf(revised.copy(revision = BarRevision(2, revised.revisionIdentity))))) }
        storeError(MarketStorageError.REVISION_PARENT) { store.append(MarketAppendBatch(revisions = listOf(revised.copy(revision = BarRevision(2, revised.contentFingerprint), receivedAt = storageTime(299), availableAt = storageTime(299), evidence = initial.evidence)))) }
    }
    @Test fun `missing parent and nonexistent or contradictory lineage roll back the whole batch`() = opened { store, sql ->
        val a = storageEvent(); val initial = storageBar(a)
        storeError(MarketStorageError.REVISION_PARENT) { store.append(MarketAppendBatch(listOf(registration()), listOf(a), listOf(initial.copy(revision = BarRevision(1, initial.contentFingerprint))))) }
        assertEquals(0, sql.count("raw_receipts")); assertEquals(0, sql.count("ingest_sessions"))
        storeError(MarketStorageError.LINEAGE_CONFLICT) { store.append(MarketAppendBatch(revisions = listOf(initial))) }
        store.append(MarketAppendBatch(listOf(registration()), listOf(a)))
        val wrong = a.reference().copy(parserVersion = ParserVersion(2))
        storeError(MarketStorageError.LINEAGE_CONFLICT) { store.append(MarketAppendBatch(revisions = listOf(initial.copy(evidence = BarEvidence(listOf(wrong), emptyList()))))) }
        assertEquals(0, sql.count("canonical_revisions")); assertEquals(0, sql.count("bar_lineage"))
    }
    @ParameterizedTest @ValueSource(strings = ["raw_receipts", "canonical_revisions", "bar_lineage", "bar_quality_flags"])
    fun `injected mid-batch failure rolls back all evidence metadata lineage and high water`(table: String) = opened { store, sql ->
        val batch = MarketAppendBatch(listOf(registration()), listOf(storageEvent()), listOf(storageBar()))
        sql.failAfterSql = "INSERT INTO $table "
        storeError(MarketStorageError.SQL_FAILURE) { store.append(batch) }
        for (name in listOf("ingest_sessions", "session_high_water", "raw_receipts", "canonical_revisions", "bar_lineage", "bar_quality_flags")) assertEquals(0, sql.count(name), name)
        sql.failAfterSql = null
        assertEquals(MarketAppendResult(1, 0, 1, 0), store.append(batch)); assertTrue(store.verifyIntegrity().valid)
    }
    @Test fun `append-only triggers reject direct destructive SQL without changing evidence`() = opened { store, sql ->
        store.append(MarketAppendBatch(listOf(registration()), listOf(storageEvent()), listOf(storageBar())))
        for (table in MarketDatasetSchema.tables.keys) {
            assertThrows(Exception::class.java) { sql.execute("DELETE FROM $table") }
        }
        assertThrows(Exception::class.java) { sql.execute("UPDATE raw_receipts SET bid='999'") }
        assertThrows(Exception::class.java) { sql.execute("UPDATE canonical_revisions SET close_value='999'") }
        assertThrows(Exception::class.java) { sql.execute("UPDATE session_high_water SET sequence=9223372036854775807") }
        val fields = MarketRowCodec.bar(storageBar().copy(close = ExactMarketDecimal.parse("1.5")))
        assertThrows(Exception::class.java) {
            sql.execute("INSERT OR REPLACE INTO canonical_revisions (${fields.keys.joinToString()}) VALUES (${fields.keys.joinToString { "?" }})", fields.values.toList())
        }
        assertEquals(storageEvent(), store.readReceipt(storageEvent().ingestPosition)); assertTrue(store.verifyIntegrity().valid)
    }
    @Test fun `foreign key orphan insertion and integer overflow affinity are rejected`() = opened { store, sql ->
        assertThrows(Exception::class.java) { sql.insert("bar_lineage", MarketRowCodec.lineage(storageBar().revisionIdentity.hex, storageEvent().reference())) }
        store.append(MarketAppendBatch(sessions = listOf(registration())))
        val fields = MarketRowCodec.raw(storageEvent()).toMutableMap()
        fields["event_sec"] = "9223372036854775808"
        assertThrows(Exception::class.java) { sql.insert("raw_receipts", fields) }
        assertNull(store.readHighWater(IngestSessionId("A"))); assertTrue(sql.query("PRAGMA foreign_key_check").isEmpty())
    }
}
