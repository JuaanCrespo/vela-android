package com.vela.android.lab.core.marketdataset.storage

import java.security.MessageDigest

/** This exact DDL is executed by Android and the host integration backend. No Room schema is involved. */
internal object MarketDatasetSchema {
    const val DATABASE_NAME = "vela_market_dataset.db"
    const val VERSION = 1
    const val FORMAT = "VELA_MARKET_DATASET_V1"
    private fun integer(name: String, nullable: Boolean = false, minimum: Long? = null, maximum: Long? = null): String {
        val bounds = listOfNotNull(minimum?.let { "$name >= $it" }, maximum?.let { "$name <= $it" })
        val check = (listOf("typeof($name) = 'integer'") + bounds).joinToString(" AND ")
        return "$name INTEGER ${if (nullable) "" else "NOT NULL"} CHECK (${if (nullable) "$name IS NULL OR ($check)" else check})"
    }
    private fun time(prefix: String) = "${integer("${prefix}_sec", minimum = -31557014167219200L, maximum = 31556889864403199L)}, ${integer("${prefix}_nano", minimum = 0, maximum = 999999999)}"
    private val identity = "provider TEXT NOT NULL, feed TEXT NOT NULL, source_class TEXT NOT NULL, asset_class TEXT NOT NULL, canonical_symbol TEXT NOT NULL, provider_symbol TEXT NOT NULL, instrument_fp TEXT NOT NULL"
    private val versions = "${integer("dataset_schema_version", minimum = 1, maximum = Int.MAX_VALUE.toLong())}, ${integer("parser_version", minimum = 1, maximum = Int.MAX_VALUE.toLong())}"
    val tables: Map<String, String> = linkedMapOf(
        "market_metadata" to "CREATE TABLE market_metadata (singleton INTEGER PRIMARY KEY CHECK(singleton=1), format TEXT NOT NULL, db_version INTEGER NOT NULL, dataset_id TEXT NOT NULL, schema_fingerprint TEXT NOT NULL)",
        "ingest_sessions" to "CREATE TABLE ingest_sessions (session_id TEXT PRIMARY KEY NOT NULL, provider TEXT NOT NULL, feed TEXT NOT NULL, source_class TEXT NOT NULL, UNIQUE(session_id,provider,feed,source_class))",
        "session_high_water" to "CREATE TABLE session_high_water (session_id TEXT PRIMARY KEY NOT NULL REFERENCES ingest_sessions(session_id), ${integer("sequence", nullable = true, minimum = 0)})",
        "raw_receipts" to "CREATE TABLE raw_receipts (session_id TEXT NOT NULL, ${integer("sequence", minimum = 0)}, $identity, ${time("event")}, ${time("received")}, ${time("available")}, $versions, event_type TEXT NOT NULL, provider_event_id TEXT, event_identity_kind TEXT NOT NULL, event_identity_fp TEXT NOT NULL, content_fp TEXT NOT NULL, receipt_fp TEXT NOT NULL, bid TEXT, ask TEXT, bid_size TEXT, ask_size TEXT, trade_price TEXT, trade_size TEXT, open_value TEXT, high_value TEXT, low_value TEXT, close_value TEXT, volume TEXT, ${integer("trade_count", nullable = true, minimum = 0)}, vwap TEXT, digest_fp TEXT, PRIMARY KEY(session_id,sequence), FOREIGN KEY(session_id,provider,feed,source_class) REFERENCES ingest_sessions(session_id,provider,feed,source_class))",
        "canonical_revisions" to "CREATE TABLE canonical_revisions (revision_id TEXT PRIMARY KEY NOT NULL, logical_id TEXT NOT NULL, $identity, ${integer("interval_seconds", minimum = 1)}, ${time("start")}, ${time("end")}, ${time("received")}, ${time("available")}, open_value TEXT NOT NULL, high_value TEXT NOT NULL, low_value TEXT NOT NULL, close_value TEXT NOT NULL, volume TEXT, ${integer("trade_count", nullable = true, minimum = 0)}, vwap TEXT, finality TEXT NOT NULL, ${integer("revision_number", minimum = 0)}, previous_content_fp TEXT REFERENCES canonical_revisions(content_fp), ${integer("dataset_schema_version", minimum = 1, maximum = Int.MAX_VALUE.toLong())}, ${integer("canonical_version", minimum = 1, maximum = Int.MAX_VALUE.toLong())}, content_fp TEXT NOT NULL UNIQUE, UNIQUE(logical_id,dataset_schema_version,canonical_version,revision_number), CHECK((revision_number=0 AND previous_content_fp IS NULL) OR (revision_number>0 AND previous_content_fp IS NOT NULL)))",
        "bar_lineage" to "CREATE TABLE bar_lineage (revision_id TEXT NOT NULL REFERENCES canonical_revisions(revision_id), session_id TEXT NOT NULL, ${integer("sequence", minimum = 0)}, content_fp TEXT NOT NULL, $versions, PRIMARY KEY(revision_id,session_id,sequence), FOREIGN KEY(session_id,sequence) REFERENCES raw_receipts(session_id,sequence))",
        "bar_quality_flags" to "CREATE TABLE bar_quality_flags (revision_id TEXT NOT NULL REFERENCES canonical_revisions(revision_id), flag TEXT NOT NULL, PRIMARY KEY(revision_id,flag))",
    )
    val indexes = linkedMapOf(
        "raw_content" to "CREATE INDEX raw_content ON raw_receipts(content_fp)",
        "raw_event_identity" to "CREATE INDEX raw_event_identity ON raw_receipts(event_identity_kind,event_identity_fp)",
        "raw_series_time" to "CREATE INDEX raw_series_time ON raw_receipts(provider,feed,source_class,asset_class,canonical_symbol,provider_symbol,event_sec,event_nano)",
        "raw_availability" to "CREATE INDEX raw_availability ON raw_receipts(available_sec,available_nano)",
        "bar_availability" to "CREATE INDEX bar_availability ON canonical_revisions(available_sec,available_nano)",
        "bar_series_time" to "CREATE INDEX bar_series_time ON canonical_revisions(provider,feed,source_class,asset_class,canonical_symbol,provider_symbol,interval_seconds,start_sec,start_nano)",
        "lineage_receipt" to "CREATE INDEX lineage_receipt ON bar_lineage(session_id,sequence)",
    )
    val triggers: Map<String, String> = buildMap {
        for (table in tables.keys) {
            for (operation in listOf("UPDATE", "DELETE")) {
                if (table == "session_high_water" && operation == "UPDATE") continue
                val name = "${table}_${operation.lowercase(java.util.Locale.ROOT)}_blocked"
                put(name, "CREATE TRIGGER $name BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT,'APPEND_ONLY'); END")
            }
        }
        put("high_water_guard", "CREATE TRIGGER high_water_guard BEFORE UPDATE ON session_high_water BEGIN SELECT CASE WHEN NEW.session_id != OLD.session_id OR NEW.sequence IS NULL OR NOT ((OLD.sequence IS NULL AND NEW.sequence=0) OR (OLD.sequence IS NOT NULL AND OLD.sequence<9223372036854775807 AND NEW.sequence=OLD.sequence+1)) OR NOT EXISTS(SELECT 1 FROM raw_receipts WHERE session_id=NEW.session_id AND sequence=NEW.sequence) THEN RAISE(ABORT,'HIGH_WATER') END; END")
        put("raw_sequence_guard", "CREATE TRIGGER raw_sequence_guard BEFORE INSERT ON raw_receipts BEGIN SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM session_high_water WHERE session_id=NEW.session_id AND ((sequence IS NULL AND NEW.sequence=0) OR (sequence IS NOT NULL AND sequence<9223372036854775807 AND NEW.sequence=sequence+1))) THEN RAISE(ABORT,'SEQUENCE_GAP') END; END")
        put("raw_high_water", "CREATE TRIGGER raw_high_water AFTER INSERT ON raw_receipts BEGIN UPDATE session_high_water SET sequence=NEW.sequence WHERE session_id=NEW.session_id; END")
    }
    val statements: List<String> get() = tables.values + indexes.values + triggers.values
    val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(statements.joinToString("\n").toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun normalized(sql: String) = sql.trim().trimEnd(';').replace(Regex("\\s+"), " ")

    fun verify(connection: MarketSqlConnection) {
        val version = connection.query("PRAGMA user_version").single().values.single()?.toLongOrNull()
        demand(version == VERSION.toLong(), MarketStorageError.UNSUPPORTED_SCHEMA)
        val expected = tables.map { (n, s) -> n to ("table" to normalized(s)) }.toMap() +
            indexes.map { (n, s) -> n to ("index" to normalized(s)) }.toMap() +
            triggers.map { (n, s) -> n to ("trigger" to normalized(s)) }.toMap()
        val actual = connection.query("SELECT name,type,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*'")
            .associate { it.text("name") to (it.text("type") to normalized(it.text("sql"))) }
        demand(actual == expected, MarketStorageError.INCOMPATIBLE_SCHEMA)
        demand(connection.query("PRAGMA foreign_keys").single().values.single() == "1", MarketStorageError.INCOMPATIBLE_SCHEMA, "FOREIGN_KEYS_DISABLED")
        demand(connection.query("PRAGMA recursive_triggers").single().values.single() == "1", MarketStorageError.INCOMPATIBLE_SCHEMA, "RECURSIVE_TRIGGERS_DISABLED")
    }
}
