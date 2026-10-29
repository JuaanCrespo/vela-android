package com.vela.android.lab.core.marketdataset.storage

import com.vela.android.lab.core.marketdataset.domain.*

/** Explicitly opened, owned connection. No collector, singleton, application or execution wiring. */
class MarketDatasetStore private constructor(private val sql: MarketSqlConnection, val datasetId: DatasetId) : AutoCloseable {
    private var closed = false
    companion object {
        internal fun open(sql: MarketSqlConnection, datasetId: DatasetId): MarketDatasetStore {
            val store = MarketDatasetStore(sql, datasetId)
            try {
                store.access {
                    val version = sql.query("PRAGMA user_version").single().values.single()?.toLongOrNull()
                    if (version == 0L) {
                        demand(sql.query("SELECT name FROM sqlite_master WHERE name NOT GLOB 'sqlite_*'").isEmpty(), MarketStorageError.INCOMPATIBLE_SCHEMA)
                        MarketDatasetSchema.statements.forEach { sql.execute(it) }
                        sql.insert("market_metadata", linkedMapOf("singleton" to "1", "format" to MarketDatasetSchema.FORMAT,
                            "db_version" to MarketDatasetSchema.VERSION.toString(), "dataset_id" to datasetId.value, "schema_fingerprint" to MarketDatasetSchema.fingerprint))
                        sql.execute("PRAGMA user_version=1")
                    }
                    store.verifySchema()
                }
                return store
            } catch (failure: Throwable) {
                try { sql.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }
    }
    private fun verifySchema() {
        MarketDatasetSchema.verify(sql)
        val metadata = sql.query("SELECT * FROM market_metadata").singleOrNull()
        demand(metadata != null, MarketStorageError.INCOMPATIBLE_SCHEMA, "METADATA")
        val actual = metadata!!
        demand(actual["format"] == MarketDatasetSchema.FORMAT && actual["db_version"] == "1" && actual["schema_fingerprint"] == MarketDatasetSchema.fingerprint,
            MarketStorageError.INCOMPATIBLE_SCHEMA, "METADATA")
        demand(actual["dataset_id"] == datasetId.value, MarketStorageError.WRONG_DATASET)
    }
    private fun <T> access(block: () -> T): T = synchronized(sql) {
        demand(!closed, MarketStorageError.CLOSED)
        try { sql.transaction(block) }
        catch (failure: MarketStorageException) { throw failure }
        catch (_: IllegalArgumentException) { throw MarketStorageException(MarketStorageError.DATA_INTEGRITY_ERROR, "DOMAIN_REHYDRATION") }
        catch (_: Exception) { throw MarketStorageException(MarketStorageError.SQL_FAILURE) }
    }
    fun append(batch: MarketAppendBatch): MarketAppendResult {
        val sessions = batch.sessions.toList(); val receipts = batch.receipts.toList(); val revisions = batch.revisions.toList()
        return access {
            verifySchema()
            sessions.forEach(::register)
            val requiredSessions = (sessions.map { it.session } + receipts.map { it.ingestPosition.session }).toSet()
            val affectedSessions = requiredSessions + revisions.flatMap { it.evidence.references.map { reference -> reference.ingestPosition.session } }
            // Validate existing causal prefixes once per affected session, before retry/new-write handling.
            affectedSessions.forEach { checkedHighWater(it, it in requiredSessions) }
            var insertedRaw = 0; var retryRaw = 0; var insertedBars = 0; var retryBars = 0
            receipts.forEach { if (appendReceipt(it)) insertedRaw++ else retryRaw++ }
            revisions.forEach { if (appendRevision(it)) insertedBars++ else retryBars++ }
            MarketAppendResult(insertedRaw, retryRaw, insertedBars, retryBars)
        }
    }
    private fun register(registration: IngestSessionRegistration) {
        val existing = sql.query("SELECT * FROM ingest_sessions WHERE session_id=?", listOf(registration.session.value)).singleOrNull()
        if (existing != null) {
            demand(registered(existing) == registration, MarketStorageError.SESSION_CONFLICT)
            return
        }
        sql.insert("ingest_sessions", linkedMapOf("session_id" to registration.session.value, "provider" to registration.source.provider.code,
            "feed" to registration.source.feed.code, "source_class" to registration.source.sourceClass.name))
        sql.insert("session_high_water", linkedMapOf("session_id" to registration.session.value, "sequence" to null))
    }
    private fun registered(row: Map<String, String?>) =
        IngestSessionRegistration(IngestSessionId(row.text("session_id")), MarketRowCodec.source(row))
    private fun raw(position: IngestPosition): RawMarketEventEnvelope? = sql.query("SELECT * FROM raw_receipts WHERE session_id=? AND sequence=?",
        listOf(position.session.value, position.sequence.value.toString())).singleOrNull()?.let(MarketRowCodec::raw)
    fun readReceipt(position: IngestPosition): RawMarketEventEnvelope? = access { verifySchema(); raw(position) }
    fun readHighWater(session: IngestSessionId): IngestSequence? = access {
        verifySchema()
        checkedHighWater(session)
    }
    private fun checkedHighWater(session: IngestSessionId, required: Boolean = false): IngestSequence? {
        val ledger = sql.query("SELECT * FROM ingest_sessions WHERE session_id=?", listOf(session.value)).singleOrNull()
        if (required) demand(ledger != null, MarketStorageError.SESSION_CONFLICT)
        if (ledger != null) registered(ledger)
        val record = sql.query("SELECT sequence FROM session_high_water WHERE session_id=?", listOf(session.value)).singleOrNull()
        val water = record?.get("sequence")?.let { IngestSequence(record.number("sequence")) }
        val counts = sql.query("SELECT COUNT(*) AS amount,MIN(sequence) AS first,MAX(sequence) AS last FROM raw_receipts WHERE session_id=?", listOf(session.value)).single()
        val count = counts.number("amount")
        val consistent = when {
            ledger == null -> record == null && count == 0L
            record == null -> false
            count == 0L -> water == null
            else -> water != null && counts["first"] == "0" && counts["last"] == water.value.toString() && water.value == count - 1
        }
        demand(consistent, MarketStorageError.DATA_INTEGRITY_ERROR, "HIGH_WATER")
        return water
    }
    private fun highWater(session: IngestSessionId): IngestSequence? {
        val row = sql.query("SELECT sequence FROM session_high_water WHERE session_id=?", listOf(session.value)).singleOrNull() ?: return null
        return row["sequence"]?.let { IngestSequence(row.number("sequence")) }
    }
    private fun appendReceipt(event: RawMarketEventEnvelope): Boolean {
        val existing = raw(event.ingestPosition)
        if (existing != null) {
            demand(existing == event, MarketStorageError.RECEIPT_CONFLICT)
            return false
        }
        val session = sql.query("SELECT * FROM ingest_sessions WHERE session_id=?", listOf(event.ingestPosition.session.value)).singleOrNull()
        demand(session != null && registered(session) == IngestSessionRegistration(event.ingestPosition.session, event.source), MarketStorageError.SESSION_CONFLICT)
        val previous = highWater(event.ingestPosition.session)?.value
        demand(if (previous == null) event.ingestPosition.sequence.value == 0L else previous < Long.MAX_VALUE && event.ingestPosition.sequence.value == previous + 1,
            MarketStorageError.SEQUENCE_GAP)
        if (event.providerEventId != null) {
            val contradictory = sql.query("SELECT content_fp FROM raw_receipts WHERE event_identity_kind='PROVIDER_ID' AND event_identity_fp=? AND content_fp<>? LIMIT 1",
                listOf(event.eventIdentity.fingerprint.hex, event.contentFingerprint.hex))
            demand(contradictory.isEmpty(), MarketStorageError.PROVIDER_ID_CONFLICT)
        }
        sql.insert("raw_receipts", MarketRowCodec.raw(event)) // SQL trigger advances high-water in this same transaction.
        return true
    }
    private fun decodeBar(row: Map<String, String?>): CanonicalMarketBar {
        val id = row.text("revision_id")
        val references = sql.query("SELECT * FROM bar_lineage WHERE revision_id=? ORDER BY session_id,sequence", listOf(id)).map { reference ->
            val position = IngestPosition(IngestSessionId(reference.text("session_id")), IngestSequence(reference.number("sequence")))
            val evidence = raw(position)?.reference() ?: throw MarketStorageException(MarketStorageError.DATA_INTEGRITY_ERROR, "ORPHAN_LINEAGE")
            demand(reference == MarketRowCodec.lineage(id, evidence), MarketStorageError.DATA_INTEGRITY_ERROR, "LINEAGE_CONTENT")
            evidence
        }
        val flags = sql.query("SELECT flag FROM bar_quality_flags WHERE revision_id=? ORDER BY flag", listOf(id)).map { BarQualityFlag.valueOf(it.text("flag")) }
        return MarketRowCodec.bar(row, BarEvidence(references, flags))
    }
    private fun parent(bar: CanonicalMarketBar) {
        if (bar.revision.number == 0L) return
        val row = sql.query("SELECT * FROM canonical_revisions WHERE logical_id=? AND dataset_schema_version=? AND canonical_version=? AND revision_number=?",
            listOf(bar.identity.fingerprint.hex, bar.schemaVersion.value.toString(), bar.canonicalizationVersion.value.toString(), (bar.revision.number - 1).toString())).singleOrNull()
        demand(row != null, MarketStorageError.REVISION_PARENT, "MISSING_PREDECESSOR")
        val preceding = decodeBar(row!!)
        demand(preceding.contentFingerprint == bar.revision.previousRevisionFingerprint, MarketStorageError.REVISION_PARENT, "PREDECESSOR_CONTENT_FINGERPRINT")
        demand(preceding.availableAt <= bar.availableAt && preceding.receivedAt <= bar.receivedAt, MarketStorageError.REVISION_PARENT, "RETROACTIVE_AVAILABILITY")
    }
    fun readRevision(identity: MarketFingerprint): CanonicalMarketBar? = access {
        verifySchema()
        sql.query("SELECT * FROM canonical_revisions WHERE revision_id=?", listOf(identity.hex)).singleOrNull()?.let { decodeBar(it).also(::parent) }
    }
    private fun appendRevision(bar: CanonicalMarketBar): Boolean {
        val existing = sql.query("SELECT * FROM canonical_revisions WHERE revision_id=?", listOf(bar.revisionIdentity.hex)).singleOrNull()
        if (existing != null) {
            demand(decodeBar(existing) == bar, MarketStorageError.REVISION_CONFLICT)
            parent(bar)
            return false
        }
        parent(bar)
        for (reference in bar.evidence.references) {
            demand(raw(reference.ingestPosition)?.reference() == reference, MarketStorageError.LINEAGE_CONFLICT)
        }
        sql.insert("canonical_revisions", MarketRowCodec.bar(bar))
        bar.evidence.references.forEach { sql.insert("bar_lineage", MarketRowCodec.lineage(bar.revisionIdentity.hex, it)) }
        bar.evidence.qualityFlags.forEach { sql.insert("bar_quality_flags", linkedMapOf("revision_id" to bar.revisionIdentity.hex, "flag" to it.name)) }
        return true
    }
    /** Consistent transaction; SQL diagnostics/SELECTs only, with no automatic repair or metadata writes. */
    fun verifyIntegrity(): MarketIntegrityReport = try {
        access {
            val issues = mutableListOf<MarketIntegrityIssue>()
            fun issue(reason: String) { issues += MarketIntegrityIssue(reason) }
            try { verifySchema() } catch (failure: MarketStorageException) { issue("${failure.code}:${failure.diagnostic}") }
            if (sql.query("PRAGMA integrity_check").any { it.values.firstOrNull() != "ok" }) issue("SQLITE_INTEGRITY")
            if (sql.query("PRAGMA foreign_key_check").isNotEmpty()) issue("FOREIGN_KEY")
            for (session in sql.query("SELECT * FROM ingest_sessions ORDER BY session_id")) {
                try { registered(session) } catch (_: Exception) { issue("SESSION_IDENTITY") }
                val id = session.text("session_id")
                val water = sql.query("SELECT sequence FROM session_high_water WHERE session_id=?", listOf(id)).singleOrNull()
                val counts = sql.query("SELECT COUNT(*) AS amount,MIN(sequence) AS first,MAX(sequence) AS last FROM raw_receipts WHERE session_id=?", listOf(id)).single()
                val count = counts.number("amount")
                if (water == null || if (count == 0L) water["sequence"] != null else water["sequence"] != counts["last"] || counts["first"] != "0" || water["sequence"]?.toLongOrNull() != count - 1) issue("HIGH_WATER")
            }
            for (row in sql.query("SELECT * FROM raw_receipts ORDER BY session_id,sequence")) {
                try { MarketRowCodec.raw(row) } catch (_: Exception) { issue("RAW_CONTENT_OR_IDENTITY") }
            }
            if (sql.query("SELECT event_identity_fp FROM raw_receipts WHERE event_identity_kind='PROVIDER_ID' GROUP BY event_identity_fp HAVING COUNT(DISTINCT content_fp)>1").isNotEmpty()) issue("PROVIDER_ID_CONFLICT")
            for (row in sql.query("SELECT * FROM canonical_revisions ORDER BY logical_id,dataset_schema_version,canonical_version,revision_number")) {
                try { parent(decodeBar(row)) } catch (failure: MarketStorageException) { issue("${failure.code}:${failure.diagnostic}") }
                catch (_: Exception) { issue("BAR_DOMAIN_REHYDRATION") }
            }
            MarketIntegrityReport(issues)
        }
    } catch (failure: MarketStorageException) { MarketIntegrityReport(listOf(MarketIntegrityIssue("${failure.code}:${failure.diagnostic}"))) }

    override fun close() = synchronized(sql) { if (!closed) { closed = true; sql.close() } }
}
