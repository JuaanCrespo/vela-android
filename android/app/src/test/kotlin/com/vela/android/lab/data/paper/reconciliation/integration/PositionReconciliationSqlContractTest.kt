package com.vela.android.lab.data.paper.reconciliation.integration

import java.io.File
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Execute the new exact DAO read queries on host SQLite using the unchanged exported v8 schema. */
class PositionReconciliationSqlContractTest {
    @Test fun `empty v8 has no latest report and no anchors`() = withDatabase {
        assertTrue(query("latestReportId").isEmpty()); assertTrue(query("allAnchors").isEmpty())
    }

    @Test fun `latest report follows snapshot sequence despite regressing wall clock`() = withDatabase {
        insert("paper_broker_snapshot", mapOf("snapshotId" to "s1", "sequence" to 1, "manualRefreshId" to "m1"))
        insert("paper_broker_snapshot", mapOf("snapshotId" to "s2", "sequence" to 2, "manualRefreshId" to "m2"))
        insert("paper_position_reconciliation_report", mapOf("reportId" to "r1", "brokerSnapshotId" to "s1", "createdAtEpochMillis" to 9000))
        insert("paper_position_reconciliation_report", mapOf("reportId" to "r2", "brokerSnapshotId" to "s2", "createdAtEpochMillis" to 100))
        assertEquals(listOf("r2"), query("latestReportId"))
        insert("paper_broker_snapshot", mapOf("snapshotId" to "failed3", "sequence" to 3, "manualRefreshId" to "m3", "completeness" to "FAILED"))
        assertEquals(listOf("r2"), query("latestReportId"))
    }

    @Test fun `all anchor query preserves active invalidated and superseded evidence across accounts`() = withDatabase {
        insert("paper_broker_snapshot", mapOf("snapshotId" to "s", "sequence" to 1, "manualRefreshId" to "m"))
        listOf("ACTIVE", "INVALIDATED", "SUPERSEDED").forEachIndexed { index, status ->
            insert("paper_position_anchor", mapOf("anchorId" to "a$index", "brokerSnapshotId" to "s", "status" to status,
                "createdAtEpochMillis" to (3-index), "accountRef" to "account$index", "activeKey" to null))
        }
        assertEquals(listOf("a2", "a1", "a0"), query("allAnchors"))
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA query_only=ON")
            assertEquals(3, query("allAnchors").size)
            assertThrows(java.sql.SQLException::class.java) { statement.execute("DELETE FROM paper_position_anchor") }
        }
    }

    private fun withDatabase(block: SqlFixture.() -> Unit) { SqlFixture().use { it.block() } }
    private class SqlFixture : AutoCloseable {
        private val app = listOf(File("."), File("app")).first { it.resolve("schemas").isDirectory }
        private val schema = JSONObject(app.resolve("schemas/com.vela.android.lab.db.room.VelaDatabase/8.json").readText()).getJSONObject("database")
        private val entities = schema.getJSONArray("entities").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        init {
            assertEquals(8, schema.getInt("version"))
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=ON")
                entities.forEach { entity ->
                    val table = entity.getString("tableName")
                    statement.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    repeat(indices.length()) { statement.execute(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) }
                }
            }
        }
        fun insert(table: String, overrides: Map<String, Any?>) {
            val fields = entities.single { it.getString("tableName") == table }.getJSONArray("fields")
            val columns = (0 until fields.length()).map { fields.getJSONObject(it) }
            val names = columns.map { it.getString("columnName") }
            val values = columns.map { column ->
                val name = column.getString("columnName")
                if (name in overrides) overrides[name] else if (!column.getBoolean("notNull")) null
                else if (column.getString("affinity") == "TEXT") "test" else 0L
            }
            connection.prepareStatement("INSERT INTO $table (${names.joinToString(",")}) VALUES (${names.joinToString(",") { "?" }})").use { statement ->
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }; statement.executeUpdate()
            }
        }
        fun query(method: String): List<String> {
            val dao = app.resolve("src/main/kotlin/com/vela/android/lab/db/room/dao/PaperPositionEvidenceDao.kt").readText()
            val sql = Regex("@Query\\(\"([^\"]+)\"\\)\\s+suspend fun $method\\(").find(dao)!!.groupValues[1]
            return connection.createStatement().use { statement -> statement.executeQuery(sql).use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            } }
        }
        override fun close() { connection.close() }
    }
}
