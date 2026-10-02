package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.reconciliation.domain.*
import org.json.JSONArray

/** Positional, typed V1 schema. Array order and sorted identity inventory define canonical bytes. */
object BootstrapEvidenceCodec {
    fun encode(cut: BootstrapCutManifest): String = array(cut.manifestVersion, cut.coverageMode.name,
        cut.brokerSnapshotId, cut.brokerSequence, cut.accountRef, cut.symbol, qty(cut.baselineQty), cut.localHistoryDigest,
        cut.submitAuditHighWater, cut.lifecycleHighWater, cut.orderHighWater, cut.capturedAt, cut.acceptedAt,
        JSONArray(cut.inventory.sortedBy { it.history.submitAttemptId }.map {
            array(it.classification.name, PositionEvidenceCodec.history(it.history), it.exactRequestedAtCut?.let(::qty), it.exactTerminalAtCut?.let(::qty))
        }), cut.preAnchorHistoryAssurance.name).toString()

    fun digest(cut: BootstrapCutManifest): String = evidenceDigest(encode(cut))

    fun decode(json: String, digest: String): BootstrapCutManifest {
        require(evidenceDigest(json) == digest) { "Bootstrap digest mismatch" }
        val row = JSONArray(json)
        require(row.length() == 15 && row.getInt(0) == 1)
        val inventory = row.getJSONArray(13)
        val cut = BootstrapCutManifest(row.getInt(0), PositionCoverageMode.valueOf(row.getString(1)), row.getString(2), row.getLong(3),
            row.getString(4), row.getString(5), readQty(row.getJSONArray(6)), row.getString(7), row.getLong(8), row.getLong(9),
            row.getLong(10), row.getLong(11), row.getLong(12), (0 until inventory.length()).map { index ->
                val item = inventory.getJSONArray(index)
                require(item.length() == 4)
                BootstrapHistoryEntry(PositionEvidenceCodec.readHistory(item.getJSONArray(1)), AbsorbedHistoryClassification.valueOf(item.getString(0)),
                    if (item.isNull(2)) null else readQty(item.getJSONArray(2)), if (item.isNull(3)) null else readQty(item.getJSONArray(3)))
            }, PreAnchorHistoryAssurance.valueOf(row.getString(14)))
        require(cut.coverageMode == PositionCoverageMode.LEGACY_BOOTSTRAP_V1 && validAccountRef(cut.accountRef))
        require(encode(cut) == json) { "Noncanonical bootstrap manifest" }
        return cut
    }

    fun encodeCoverage(value: PositionCoverageMetadata): String = array(1, value.coverageMode.name,
        value.preAnchorHistoryAssurance.name, value.postAnchorCoverageAssurance.name, qty(value.historicalKnownVelaDelta),
        qty(value.postAnchorExactDelta), value.bootstrapSnapshotId, value.bootstrapCutDigest).toString()

    fun decodeCoverage(json: String): PositionCoverageMetadata {
        val row = JSONArray(json)
        require(row.length() == 8 && row.getInt(0) == 1)
        return PositionCoverageMetadata(PositionCoverageMode.valueOf(row.getString(1)), PreAnchorHistoryAssurance.valueOf(row.getString(2)),
            CutAssurance.valueOf(row.getString(3)), readQty(row.getJSONArray(4)), readQty(row.getJSONArray(5)), row.getString(6), row.getString(7))
            .also { require(encodeCoverage(it) == json) }
    }

    private fun qty(value: QuantityEvidence) = array(value.quantity?.toString(), value.provenance.name)
    private fun readQty(row: JSONArray): QuantityEvidence {
        require(row.length() == 2)
        return QuantityEvidence(if (row.isNull(0)) null else requireCanonicalDecimal(row.getString(0)), DecimalProvenance.valueOf(row.getString(1)))
    }
    private fun array(vararg values: Any?) = JSONArray(values.map { it ?: org.json.JSONObject.NULL })
}
