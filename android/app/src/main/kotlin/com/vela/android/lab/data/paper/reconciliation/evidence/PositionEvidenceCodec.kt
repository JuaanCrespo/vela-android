package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.history.*
import com.vela.android.lab.data.paper.reconciliation.domain.*
import org.json.JSONArray
import org.json.JSONObject

internal data class DurablePositionInputs(val history: PositionHistoryInput, val anchors: List<PositionAnchor>)

/** Versioned immutable copy of source evidence, NOT a persisted expected-position truth.
 * Legacy Double values round-trip as explicitly legacy text, never exact source decimals.
 */
internal object PositionEvidenceCodec {
    fun encode(value: DurablePositionInputs): String = JSONObject()
        .put("version", 1).put("accountRef", value.history.accountRef ?: JSONObject.NULL)
        .put("completeness", value.history.completeness.name)
        .put("history", JSONArray(value.history.histories.map(::history)))
        .put("decimals", JSONArray(value.history.decimalEvidence.toSortedMap().map { (key, evidence) ->
            array(key, identity(evidence.identity), quantity(evidence.requestedQuantity),
                JSONArray(evidence.observations.toSortedMap().map { (id, observation) ->
                    array(id, observation.payloadFingerprint, quantity(observation.filledQuantity))
                }))
        })).put("anchors", JSONArray(value.anchors.map(::anchor))).toString()

    fun decode(text: String): DurablePositionInputs {
        val root = JSONObject(text)
        require(root.getInt("version") == 1)
        val decimals = root.getJSONArray("decimals").items().associate { item ->
            val row = item as JSONArray
            row.getString(0) to OrderDecimalEvidence(readIdentity(row.getJSONArray(1)), readQuantity(row.getJSONArray(2)),
                row.getJSONArray(3).items().associate {
                    val observation = it as JSONArray
                    observation.getLong(0) to DecimalFillObservation(observation.getString(1), readQuantity(observation.getJSONArray(2)))
                })
        }
        return DurablePositionInputs(PositionHistoryInput(
            if (root.isNull("accountRef")) null else root.getString("accountRef"),
            root.getJSONArray("history").items().map { readHistory(it as JSONArray) },
            HistoryCompleteness.valueOf(root.getString("completeness")), decimals,
        ), root.getJSONArray("anchors").items().map { readAnchor(it as JSONArray) })
    }

    fun anchor(value: PositionAnchor): JSONArray = array(value.anchorId, value.symbol, value.accountRef,
        quantity(value.baselineQty), array(value.cut.orderSequenceInclusive, value.cut.lifecycleSequenceInclusive, value.cut.assurance.name),
        JSONArray(value.cursors.map { array(identity(it.identity), quantity(it.includedFilledQty), it.observationSequence, it.payloadFingerprint) }),
        value.status.name, value.createdAtEpochMillis, value.invalidatedAtEpochMillis, value.invalidationReason?.name)

    private fun readAnchor(row: JSONArray): PositionAnchor {
        val cut = row.getJSONArray(4)
        return PositionAnchor(row.getString(0), row.getString(1), row.getString(2), readQuantity(row.getJSONArray(3)),
            AnchorCoverageCut(cut.getLong(0), cut.getLong(1), CutAssurance.valueOf(cut.getString(2))),
            row.getJSONArray(5).items().map { item ->
                val cursor = item as JSONArray
                AnchorOrderCursor(readIdentity(cursor.getJSONArray(0)), readQuantity(cursor.getJSONArray(1)), cursor.getLong(2), cursor.getString(3))
            }, AnchorStatus.valueOf(row.getString(6)), row.getLong(7), row.long(8),
            row.text(9)?.let(AnchorInvalidationReason::valueOf))
    }

