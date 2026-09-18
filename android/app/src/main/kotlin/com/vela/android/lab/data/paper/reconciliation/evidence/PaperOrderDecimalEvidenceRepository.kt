package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.reconciliation.domain.*
import com.vela.android.lab.db.room.entities.PaperOrderDecimalEvidenceEntity
import java.util.Locale

/** Prepared BEFORE the future lifecycle insertion. The high-water barrier forbids v7 backfill. */
class PreparedOrderDecimalEvidence internal constructor(
    internal val identity: PositionOrderIdentity,
    internal val accountRef: String,
    internal val highWater: Long,
    internal val rawRequested: String,
    internal val rawFilled: String,
    internal val rawStatus: String,
    internal val averagePrice: String?,
    internal val filledAt: String?,
) {
    override fun toString(): String = "PreparedOrderDecimalEvidence(REDACTED)"
}

/** Additive adapter only: no changes to the status client/parser/tracker, no network of its own. */
class PaperOrderDecimalEvidenceRepository(private val database: PositionEvidenceDatabase) {
    suspend fun prepareFutureObservation(body: String, identity: PositionOrderIdentity, accountRef: String): PreparedOrderDecimalEvidence = database.transaction {
        require(validAccountRef(accountRef))
        val fields = (StrictEvidenceJson.parse(body) as? StrictEvidenceJson.Obj)?.fields ?: error("Invalid order evidence")
        fun text(name: String): String = (fields[name] as? StrictEvidenceJson.Str)?.text ?: error("Invalid order field")
        fun optional(name: String): String? = when (val item = fields[name]) {
            null, StrictEvidenceJson.Null -> null
            is StrictEvidenceJson.Str -> item.text
            else -> error("Invalid optional order field")
        }
        require(text("id") == identity.orderId && text("client_order_id") == identity.clientOrderId)
        require(text("symbol").uppercase(Locale.ROOT) == identity.symbol && text("side").uppercase(Locale.ROOT) == identity.side)
        require(identity.attemptId.isNotBlank() && identity.orderSequenceId != null && identity.orderSequenceId > 0)
        val requested = text("qty")
        val filled = text("filled_qty")
        val requestedQuantity = DecimalQuantity.parse(requested)
        val filledQuantity = DecimalQuantity.parse(filled)
        require(requestedQuantity > DecimalQuantity.ZERO && filledQuantity >= DecimalQuantity.ZERO && filledQuantity <= requestedQuantity)
        PreparedOrderDecimalEvidence(identity, accountRef, database.evidence.lifecycleHighWater(), requested, filled,
            text("status"), optional("filled_avg_price"), optional("filled_at"))
    }

    suspend fun attachToNewObservation(prepared: PreparedOrderDecimalEvidence, observationId: Long, recordedAt: Long) = database.transaction {
        require(observationId > prepared.highWater && recordedAt >= 0)
        val history = database.fullCanonicalHistory().single { it.positionIdentity() == prepared.identity }
        val observation = history.lifecycleObservations.single { it.databaseId == observationId }
        require(observation.source == "ALPACA_PAPER_ORDER_GET" && observation.httpStatusCode == 200)
        require(observation.rawStatus == prepared.rawStatus && observation.filledAtIso == prepared.filledAt)
        // Comparison to legacy storage only. Persist the ORIGINAL text, not this lossy projection.
        require(prepared.rawRequested.toDouble() == history.quantity && prepared.rawFilled.toDouble() == observation.filledQuantity)
        require(prepared.averagePrice?.toDouble() == observation.filledAveragePriceUsd)
        listOf("qty" to prepared.rawRequested, "filled_qty" to prepared.rawFilled).forEach { (field, raw) ->
            database.evidence.insertOrderDecimalEvidence(PaperOrderDecimalEvidenceEntity(observationId, field,
                prepared.identity.attemptId, requireNotNull(prepared.identity.orderId), requireNotNull(prepared.identity.clientOrderId),
                requireNotNull(prepared.identity.symbol), requireNotNull(prepared.identity.side), requireNotNull(prepared.identity.orderSequenceId),
                prepared.accountRef, raw, DecimalQuantity.parse(raw).toString(), DecimalProvenance.EXACT_DECIMAL.name,
                observation.payloadFingerprint, "ORDER_DECIMAL_SIDECAR_V1", recordedAt))
        }
    }
}

/** No LIMIT, no dashboard inputs. Scope assurance is explicit; v7 has no durable account binding. */
class ConsistentPositionHistoryReader(private val database: PositionEvidenceDatabase) {
    suspend fun read(accountRef: String?, scopeAssurance: CutAssurance = CutAssurance.UNKNOWN): PositionHistoryInput = database.transaction {
        val histories = database.fullCanonicalHistory()
        var scoped = validAccountRef(accountRef) && scopeAssurance == CutAssurance.CONFIRMED
        val decimals = linkedMapOf<String, OrderDecimalEvidence>()
        for (history in histories) {
            val rows = database.evidence.decimalEvidence(history.submitAttemptId)
            if (rows.isEmpty()) continue
            if (rows.any { it.accountRef != accountRef }) { scoped = false; continue }
            val identity = history.positionIdentity()
            val valid = rows.all { row ->
                row.orderId == identity.orderId && row.clientOrderId == identity.clientOrderId && row.symbol == identity.symbol &&
                    row.side == identity.side && row.orderSequenceId == identity.orderSequenceId &&
                    row.provenance == DecimalProvenance.EXACT_DECIMAL.name && row.field in setOf("qty", "filled_qty") &&
                    history.lifecycleObservations.any { it.databaseId == row.observationId && it.payloadFingerprint == row.payloadFingerprint } &&
                    runCatching { DecimalQuantity.parse(row.rawDecimal) == requireCanonicalDecimal(row.canonicalDecimal) }.getOrDefault(false)
            }
            val requested = rows.filter { it.field == "qty" }.map { it.canonicalDecimal }.distinct()
            decimals[history.submitAttemptId] = OrderDecimalEvidence(identity,
                if (valid && requested.size == 1) QuantityEvidence.decimal(requested.single()) else QuantityEvidence.INVALID,
                rows.filter { it.field == "filled_qty" }.associate { it.observationId to
                    DecimalFillObservation(it.payloadFingerprint, if (valid) QuantityEvidence.decimal(it.canonicalDecimal) else QuantityEvidence.INVALID) })
        }
        PositionHistoryInput(accountRef, histories, if (scoped) HistoryCompleteness.COMPLETE else HistoryCompleteness.INCOMPLETE, decimals)
    }
}
