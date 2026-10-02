package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.reconciliation.domain.DecimalQuantity
import com.vela.android.lab.data.paper.reconciliation.domain.positionIdentity
import com.vela.android.lab.data.paper.status.PaperLifecycleEvidenceWriter
import com.vela.android.lab.data.paper.status.PaperOrderRawDecimalEvidence
import com.vela.android.lab.data.paper.status.PaperOrderStatusFetchEvidence
import com.vela.android.lab.db.room.dao.PaperOrderReconciliationDao
import com.vela.android.lab.db.room.entities.PaperOrderLifecycleObservationEntity
import com.vela.android.lab.db.room.entities.PaperOrderReconciliationEntity
import org.json.JSONObject

/** Reads the original existing response only. No HTTP and no synthesis of source decimal text. */
fun captureFutureOrderDecimals(body: String, accountRef: String): PaperOrderRawDecimalEvidence? = runCatching {
    require(validAccountRef(accountRef))
    val fields = (StrictEvidenceJson.parse(body) as? StrictEvidenceJson.Obj)?.fields ?: error("Invalid evidence")
    val allowed = listOf("id", "client_order_id", "symbol", "side", "qty", "filled_qty", "status", "filled_avg_price", "filled_at")
    val json = JSONObject()
    allowed.forEach { key ->
        val value = fields[key]
        if (key in setOf("filled_avg_price", "filled_at") && (value == null || value == StrictEvidenceJson.Null)) json.put(key, JSONObject.NULL)
        else json.put(key, (value as? StrictEvidenceJson.Str)?.text ?: error("Original text required"))
    }
    val requested = DecimalQuantity.parse(json.getString("qty"))
    val filled = DecimalQuantity.parse(json.getString("filled_qty"))
    require(requested > DecimalQuantity.ZERO && filled >= DecimalQuantity.ZERO && filled <= requested)
    PaperOrderRawDecimalEvidence(json.toString(), accountRef)
}.getOrNull()

/** Same Room transaction: future observation, projection and exact sidecar commit or roll back together. */
class FutureLifecycleDecimalEvidenceWriter(
    private val database: PositionEvidenceDatabase,
    private val lifecycle: PaperOrderReconciliationDao,
) : PaperLifecycleEvidenceWriter {
    override suspend fun append(observation: PaperOrderLifecycleObservationEntity, updated: PaperOrderReconciliationEntity,
        evidence: PaperOrderStatusFetchEvidence) = database.transaction {
        val raw = evidence.exactDecimalEvidence
        val prepared = raw?.let {
            val identity = database.fullCanonicalHistory().single { it.submitAttemptId == observation.submitAttemptId }.positionIdentity()
            PaperOrderDecimalEvidenceRepository(database).prepareFutureObservation(it.fieldsJson, identity, it.accountRef)
        }
        lifecycle.appendLifecycleObservation(observation, updated)
        if (prepared != null) {
            val inserted = lifecycle.lifecycleByAttemptId(observation.submitAttemptId).single { it.observationKey == observation.observationKey }
            PaperOrderDecimalEvidenceRepository(database).attachToNewObservation(prepared, inserted.id, observation.observedAtEpochMillis)
        }
    }
}