    private fun identity(value: PositionOrderIdentity) = array(value.attemptId, value.orderId, value.clientOrderId, value.orderSequenceId, value.symbol, value.side)
    private fun readIdentity(row: JSONArray) = PositionOrderIdentity(row.getString(0), row.text(1), row.text(2), row.long(3), row.text(4), row.text(5))
    private fun quantity(value: QuantityEvidence) = array(value.quantity?.toString(), value.provenance.name)
    private fun readQuantity(row: JSONArray) = QuantityEvidence(row.text(0)?.let(::requireCanonicalDecimal), DecimalProvenance.valueOf(row.getString(1)))
    private fun array(vararg values: Any?) = JSONArray(values.map { it ?: JSONObject.NULL })
    private fun JSONArray.text(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun JSONArray.long(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun JSONArray.items(): List<Any> = (0 until length()).map(::get)

    private fun history(value: CanonicalPaperOrderHistory) = array(
        value.submitAttemptId,
        value.orderSequenceId,
        value.linkedClientDryRunId,
        value.previewId,
        value.auditStartRowId,
        value.auditResultRowId,
        value.alpacaOrderId,
        value.clientOrderId,
        value.symbol,
        value.side,
        value.quantity?.toString(),
        value.orderType,
        value.timeInForce,
        value.limitPriceUsd?.toString(),
        value.dryRunAuditRowId,
        value.decisionCreatedAtEpochMillis,
        value.localSubmitResult,
        value.localSubmitResultAtEpochMillis,
        value.submitHttpStatusCode,
        value.initialAlpacaStatus,
        value.alpacaSubmittedAtIso,
        JSONArray(value.lifecycleObservations.map(::observation)),
        value.currentLifecycle?.let(::observation),
        value.mappingState,
        value.resolved,
        value.unresolved,
        value.ambiguous,
        value.resetAcknowledgedAtEpochMillis,
        value.integrityStatus.name,
        JSONArray(value.integrityDiagnostics.map { it.name }),
    )
    private fun readHistory(row: JSONArray): CanonicalPaperOrderHistory {
        require(row.length() == 30)
        return CanonicalPaperOrderHistory(
            submitAttemptId = row.getString(0),
            orderSequenceId = row.long(1),
            linkedClientDryRunId = row.text(2),
            previewId = row.text(3),
            auditStartRowId = row.long(4),
            auditResultRowId = row.long(5),
            alpacaOrderId = row.text(6),
            clientOrderId = row.text(7),
            symbol = row.text(8),
            side = row.text(9),
            quantity = row.text(10)?.toDouble(),
            orderType = row.text(11),
            timeInForce = row.text(12),
            limitPriceUsd = row.text(13)?.toDouble(),
            dryRunAuditRowId = row.long(14),
            decisionCreatedAtEpochMillis = row.long(15),
            localSubmitResult = row.text(16),
            localSubmitResultAtEpochMillis = row.long(17),
            submitHttpStatusCode = row.long(18)?.toInt(),
            initialAlpacaStatus = row.text(19),
            alpacaSubmittedAtIso = row.text(20),
            lifecycleObservations = row.getJSONArray(21).items().map { readObservation(it as JSONArray) },
            currentLifecycle = if (row.isNull(22)) null else readObservation(row.getJSONArray(22)),
            mappingState = row.text(23),
            resolved = row.getBoolean(24),
            unresolved = row.getBoolean(25),
            ambiguous = row.getBoolean(26),
            resetAcknowledgedAtEpochMillis = row.long(27),
            integrityStatus = PaperHistoryIntegrityStatus.valueOf(row.getString(28)),
            integrityDiagnostics = row.getJSONArray(29).items().map { PaperHistoryIntegrityDiagnostic.valueOf(it as String) },
        )
    }
    private fun observation(value: CanonicalPaperLifecycleObservation) = array(
        value.databaseId,
        value.observedAtEpochMillis,
        value.status,
        value.rawStatus,
        value.terminal,
        value.filledQuantity?.toString(),
        value.filledAveragePriceUsd?.toString(),
        value.filledAtIso,
        value.source,
        value.httpStatusCode,
        value.submitAuditEntryId,
        value.payloadFingerprint,
        value.samePayloadAsPrevious,
    )
    private fun readObservation(row: JSONArray): CanonicalPaperLifecycleObservation {
        require(row.length() == 13)
        return CanonicalPaperLifecycleObservation(
            databaseId = row.getLong(0),
            observedAtEpochMillis = row.getLong(1),
            status = row.getString(2),
            rawStatus = row.getString(3),
            terminal = row.getBoolean(4),
            filledQuantity = row.text(5)?.toDouble(),
            filledAveragePriceUsd = row.text(6)?.toDouble(),
            filledAtIso = row.text(7),
            source = row.getString(8),
            httpStatusCode = row.long(9)?.toInt(),
            submitAuditEntryId = row.long(10),
            payloadFingerprint = row.getString(11),
            samePayloadAsPrevious = row.getBoolean(12),
        )
    }
}

