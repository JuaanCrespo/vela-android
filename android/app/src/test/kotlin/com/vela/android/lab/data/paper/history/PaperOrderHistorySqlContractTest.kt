package com.vela.android.lab.data.paper.history

import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Real host SQLite, exact @Query strings and unmodified exported Room v7 schema.
 * This does not claim Android instrumentation coverage: it closes SQL ordering contracts
 * locally without an emulator, copied production database, or handwritten query substitute.
 */
class PaperOrderHistorySqlContractTest {
    @TempDir
    lateinit var directory: Path

    @ParameterizedTest
    @ValueSource(longs = [1_000L, 100L])
    fun `latest N follows result ingestion not equal or rolled back timestamps`(aTimestamp: Long) {
        HistorySqlFixture(directory.resolve("ordering.db").toFile(), aTimestamp = aTimestamp).use { db ->
            assertEquals(listOf("a", "b", "pending"), db.strings("latestAttemptIds", 3))
            assertEquals(listOf("a"), db.strings("latestAttemptIds", 1))
            assertEquals(emptyList<String>(), db.strings("latestAttemptIds", 0))
            assertEquals(listOf("a", "b", "pending"), db.strings("allAuditAttemptIds"))
            assertEquals(listOf("10", "40"), db.strings("auditsByAttemptId", "a"))
            assertEquals(listOf("200", "210"), db.strings("lifecycleByAttemptId", "a"))
            repeat(3) {
                assertEquals(listOf("a", "b"), db.strings("latestAttemptIds", 2))
            }
        }
    }

    @Test
    fun `all filtered SQL queries preserve their documented durable domain ordering`() {
        HistorySqlFixture(directory.resolve("filters.db").toFile()).use { db ->
            assertEquals(listOf("a", "b"), db.strings("attemptIdsBySymbol", "SPY"))
            assertEquals(listOf("a", "b"), db.strings("attemptIdsBySide", "BUY"))
            assertEquals(listOf("a"), db.strings("attemptIdsByOrderId", "order-a"))
            assertEquals(listOf("b"), db.strings("attemptIdsByClientOrderId", "client-b"))
            assertEquals(listOf("b", "a"), db.strings("terminalCandidateAttemptIds"))
            assertEquals(listOf("a"), db.strings("filledCandidateAttemptIds"))
            assertEquals(listOf("b", "a"), db.strings("attemptIdsWithinSubmitResultTimeRange", 1_000L, 1_001L))
            assertEquals(emptyList<String>(), db.strings("attemptIdsWithinSubmitResultTimeRange", 0L, 1_000L))
            assertEquals(emptyList<String>(), db.strings("attemptIdsWithinSubmitResultTimeRange", 1_001L, 2_000L))
            assertEquals(listOf("a", "b"), db.strings("allReconciliationAttemptIds"))
            assertEquals(listOf("a"), db.strings("reconciliationByAttemptId", "a"))
            assertEquals(listOf("40"), db.strings("auditById", 40L))
            assertEquals(listOf("70"), db.strings("dryRunByClientId", "dry-a"))
        }
    }

    @Test
    fun `exact identity parameters cannot broaden matches`() {
        HistorySqlFixture(directory.resolve("identity.db").toFile()).use { db ->
            listOf("", "missing", "order-a' OR 1=1 --").forEach { identity ->
                assertEquals(emptyList<String>(), db.strings("attemptIdsByOrderId", identity))
                assertEquals(emptyList<String>(), db.strings("attemptIdsByClientOrderId", identity))
                assertEquals(emptyList<String>(), db.strings("auditsByAttemptId", identity))
                assertEquals(emptyList<String>(), db.strings("lifecycleByAttemptId", identity))
            }
        }
    }

