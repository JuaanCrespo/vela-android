package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.db.room.migrations.BOOTSTRAP_V9_SQL
import java.io.File
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Runs the production migration SQL against SQLite, not a hand-reimplemented migration. */
class BootstrapMigrationSqlTest {
    private val app = listOf(File("."), File("app")).first { it.resolve("schemas").isDirectory }
    private fun schema(version: Int) = JSONObject(app.resolve("schemas/com.vela.android.lab.db.room.VelaDatabase/$version.json").readText()).getJSONObject("database")
    private fun entities(version: Int) = schema(version).getJSONArray("entities").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }

    @Test fun `exported v9 adds only the four audited columns`() {
        val old = entities(8).associateBy { it.getString("tableName") }; val new = entities(9).associateBy { it.getString("tableName") }
        assertEquals(old.keys, new.keys); assertEquals(9, schema(9).getInt("version"))
        old.forEach { (name, entity) ->
            val fields = entity.getJSONArray("fields"); val next = new.getValue(name).getJSONArray("fields")
            val added = when (name) { "paper_position_anchor" -> 3; "paper_position_reconciliation_row" -> 1; else -> 0 }
            assertEquals(fields.length() + added, next.length(), name)
            repeat(fields.length()) { assertTrue(fields.getJSONObject(it).similar(next.getJSONObject(it)), "$name field $it changed") }
            listOf("primaryKey", "indices", "foreignKeys").forEach { key -> assertTrue(JSONObject().put(key, entity.get(key)).similar(JSONObject().put(key, new.getValue(name).get(key))), "$name $key") }
        }
    }

    @Test fun `empty v8 migration inserts no rows manifests or decimals`() = database(false)
    @Test fun `populated v8 migration preserves every original column and foreign key`() = database(true)

    private fun database(populated: Boolean) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            val old = entities(8)
            db.createStatement().use { sql ->
                sql.execute("PRAGMA foreign_keys=ON")
                old.forEach { entity ->
                    val table = entity.getString("tableName")
                    sql.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    repeat(indices.length()) { sql.execute(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) }
                }
            }
            fun insert(table: String, overrides: Map<String, Any?>) {
                val fields = old.single { it.getString("tableName") == table }.getJSONArray("fields")
                val columns = (0 until fields.length()).map { fields.getJSONObject(it) }
                val names = columns.map { it.getString("columnName") }
                db.prepareStatement("INSERT INTO $table (${names.joinToString()}) VALUES (${names.joinToString { "?" }})").use { statement ->
                    columns.forEachIndexed { index, field ->
                        val name = field.getString("columnName")
                        val value = if (name in overrides) overrides[name] else if (!field.getBoolean("notNull")) null
                            else if (field.getString("affinity") == "TEXT") "fixture-$name" else 0
                        statement.setObject(index + 1, value)
                    }; statement.executeUpdate()
                }
            }
            if (populated) {
                listOf("A", "B", "future").forEachIndexed { index, id ->
                    insert("paper_order_submit_audit", mapOf("id" to index + 1, "eventKey" to "$id:SUBMITTED", "submitAttemptId" to id,
                        "symbol" to "SPY", "side" to "BUY", "quantity" to 1.0, "status" to "SUBMITTED", "alpacaOrderId" to "order-$id"))
                    insert("paper_order_reconciliation", mapOf("submitAttemptId" to id, "alpacaOrderId" to "order-$id", "mappingStatus" to "EXACT",
                        "terminal" to 1, "filledQuantity" to 1.0, "resetAcknowledgedAtEpochMillis" to 1000))
                    insert("paper_order_lifecycle_observation", mapOf("id" to index + 1, "observationKey" to "obs-$id", "submitAttemptId" to id,
                        "alpacaOrderId" to "order-$id", "status" to "FILLED", "rawStatus" to "filled", "terminal" to 1, "filledQuantity" to 1.0))
                }
                listOf("qty", "filled_qty").forEach { field -> insert("paper_order_decimal_evidence", mapOf("observationId" to 3, "field" to field,
                    "attemptId" to "future", "rawDecimal" to "1.000", "canonicalDecimal" to "1", "provenance" to "EXACT_DECIMAL")) }
                listOf("COMPLETE", "FAILED").forEachIndexed { i, status -> insert("paper_broker_snapshot", mapOf("snapshotId" to "s$i", "sequence" to i + 1,
                    "manualRefreshId" to "m$i", "completeness" to status, "diagnosticsJson" to if (i == 0) "[]" else "[\"NETWORK_FAILURE\"]")) }
                insert("paper_broker_position_snapshot", mapOf("snapshotId" to "s0", "symbol" to "SPY", "qtyRawDecimal" to "8.00", "qtyCanonicalDecimal" to "8"))
                insert("paper_position_anchor", mapOf("anchorId" to "old", "brokerSnapshotId" to "s0", "baselineQty" to "8", "status" to "ACTIVE", "version" to 1))
                insert("paper_position_anchor_cursor", mapOf("anchorId" to "old", "attemptId" to "future", "includedFilledQty" to "1", "provenance" to "EXACT_DECIMAL"))
                insert("paper_position_anchor_event", mapOf("eventId" to "old:1", "anchorId" to "old", "version" to 1, "type" to "CREATED"))
                listOf("r-before", "r-later").forEach { id ->
                    insert("paper_position_reconciliation_report", mapOf("reportId" to id, "brokerSnapshotId" to "s0", "engineVersion" to POSITION_ENGINE_V1,
                        "policyVersion" to POSITION_POLICY_V1, "inputsJson" to "{\"version\":1,\"history\":[],\"anchors\":[],\"decimals\":[],\"accountRef\":null,\"completeness\":\"INCOMPLETE\"}"))
                    insert("paper_position_reconciliation_row", mapOf("reportId" to id, "symbol" to "SPY", "anchorId" to null, "state" to "UNKNOWN", "knownVelaDelta" to "2", "knownDeltaProvenance" to "LEGACY_DOUBLE_DERIVED"))
                }
            }
            fun values(entity: JSONObject): List<List<String?>> {
                val fields = entity.getJSONArray("fields")
                val names = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
                return db.createStatement().use { statement -> statement.executeQuery("SELECT ${names.joinToString()} FROM ${entity.getString("tableName")} ORDER BY rowid").use { rows ->
                    buildList { while (rows.next()) add(names.indices.map { rows.getString(it + 1) }) }
                } }
            }
            val before = old.associate { it.getString("tableName") to values(it) }
            db.createStatement().use { statement -> BOOTSTRAP_V9_SQL.forEach { statement.execute(it) } }
            old.forEach { assertEquals(before[it.getString("tableName")], values(it), it.getString("tableName")) }
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT coverageMode, bootstrapCutJson, bootstrapCutDigest FROM paper_position_anchor").use { rows ->
                    while (rows.next()) { assertEquals("EXACT_CURSORS_V1", rows.getString(1)); assertNull(rows.getString(2)); assertNull(rows.getString(3)) }
                }
                statement.executeQuery("SELECT coverageJson FROM paper_position_reconciliation_row").use { rows -> while (rows.next()) assertNull(rows.getString(1)) }
                statement.executeQuery("PRAGMA foreign_key_check").use { assertFalse(it.next()) }
                statement.executeQuery("PRAGMA integrity_check").use { assertTrue(it.next()); assertEquals("ok", it.getString(1)) }
            }
        }
    }
}
