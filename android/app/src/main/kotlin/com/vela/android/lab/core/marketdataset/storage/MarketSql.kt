package com.vela.android.lab.core.marketdataset.storage

import com.vela.android.lab.core.marketdataset.domain.*
import java.util.Collections

/** Narrow parameter binding boundary. All values are exact text or SQL NULL, never binary floats. */
internal interface MarketSqlConnection : AutoCloseable {
    fun execute(sql: String, arguments: List<String?> = emptyList())
    fun query(sql: String, arguments: List<String?> = emptyList()): List<Map<String, String?>>
    /** Serialized connection transaction, IMMEDIATE writer reservation, rollback on any exception. */
    fun <T> transaction(block: () -> T): T
}

enum class MarketStorageError {
    UNSUPPORTED_SCHEMA, INCOMPATIBLE_SCHEMA, WRONG_DATASET, SESSION_CONFLICT,
    SEQUENCE_GAP, RECEIPT_CONFLICT, PROVIDER_ID_CONFLICT, REVISION_CONFLICT,
    REVISION_PARENT, LINEAGE_CONFLICT, DATA_INTEGRITY_ERROR, SQL_BUSY, SQL_FAILURE, CLOSED,
}

class MarketStorageException(val code: MarketStorageError, val diagnostic: String = code.name) :
    IllegalStateException("${code.name}:$diagnostic")

internal fun demand(condition: Boolean, code: MarketStorageError, diagnostic: String = code.name) {
    if (!condition) throw MarketStorageException(code, diagnostic)
}

data class IngestSessionRegistration(val session: IngestSessionId, val source: MarketSourceIdentity)
data class MarketAppendBatch(
    val sessions: List<IngestSessionRegistration> = emptyList(),
    val receipts: List<RawMarketEventEnvelope> = emptyList(),
    val revisions: List<CanonicalMarketBar> = emptyList(),
)
data class MarketAppendResult(val insertedReceipts: Int, val retriedReceipts: Int, val insertedRevisions: Int, val retriedRevisions: Int)
data class MarketIntegrityIssue(val reason: String)
class MarketIntegrityReport(issues: Collection<MarketIntegrityIssue>) {
    val issues: List<MarketIntegrityIssue> = Collections.unmodifiableList(issues.distinct().sortedBy { it.reason })
    val valid: Boolean get() = issues.isEmpty()
}

internal fun MarketSqlConnection.insert(table: String, fields: Map<String, String?>) {
    execute("INSERT INTO $table (${fields.keys.joinToString()}) VALUES (${fields.keys.joinToString { "?" }})", fields.values.toList())
}
internal fun Map<String, String?>.text(name: String): String = this[name]
    ?: throw MarketStorageException(MarketStorageError.DATA_INTEGRITY_ERROR, "MISSING_$name")
internal fun Map<String, String?>.number(name: String): Long = text(name).toLongOrNull()
    ?: throw MarketStorageException(MarketStorageError.DATA_INTEGRITY_ERROR, "INTEGER_$name")
internal fun Map<String, String?>.version(name: String): Int = number(name).let {
    demand(it in 1..Int.MAX_VALUE.toLong(), MarketStorageError.DATA_INTEGRITY_ERROR, "VERSION_$name"); it.toInt()
}
internal fun Map<String, String?>.timestamp(prefix: String): MarketTimestamp {
    val nano = number("${prefix}_nano")
    demand(nano in 0..999_999_999, MarketStorageError.DATA_INTEGRITY_ERROR, "NANO_$prefix")
    return MarketTimestamp(number("${prefix}_sec"), nano.toInt())
}