    @Test
    fun `queries retain duplicate observations and survive close and reopen byte unchanged`() {
        val file = directory.resolve("restart.db").toFile()
        lateinit var before: Map<String, List<List<String?>>>
        HistorySqlFixture(file).use { db ->
            before = db.snapshot()
            assertEquals(listOf("200", "210"), db.strings("lifecycleByAttemptId", "a"))
            assertThrows(java.sql.SQLException::class.java) {
                db.connection.createStatement().use { it.execute("DELETE FROM paper_order_submit_audit") }
            }
        }
        val beforeHash = digest(file)
        HistorySqlFixture(file, initialize = false).use { reopened ->
            // Exercise every DAO read against persisted rows with SQLite write protection enabled.
            val calls = mapOf<String, Array<out Any>>(
                "allAuditAttemptIds" to emptyArray(),
                "allReconciliationAttemptIds" to emptyArray(),
                "attemptIdsByOrderId" to arrayOf("order-a"),
                "attemptIdsByClientOrderId" to arrayOf("client-a"),
                "auditById" to arrayOf(40L),
                "auditsByAttemptId" to arrayOf("a"),
                "reconciliationByAttemptId" to arrayOf("a"),
                "dryRunByClientId" to arrayOf("dry-a"),
                "lifecycleByAttemptId" to arrayOf("a"),
                "terminalCandidateAttemptIds" to emptyArray(),
                "filledCandidateAttemptIds" to emptyArray(),
                "attemptIdsBySymbol" to arrayOf("SPY"),
                "attemptIdsBySide" to arrayOf("BUY"),
                "attemptIdsWithinSubmitResultTimeRange" to arrayOf(0L, 2_000L),
                "latestAttemptIds" to arrayOf(3),
            )
            assertEquals(reopened.queries.keys, calls.keys, "Every actual DAO query must be exercised")
            calls.forEach { (name, arguments) -> reopened.strings(name, *arguments) }
            assertEquals(before, reopened.snapshot())
            assertEquals(listOf("a", "b", "pending"), reopened.strings("latestAttemptIds", 3))
        }
        assertEquals(beforeHash, digest(file))
    }

