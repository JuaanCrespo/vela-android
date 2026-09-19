package com.vela.android.lab.data.paper.reconciliation.integration

import com.vela.android.lab.data.paper.history.CanonicalPaperOrderHistory
import com.vela.android.lab.data.paper.reconciliation.domain.PositionFixture
import com.vela.android.lab.data.paper.reconciliation.evidence.*
import com.vela.android.lab.db.room.dao.PaperPositionEvidenceDao
import com.vela.android.lab.db.room.entities.*

/** Real coordinator/parser/repositories/engines, fake network and host-only persistence. */
internal class PositionIntegrationRig {
    val rawAccount = "11111111-1111-1111-1111-111111111111"
    val accountRef = StrictPaperCaptureParser().account("""{"id":"$rawAccount"}""").value!!.accountRef!!
    var now = 1_000L
    var accountResponse = CaptureHttpResponse(200, """{"id":"$rawAccount"}""")
    var positionsResponse = CaptureHttpResponse(200, """[{"symbol":"SPY","side":"long","qty":"5"}]""")
    val requests = mutableListOf<PaperCaptureEndpoint>()
    var onRequest: suspend (PaperCaptureEndpoint) -> Unit = {}
    val dao = InMemoryEvidenceDao()
    var histories = emptyList<CanonicalPaperOrderHistory>()
    var fullHistoryReads = 0
    var failReport = false
    var failSnapshot = false
    var failAnchor = false
    var failLoad = false
    val events = mutableListOf<String>()
    val database = object : PositionEvidenceDatabase {
        override val evidence = object : PaperPositionEvidenceDao by dao {
            override suspend fun insertBrokerSnapshot(row: PaperBrokerSnapshotEntity) {
                if (failSnapshot) error("secret unsafe body")
                events += "persist-snapshot"
                dao.insertBrokerSnapshot(row)
            }
            override suspend fun insertPositionReconciliationReport(row: PaperPositionReconciliationReportEntity) {
                if (failReport) error("secret unsafe body")
                events += "persist-report"
                dao.insertPositionReconciliationReport(row)
            }
            override suspend fun insertPositionAnchor(row: PaperPositionAnchorEntity) {
                if (failAnchor) error("secret unsafe body")
                events += "persist-anchor"
                dao.insertPositionAnchor(row)
            }
            override suspend fun latestReportId(): String? {
                if (failLoad) error("secret unsafe body")
                events += "load-overview"
                return dao.latestReportId()
            }
        }
        override suspend fun <T> transaction(block: suspend () -> T): T = block()
        override suspend fun fullCanonicalHistory(): List<CanonicalPaperOrderHistory> {
            fullHistoryReads++
            return histories
        }
    }
    val snapshots = PaperBrokerPositionSnapshotRepository(database)
    val anchors = PaperPositionAnchorRepository(database)
    val reports = PaperPositionReconciliationReportRepository(database)
    val credentials = MutableCredentialsProvider()
    val coordinator = PaperPositionEvidenceCaptureCoordinator(PaperCaptureConfiguration(credentials),
        PaperCaptureTransport { endpoint, _ ->
            requests += endpoint
            onRequest(endpoint)
            if (endpoint == PaperCaptureEndpoint.ACCOUNT) accountResponse else positionsResponse
        }, snapshots, wallTime = { now }, monotonicTime = { now })
    fun store() = CanonicalPositionReconciliationStore(database, coordinator, now = { now })

    /** Fixture already has explicit exact binding; this is test data, not a production backfill. */
    fun addExact(fixture: PositionFixture) {
        histories = histories + fixture.history
        val identity = fixture.decimals.identity
        fixture.decimals.observations.forEach { (id, evidence) ->
            listOf("qty" to fixture.decimals.requestedQuantity, "filled_qty" to evidence.filledQuantity).forEach { (field, quantity) ->
                val text = requireNotNull(quantity.quantity).toString()
                dao.decimalEvidence += PaperOrderDecimalEvidenceEntity(id, field, identity.attemptId,
                    identity.orderId!!, identity.clientOrderId!!, identity.symbol!!, identity.side!!,
                    identity.orderSequenceId!!, accountRef, text, text, quantity.provenance.name,
                    evidence.payloadFingerprint, "ORDER_DECIMAL_SIDECAR_V1", now)
            }
        }
    }
}
