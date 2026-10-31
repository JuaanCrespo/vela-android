package com.vela.android.lab.core.marketdataset.runtime

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vela.android.lab.BuildConfig
import com.vela.android.lab.core.marketdataset.domain.*
import com.vela.android.lab.core.marketdataset.storage.*
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Thirteen PREPARED Android-only cases. Never use the operational or normal Market DB. */
@RunWith(AndroidJUnit4::class)
class MarketDatasetAndroidRuntimeIntegrityTest {
    @get:Rule val testName = TestName()
    private var sandbox: MarketDatasetR1Sandbox? = null
    private val stores = mutableListOf<MarketDatasetStore>()
    private var previousThreadPolicy: StrictMode.ThreadPolicy? = null
    private lateinit var dataset: DatasetId
    private lateinit var session: IngestSessionId
    private val source = MarketSourceIdentity(MarketProviderId("R1_SYNTHETIC"), MarketFeedId.TEST, MarketSourceClass.SYNTHETIC)
    private val instrument = MarketInstrumentIdentity(DatasetAssetClass.SYNTHETIC, source.provider, source.feed,
        CanonicalMarketSymbol("R1_SYNTHETIC_ONLY"), ProviderMarketSymbol("R1_SYNTHETIC_ONLY"))

    @Before fun prepareExclusiveStorageWithoutOpeningSQLite() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals(MarketDatasetR1Runner::class.java, instrumentation.javaClass)
        assertEquals(MarketDatasetR1Runner.SQLITE_CLASS, InstrumentationRegistry.getArguments().getString("class"))
        assertEquals(MarketDatasetR1Runner.SQLITE_AUTHORIZATION_VALUE,
            InstrumentationRegistry.getArguments().getString(MarketDatasetR1Runner.AUTHORIZATION_ARGUMENT))
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        assertFalse(BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED)
        assertEquals("", BuildConfig.ALPACA_TEST_KEY_ID)
        assertEquals("", BuildConfig.ALPACA_TEST_SECRET)
        previousThreadPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build())
        val owned = MarketDatasetR1Sandbox(instrumentation.targetContext)
        sandbox = owned
        // The nonce namespaces synthetic fixtures/storage only; authoritative times/prices are fixed.
        dataset = DatasetId("R1_SYNTHETIC_ONLY-${owned.owner}")
        session = IngestSessionId("R1-${testName.methodName}-${owned.owner}")
        assertSame(owned.context, owned.context.applicationContext)
        assertEquals(owned.database, owned.context.applicationContext.getDatabasePath(AndroidMarketDatasetDatabase.DATABASE_NAME))
        assertFalse(owned.database.exists())
        try { owned.context.applicationContext.getDatabasePath("vela-lab.db"); fail("Unexpected database name accepted") }
        catch (_: IllegalArgumentException) { /* No fallback to the base context. */ }
    }

    @After fun closeConnectionsThenDeleteOnlyOwnedFiles() {
        try {
            // A close failure deliberately prevents deletion of possibly open evidence.
            stores.asReversed().forEach { it.close() }
            sandbox?.close()
        } finally {
            previousThreadPolicy?.let(StrictMode::setThreadPolicy)
        }
    }

    private fun owned() = checkNotNull(sandbox).also { it.verifyOwnership() }
    private fun open(): MarketDatasetStore = AndroidMarketDatasetDatabase.open(owned().context, dataset).also(stores::add)

    /** Inspect this factory-owned handle, not a fresh connection with unrelated PRAGMA settings. */
    private fun database(store: MarketDatasetStore): SQLiteDatabase {
        val sqlField = MarketDatasetStore::class.java.getDeclaredField("sql").apply { isAccessible = true }
        val connection = sqlField.get(store)
        val dbField = connection.javaClass.getDeclaredField("db").apply { isAccessible = true }
        val db = dbField.get(connection) as SQLiteDatabase
        assertTrue(db.isOpen)
        assertEquals(owned().database.canonicalFile, File(db.path).canonicalFile)
        return db
    }

    private fun rows(db: SQLiteDatabase, sql: String, vararg arguments: String): List<Map<String, String?>> =
        db.rawQuery(sql, arguments).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.withIndex().associate { (index, name) ->
                    name to if (cursor.isNull(index)) null else cursor.getString(index)
                })
            }
        }
    private fun scalar(db: SQLiteDatabase, sql: String): String? = rows(db, sql).single().values.single()
    private fun count(db: SQLiteDatabase, table: String): Long = scalar(db, "SELECT COUNT(*) FROM $table")!!.toLong()
    private fun registration() = IngestSessionRegistration(session, source)
    private fun time(second: Long, nano: Int = 0) = MarketTimestamp(second, nano)
    private fun event(sequence: Long = 0, bid: String = "0.100000000000000001") = RawMarketEventEnvelope(
        instrument, source, time(36_000), time(36_060), time(36_060), IngestPosition(session, IngestSequence(sequence)),
        ParserVersion(1), DatasetSchemaVersion(1), RawMarketPayload.Quote(RawMarketDecimal(bid), RawMarketDecimal("0.100000000000000002")),
    )
    private fun bar(raw: RawMarketEventEnvelope = event()) = CanonicalMarketBar(
        LogicalBarIdentity(instrument, source, BarInterval.ONE_MINUTE, time(36_000)), time(36_060), raw.receivedAt, raw.availableAt,
        ExactMarketDecimal.parse("0.100000000000000001"), ExactMarketDecimal.parse("0.100000000000000002"),
        ExactMarketDecimal.parse("0.100000000000000001"), ExactMarketDecimal.parse("0.100000000000000002"),
        ExactMarketDecimal.ZERO, 0L, null, BarFinality.FINAL, BarRevision(0, null), DatasetSchemaVersion(1), CanonicalizationVersion(1),
        BarEvidence(listOf(raw.reference()), listOf(BarQualityFlag.UNKNOWN)),
    )
    private data class History(val raw0: RawMarketEventEnvelope, val raw1: RawMarketEventEnvelope,
        val bar0: CanonicalMarketBar, val bar1: CanonicalMarketBar)
    private fun history(): History {
        val raw0 = event()
        val raw1 = event(1).copy(receivedAt = time(36_300), availableAt = time(36_300))
        val bar0 = bar(raw0)
        val bar1 = bar0.copy(receivedAt = raw1.receivedAt, availableAt = raw1.availableAt, finality = BarFinality.REVISED,
            revision = BarRevision(1, bar0.contentFingerprint), evidence = BarEvidence(listOf(raw1.reference()), listOf(BarQualityFlag.LATE)))
        return History(raw0, raw1, bar0, bar1)
    }
    private fun appendHistory(store: MarketDatasetStore, h: History) =
        store.append(MarketAppendBatch(listOf(registration()), listOf(h.raw0, h.raw1), listOf(h.bar0, h.bar1)))

    private fun storageError(expected: MarketStorageError, action: () -> Unit) {
        try { action(); fail("Expected storage rejection: $expected") }
        catch (failure: MarketStorageException) { assertEquals(expected, failure.code) }
    }

    /** Actual runtime observations only. Standard runner output supplies each individual JUnit result. */
    private fun evidence(db: SQLiteDatabase, label: String, reopened: Boolean = false, factoryConnection: Boolean = true) {
        val tables = rows(db, "SELECT name FROM sqlite_master WHERE type='table'").map { it.getValue("name") }.toSet()
        val fields = linkedMapOf<String, String?>(
            "case" to testName.methodName, "label" to label, "connection" to if (factoryConnection) "production-factory-owned" else "test-only-incompatible-schema",
            "sqlite_version" to scalar(db, "SELECT sqlite_version()"), "user_version" to scalar(db, "PRAGMA user_version"),
            "journal_mode" to scalar(db, "PRAGMA journal_mode"), "synchronous" to scalar(db, "PRAGMA synchronous"),
            "foreign_keys" to scalar(db, "PRAGMA foreign_keys"), "recursive_triggers" to scalar(db, "PRAGMA recursive_triggers"),
            "raw_count" to if ("raw_receipts" in tables) count(db, "raw_receipts").toString() else "N/A",
            "revision_count" to if ("canonical_revisions" in tables) count(db, "canonical_revisions").toString() else "N/A",
            "high_water" to if ("session_high_water" in tables) rows(db, "SELECT session_id,sequence FROM session_high_water").toString() else "N/A",
            "integrity_check" to rows(db, "PRAGMA integrity_check").toString(),
            "foreign_key_check_rows" to rows(db, "PRAGMA foreign_key_check").size.toString(),
            "reopened_verified" to reopened.toString(), "test_owned_path" to db.path,
        )
        val report = fields.entries.joinToString("\n") { "${it.key}=${it.value}" }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "\nR1_EVIDENCE_BEGIN\n$report\nR1_EVIDENCE_END\n")
            putString("r1.case", testName.methodName)
        })
    }

    @Test fun a_creation_hasV1MetadataAndExclusiveDataset() {
        assertFalse(owned().database.exists())
        val store = open(); val db = database(store)
        assertEquals("1", scalar(db, "PRAGMA user_version"))
        assertEquals(7, rows(db, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*'").size)
        assertEquals(7, rows(db, "SELECT name FROM sqlite_master WHERE type='index' AND name NOT GLOB 'sqlite_*'").size)
        assertEquals(16, rows(db, "SELECT name FROM sqlite_master WHERE type='trigger'").size)
        assertEquals(mapOf("singleton" to "1", "format" to "VELA_MARKET_DATASET_V1", "db_version" to "1", "dataset_id" to dataset.value,
            "schema_fingerprint" to "a539bc2a020922419c27ae782d8c4bd43f02af4c642b1930dd8c59bc0bea6e47"), rows(db, "SELECT * FROM market_metadata").single())
        assertTrue(store.verifyIntegrity().valid)
        evidence(db, "creation")
    }

    @Test fun b_configuration_isObservedOnTheActualFactoryConnection() {
        val owner = open(); val db = database(owner)
        assertEquals("delete", scalar(db, "PRAGMA journal_mode"))
        assertEquals("2", scalar(db, "PRAGMA synchronous"))
        assertEquals("1", scalar(db, "PRAGMA foreign_keys"))
        assertEquals("1", scalar(db, "PRAGMA recursive_triggers"))
        // PLATFORM_MANAGED: observe the platform value without promising a VELA duration.
        val observedTimeout = scalar(db, "PRAGMA busy_timeout")
        val first = event(); val originalBar = bar(first); val next = event(1)
        owner.append(MarketAppendBatch(listOf(registration()), listOf(first), listOf(originalBar)))
        val rawBefore = rows(db, "SELECT * FROM raw_receipts")
        val revisionsBefore = rows(db, "SELECT * FROM canonical_revisions")
        val contender = open(); val contenderDb = database(contender)
        assertNotSame(db, contenderDb)
        assertEquals(File(db.path).canonicalFile, File(contenderDb.path).canonicalFile)
        db.beginTransactionNonExclusive() // Hold the writer lock on a distinct factory handle.
        try {
            storageError(MarketStorageError.SQL_BUSY) {
                contender.append(MarketAppendBatch(receipts = listOf(next)))
            }
        } finally { db.endTransaction() }
        // Failed append acknowledged no partial result and scheduled no deferred write.
        assertEquals(rawBefore, rows(contenderDb, "SELECT * FROM raw_receipts"))
        assertEquals(revisionsBefore, rows(contenderDb, "SELECT * FROM canonical_revisions"))
        assertEquals(IngestSequence(0), contender.readHighWater(session))
        assertNull(contender.readReceipt(next.ingestPosition))
        assertEquals(first, contender.readReceipt(first.ingestPosition))
        assertEquals(originalBar, contender.readRevision(originalBar.revisionIdentity))
        assertTrue(contender.verifyIntegrity().valid)
        // Only this explicit post-release call may advance the ledger; no adapter retry loop.
        assertEquals(MarketAppendResult(1, 0, 0, 0), contender.append(MarketAppendBatch(receipts = listOf(next))))
        assertEquals(IngestSequence(1), owner.readHighWater(session))
        assertEquals(2L, count(db, "raw_receipts")); assertEquals(1L, count(db, "canonical_revisions"))
        assertEquals(first, owner.readReceipt(first.ingestPosition))
        assertEquals(originalBar, owner.readRevision(originalBar.revisionIdentity))
        assertEquals(next, owner.readReceipt(next.ingestPosition))
        assertTrue(owner.verifyIntegrity().valid)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "\nR1_ANDROID_TIMEOUT_POLICY\npolicy=PLATFORM_MANAGED\nbusy_timeout_observed=$observedTimeout\n")
        })
        evidence(db, "connection-configuration-and-contention")
    }

    @Test fun c_exactDecimalsAndOriginalRawLexemesRoundTrip() {
        val first = event()
        val second = event(1).copy(payload = RawMarketPayload.Quote(RawMarketDecimal("1.0"), RawMarketDecimal("2.00")))
        val third = event(2).copy(payload = RawMarketPayload.Quote(RawMarketDecimal("1.00"), RawMarketDecimal("2.00")))
        val canonical = bar(first)
        val store = open()
        store.append(MarketAppendBatch(listOf(registration()), listOf(first, second, third), listOf(canonical)))
        assertEquals(first, store.readReceipt(first.ingestPosition)); assertEquals(second, store.readReceipt(second.ingestPosition))
        assertEquals(third, store.readReceipt(third.ingestPosition))
        val read = checkNotNull(store.readRevision(canonical.revisionIdentity))
        assertNotEquals(read.open, read.close)
        assertEquals("0.100000000000000001", read.open.toString()); assertEquals("0.100000000000000002", read.close.toString())
        val db = database(store)
        assertEquals(listOf("0.100000000000000001", "1.0", "1.00"), rows(db, "SELECT bid FROM raw_receipts ORDER BY sequence").map { it["bid"] })
        assertEquals("text", scalar(db, "SELECT typeof(open_value) FROM canonical_revisions"))
        assertEquals("text", scalar(db, "SELECT typeof(bid) FROM raw_receipts WHERE sequence=0"))
        evidence(db, "exact-decimals")
    }

    @Test fun d_adjacentNanosecondsSurviveANewAndroidConnection() {
        val first = event().copy(eventTime = time(36_000, 1), receivedAt = time(36_060, 1), availableAt = time(36_060, 3))
        val second = event(1).copy(eventTime = time(36_000, 2), receivedAt = time(36_060, 2), availableAt = time(36_060, 4))
        val original = open()
        original.append(MarketAppendBatch(listOf(registration()), listOf(first, second)))
        val oldDb = database(original); original.close(); assertFalse(oldDb.isOpen)
        val reopened = open(); val newDb = database(reopened)
        assertNotSame(oldDb, newDb)
        assertEquals(first, reopened.readReceipt(first.ingestPosition)); assertEquals(second, reopened.readReceipt(second.ingestPosition))
        assertEquals(1, checkNotNull(reopened.readReceipt(first.ingestPosition)).eventTime.nanoAdjustment)
        assertEquals(2, checkNotNull(reopened.readReceipt(second.ingestPosition)).eventTime.nanoAdjustment)
        assertEquals(listOf("3", "4"), rows(newDb, "SELECT available_nano FROM raw_receipts ORDER BY sequence").map { it["available_nano"] })
        evidence(newDb, "nanoseconds", reopened = true)
    }

    @Test fun e_explicitSessionsContiguousSequenceAndDurableHighWater() {
        val store = open()
        storageError(MarketStorageError.SESSION_CONFLICT) { store.append(MarketAppendBatch(receipts = listOf(event()))) }
        store.append(MarketAppendBatch(sessions = listOf(registration())))
        assertNull(store.readHighWater(session))
        storageError(MarketStorageError.SEQUENCE_GAP) { store.append(MarketAppendBatch(receipts = listOf(event(1)))) }
        store.append(MarketAppendBatch(receipts = listOf(event())))
        storageError(MarketStorageError.SEQUENCE_GAP) { store.append(MarketAppendBatch(receipts = listOf(event(2)))) }
        store.append(MarketAppendBatch(receipts = listOf(event(1))))
        assertEquals(IngestSequence(1), store.readHighWater(session))
        store.close()
        val reopened = open()
        assertEquals(IngestSequence(1), reopened.readHighWater(session)); assertEquals(2L, count(database(reopened), "raw_receipts"))
        evidence(database(reopened), "durable-high-water", reopened = true)
    }

    @Test fun f_idempotentRetryAndChangedContentConflict() {
        val store = open()
        val batch = MarketAppendBatch(listOf(registration()), listOf(event()), listOf(bar()))
        assertEquals(MarketAppendResult(1, 0, 1, 0), store.append(batch))
        assertEquals(MarketAppendResult(0, 1, 0, 1), store.append(batch))
        storageError(MarketStorageError.RECEIPT_CONFLICT) {
            store.append(MarketAppendBatch(receipts = listOf(event(bid = "0.100000000000000000"))))
        }
        assertEquals(1L, count(database(store), "raw_receipts")); assertEquals(1L, count(database(store), "canonical_revisions"))
        assertEquals(IngestSequence(0), store.readHighWater(session)); assertEquals(event(), store.readReceipt(event().ingestPosition))
        evidence(database(store), "retry-and-conflict")
    }

    @Test fun g_directHistoricalUpdateAndDeleteAreRejectedByAndroidTriggers() {
        val store = open()
        store.append(MarketAppendBatch(listOf(registration()), listOf(event()), listOf(bar())))
        val db = database(store)
        for (table in listOf("raw_receipts", "canonical_revisions")) {
            for (sql in listOf("UPDATE $table SET content_fp=content_fp", "DELETE FROM $table")) {
                try { db.execSQL(sql); fail("Historical SQL unexpectedly succeeded") }
                catch (failure: SQLiteConstraintException) { assertTrue(failure.message.orEmpty().contains("APPEND_ONLY")) }
                assertEquals(1L, count(db, table))
                assertEquals(event(), store.readReceipt(event().ingestPosition)); assertEquals(bar(), store.readRevision(bar().revisionIdentity))
                assertTrue(store.verifyIntegrity().valid)
            }
        }
        evidence(db, "append-only-triggers")
    }

    @Test fun h_failureAfterSuccessfulSqlRollsBackTheWholeBatch() {
        val store = open(); val db = database(store)
        val changesBefore = scalar(db, "SELECT total_changes()")!!.toLong()
        val initial = bar()
        val missingPredecessor = initial.copy(revision = BarRevision(2, initial.contentFingerprint))
        storageError(MarketStorageError.REVISION_PARENT) {
            store.append(MarketAppendBatch(listOf(registration()), listOf(event()), listOf(initial, missingPredecessor)))
        }
        // SQLite total_changes includes successful statements whose transaction was rolled back.
        assertTrue(scalar(db, "SELECT total_changes()")!!.toLong() > changesBefore)
        for (table in listOf("ingest_sessions", "session_high_water", "raw_receipts", "canonical_revisions", "bar_lineage", "bar_quality_flags")) {
            assertEquals("Partial batch effects in $table", 0L, count(db, table))
        }
        assertNull(store.readHighWater(session)); assertTrue(store.verifyIntegrity().valid)
        evidence(db, "mid-batch-rollback")
    }

    @Test fun i_revisionParentsAndCommittedRawLineageRemainExact() {
        val h = history(); val store = open()
        appendHistory(store, h)
        val zero = checkNotNull(store.readRevision(h.bar0.revisionIdentity))
        val one = checkNotNull(store.readRevision(h.bar1.revisionIdentity))
        assertEquals(h.bar0, zero); assertEquals(h.bar1, one)
        assertEquals(zero.contentFingerprint, one.revision.previousRevisionFingerprint)
        assertEquals(h.raw0.reference(), zero.evidence.references.single()); assertEquals(h.raw1.reference(), one.evidence.references.single())
        storageError(MarketStorageError.REVISION_PARENT) {
            store.append(MarketAppendBatch(revisions = listOf(one.copy(revision = BarRevision(2, zero.contentFingerprint)))))
        }
        assertEquals(2L, count(database(store), "canonical_revisions")); assertEquals(2L, count(database(store), "bar_lineage"))
        assertTrue(rows(database(store), "PRAGMA foreign_key_check").isEmpty())
        evidence(database(store), "revision-parent-and-lineage")
    }

    @Test fun j_reopened1001OriginalIsVisibleBeforeThe1005Correction() {
        val h = history(); val store = open()
        appendHistory(store, h); store.close()
        val reopened = open()
        val zero = checkNotNull(reopened.readRevision(h.bar0.revisionIdentity))
        val one = checkNotNull(reopened.readRevision(h.bar1.revisionIdentity))
        assertEquals(time(36_060), zero.availableAt); assertEquals(time(36_300), one.availableAt)
        val cutoff = time(36_180)
        assertTrue(MarketAvailability.isVisible(zero.availableAt, cutoff))
        assertFalse(MarketAvailability.isVisible(one.availableAt, cutoff))
        // Non-vacuous causal visibility above; synthetic evidence is separately never a snapshot candidate.
        val spec = DatasetSnapshotSpec(dataset, DatasetSchemaVersion(1), ParserVersion(1), CanonicalizationVersion(1), QualityPolicyVersion(1),
            DatasetSnapshotScope(listOf(source), listOf(instrument), listOf(h.raw1.ingestPosition)), MarketTimeRange(time(36_000), time(36_360)), cutoff)
        assertFalse(DatasetSourcePolicy.isCandidate(instrument, source))
        assertFalse(spec.includes(zero)); assertFalse(spec.includes(one))
        evidence(database(reopened), "no-lookahead-synthetic-not-eligible", reopened = true)
    }

    @Test fun k_closeAndNewFactoryInstanceReconstructAllDurableEvidence() {
        val h = history(); val original = open()
        appendHistory(original, h)
        val oldDb = database(original)
        original.close(); assertFalse(oldDb.isOpen)
        val reopened = open(); val newDb = database(reopened)
        assertNotSame(original, reopened); assertNotSame(oldDb, newDb)
        assertEquals(owned().database.canonicalPath, File(newDb.path).canonicalPath)
        for (raw in listOf(h.raw0, h.raw1)) {
            val read = checkNotNull(reopened.readReceipt(raw.ingestPosition))
            assertEquals(raw, read); assertEquals(raw.contentFingerprint, read.contentFingerprint)
            assertEquals(raw.receiptIdentity, read.receiptIdentity); assertEquals(raw.eventIdentity, read.eventIdentity)
        }
        for (expected in listOf(h.bar0, h.bar1)) {
            val read = checkNotNull(reopened.readRevision(expected.revisionIdentity))
            assertEquals(expected, read); assertEquals(expected.contentFingerprint, read.contentFingerprint)
        }
        assertEquals(IngestSequence(1), reopened.readHighWater(session)); assertTrue(reopened.verifyIntegrity().valid)
        evidence(newDb, "complete-close-reopen", reopened = true)
    }

    @Test fun l_engineForeignKeysAndFullRepositoryIntegrityAgree() {
        val store = open(); appendHistory(store, history())
        val db = database(store)
        assertEquals(listOf("ok"), rows(db, "PRAGMA integrity_check").map { it.values.single() })
        assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
        assertTrue(store.verifyIntegrity().valid)
        evidence(db, "engine-and-repository-integrity")
    }

    @Test fun m_unsupportedOwnedSchemaIsNotRecreatedOrMigrated() {
        val file = owned().database
        val params = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            .setJournalMode("DELETE").setSynchronousMode("FULL").build()
        SQLiteDatabase.openDatabase(file, params).use { db ->
            db.execSQL("CREATE TABLE r1_marker (value TEXT NOT NULL)")
            db.execSQL("INSERT INTO r1_marker VALUES (?)", arrayOf("R1_SYNTHETIC_PRESERVE"))
            db.execSQL("PRAGMA user_version=2")
        }
        storageError(MarketStorageError.UNSUPPORTED_SCHEMA) { open() }
        SQLiteDatabase.openDatabase(file, params).use { db ->
            assertEquals("2", scalar(db, "PRAGMA user_version"))
            assertEquals("R1_SYNTHETIC_PRESERVE", scalar(db, "SELECT value FROM r1_marker"))
            assertTrue(rows(db, "SELECT name FROM sqlite_master WHERE name='market_metadata'").isEmpty())
            evidence(db, "unsupported-version-preserved", factoryConnection = false)
        }
    }
}