    @Test
    fun `source SQL is query only bounded where required and viewer never drops repeated rows`() {
        HistorySqlFixture(directory.resolve("contract.db").toFile()).use { db ->
            assertEquals(15, db.queries.size)
            db.queries.forEach { (method, sql) ->
                assertTrue(sql.startsWith("SELECT "), method)
                assertFalse(Regex("\\b(INSERT|UPDATE|DELETE|REPLACE|DROP|ALTER)\\b").containsMatchIn(sql), method)
            }
            assertTrue(db.queries.getValue("latestAttemptIds").endsWith("LIMIT :limit"))
        }
        val root = appRoot()
        val viewer = File(root, "src/main/kotlin/com/vela/android/lab/ui/history/PaperOrderHistoryViewer.kt").readText()
        val repository = File(root, "src/main/kotlin/com/vela/android/lab/data/paper/history/PaperOrderHistoryRepository.kt").readText()
        assertTrue(viewer.contains("order.lifecycleObservations.forEachIndexed"))
        assertFalse(viewer.contains("distinctBy"))
        assertFalse(repository.contains("Int.MAX_VALUE"))
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

private fun appRoot(): File = listOf(File("."), File("app"), File("android/app"))
    .firstOrNull { File(it, "src/main/kotlin/com/vela/android/lab/db/room/dao/PaperOrderHistoryDao.kt").isFile }
    ?: error("Cannot locate Android app sources")

private class HistorySqlFixture(
    file: File,
    initialize: Boolean = true,
    aTimestamp: Long = 1_000L,
) : AutoCloseable {
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private val schema = JSONObject(File(appRoot(), "schemas/com.vela.android.lab.db.room.VelaDatabase/7.json").readText())
        .getJSONObject("database")
    private val entities = schema.getJSONArray("entities").let { array ->
        (0 until array.length()).associate { i ->
            val entity = array.getJSONObject(i)
            entity.getString("tableName") to entity
        }
    }
    val queries: Map<String, String> = run {
        val source = File(appRoot(), "src/main/kotlin/com/vela/android/lab/db/room/dao/PaperOrderHistoryDao.kt").readText()
        val queryAndMethod = Regex("@Query\\(([\\s\\S]*?)\\)\\s*suspend fun (\\w+)")
        queryAndMethod.findAll(source).associate { match ->
            val sql = Regex("\"([^\"]*)\"").findAll(match.groupValues[1])
                .joinToString("") { it.groupValues[1] }
            match.groupValues[2] to sql
        }
    }

    init {
        assertEquals(7, schema.getInt("version"))
        if (initialize) {
            entities.forEach { (table, entity) ->
                execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.getJSONArray("indices").let { indexes ->
                    for (i in 0 until indexes.length()) {
                        execute(indexes.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
            }
            // Physical insertion order and wall-clock order intentionally disagree with durable ids.
            audit(40, "a", "SUBMITTED", aTimestamp)
            audit(20, "b", "ATTEMPT_STARTED", 4_000)
            audit(30, "b", "SUBMITTED", 1_000)
            audit(10, "a", "ATTEMPT_STARTED", 5_000)
            audit(50, "pending", "ATTEMPT_STARTED", 6_000)
            insert("paper_order_reconciliation", mapOf("submitAttemptId" to "b"))
            insert("paper_order_reconciliation", mapOf("submitAttemptId" to "a", "resetAcknowledgedAtEpochMillis" to 7_000L))
            insert("paper_order_dry_run_audits", mapOf("id" to 70, "clientDryRunId" to "dry-a"))
            lifecycle(210, "a", "FILLED", 50, true)
            lifecycle(110, "b", "CANCELED", 100, true)
            lifecycle(200, "a", "FILLED", 100, true)
            lifecycle(100, "b", "NEW", 100, false)
        }
        execute("PRAGMA query_only=ON")
    }

    fun strings(method: String, vararg values: Any): List<String> =
        connection.prepareStatement(queries.getValue(method)).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }

    fun snapshot(): Map<String, List<List<String?>>> = listOf(
        "paper_order_submit_audit", "paper_order_lifecycle_observation",
        "paper_order_reconciliation", "paper_order_dry_run_audits",
    ).associateWith { table ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM $table ORDER BY 1").use { result ->
                buildList {
                    while (result.next()) add((1..result.metaData.columnCount).map(result::getString))
                }
            }
        }
    }

    private fun audit(id: Int, attempt: String, status: String, timestamp: Long) = insert(
        "paper_order_submit_audit",
        mapOf("id" to id, "submitAttemptId" to attempt, "eventKey" to "$attempt:$status",
            "status" to status, "submittedAtEpochMillis" to timestamp,
            "symbol" to if (attempt == "pending") "QQQ" else "SPY",
            "side" to if (attempt == "pending") "SELL" else "BUY",
            "alpacaOrderId" to if (status == "SUBMITTED") "order-$attempt" else null,
            "clientOrderId" to "client-$attempt"),
    )

    private fun lifecycle(id: Int, attempt: String, status: String, timestamp: Long, terminal: Boolean) = insert(
        "paper_order_lifecycle_observation",
        mapOf("id" to id, "observationKey" to "observation-$id", "submitAttemptId" to attempt,
            "status" to status, "rawStatus" to status.lowercase(), "observedAtEpochMillis" to timestamp,
            "terminal" to if (terminal) 1 else 0, "alpacaOrderId" to "order-$attempt"),
    )

    /** Synthetic values only: no application database or credentials are opened by this fixture. */
    private fun insert(table: String, overrides: Map<String, Any?>) {
        val fields = entities.getValue(table).getJSONArray("fields")
        val values = (0 until fields.length()).associate { index ->
            val field = fields.getJSONObject(index)
            val name = field.getString("columnName")
            val value = if (overrides.containsKey(name)) overrides[name] else when {
                !field.getBoolean("notNull") -> null
                field.getString("affinity") == "TEXT" -> ""
                else -> 0
            }
            name to value
        }
        val columns = values.keys.joinToString(",") { "`$it`" }
        val parameters = values.keys.joinToString(",") { "?" }
        connection.prepareStatement("INSERT INTO `$table` ($columns) VALUES ($parameters)").use { statement ->
            values.values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun execute(sql: String) = connection.createStatement().use { it.execute(sql) }
    override fun close() = connection.close()
}
