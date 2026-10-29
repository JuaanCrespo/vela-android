package com.vela.android.lab.core.marketdataset.storage

import com.vela.android.lab.core.marketdataset.domain.*
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

internal class JdbcMarketConnection(file: Path) : MarketSqlConnection {
    val connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
    var failAfterSql: String? = null
    init {
        execute("PRAGMA foreign_keys=ON"); execute("PRAGMA recursive_triggers=ON"); execute("PRAGMA busy_timeout=5000")
        query("PRAGMA journal_mode=DELETE"); execute("PRAGMA synchronous=FULL")
    }
    override fun execute(sql: String, arguments: List<String?>) {
        connection.prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.execute()
        }
        if (failAfterSql?.let(sql::contains) == true) throw IllegalStateException("INJECTED_FAILURE")
    }
    override fun query(sql: String, arguments: List<String?>): List<Map<String, String?>> = connection.prepareStatement(sql).use { statement ->
        arguments.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add((1..rows.metaData.columnCount).associate { rows.metaData.getColumnLabel(it) to rows.getString(it) })
            }
        }
    }
    private fun <T> reportBusy(block: () -> T): T = try { block() }
    catch (failure: SQLException) {
        if ((failure.errorCode and 255) in listOf(5, 6)) throw MarketStorageException(MarketStorageError.SQL_BUSY)
        throw failure
    }
    override fun <T> transaction(block: () -> T): T = reportBusy { synchronized(this) {
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            val result = block()
            connection.createStatement().use { it.execute("COMMIT") }
            result
        } catch (failure: Throwable) {
            try { connection.createStatement().use { it.execute("ROLLBACK") } } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    } }
    override fun close() = connection.close()
    fun count(table: String): Long = query("SELECT COUNT(*) AS amount FROM $table").single().number("amount")
}

internal val storageDataset = DatasetId("market-storage-tests")
internal val storageSource = MarketSourceIdentity(MarketProviderId.ALPACA, MarketFeedId.IEX, MarketSourceClass.REAL_PROVIDER)
internal fun storageTime(second: Long, nano: Int = 0) = MarketTimestamp(second, nano)
internal fun storageEvent(sequence: Long = 0, session: String = "A", bid: String = "1.0", source: MarketSourceIdentity = storageSource): RawMarketEventEnvelope =
    RawMarketEventEnvelope(MarketInstrumentIdentity(DatasetAssetClass.EQUITY, source.provider, source.feed, CanonicalMarketSymbol("SPY"), ProviderMarketSymbol("SPY")),
        source, storageTime(0), storageTime(60), storageTime(60), IngestPosition(IngestSessionId(session), IngestSequence(sequence)),
        ParserVersion(1), DatasetSchemaVersion(1), RawMarketPayload.Quote(RawMarketDecimal(bid), RawMarketDecimal("2.00")))
internal fun registration(event: RawMarketEventEnvelope = storageEvent()) = IngestSessionRegistration(event.ingestPosition.session, event.source)
internal fun storageBar(event: RawMarketEventEnvelope = storageEvent()): CanonicalMarketBar = CanonicalMarketBar(
    LogicalBarIdentity(event.instrument, event.source, BarInterval.ONE_MINUTE, storageTime(0)), storageTime(60), event.receivedAt, event.availableAt,
    ExactMarketDecimal.parse("1.0"), ExactMarketDecimal.parse("2"), ExactMarketDecimal.parse("1"), ExactMarketDecimal.parse("2.0"), null, null, null,
    BarFinality.FINAL, BarRevision(0, null), DatasetSchemaVersion(1), CanonicalizationVersion(1), BarEvidence(listOf(event.reference()), listOf(BarQualityFlag.UNKNOWN)))
internal fun storeError(code: MarketStorageError, block: () -> Unit): MarketStorageException =
    assertThrows(MarketStorageException::class.java, block).also { assertEquals(code, it.code) }
