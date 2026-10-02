package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.paper.reconciliation.domain.fixture
import com.vela.android.lab.data.paper.status.*
import com.vela.android.lab.db.room.dao.PaperOrderReconciliationDao
import com.vela.android.lab.db.room.dao.PaperPositionEvidenceDao
import com.vela.android.lab.db.room.entities.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FutureLifecycleDecimalEvidenceTest {
    private val account = "paper-v1:" + "a".repeat(64)
    private val uuid = "11111111-1111-4111-8111-111111111111"
    private fun body(order: String = uuid, client: String = "client-a", fill: String = "0.100000000000000001") =
        """{"id":"$order","client_order_id":"$client","symbol":"SPY","side":"buy","qty":"1","filled_qty":"$fill","status":"partially_filled","type":"market","time_in_force":"day","filled_avg_price":null,"filled_at":null}"""
    private val target = PaperOrderLifecycleLookupTarget("a", 1, 2, 1, uuid, "client-a", "SPY", "BUY", 1.0, "MARKET", "DAY")

    @Test fun `original text survives existing GET and display conversion without another request`() = runTest {
        val provider = MutableCredentialsProvider(); var requests = 0
        val client = AlpacaPaperOrderStatusReadOnlyClient(provider,
            AlpacaPaperOrderStatusHttpClient { _, _, _ -> requests++; PaperOrderStatusHttpResult.Success(200, body()) },
            accountRefProvider = { account }, rawEvidenceFactory = ::captureFutureOrderDecimals)
        val result = client.fetchOrderStatus(target) as AlpacaPaperOrderStatusReadOnlyClient.FetchResult.Ok
        assertEquals(1, requests); assertEquals(0.1, result.value.filledQuantity)
        assertEquals("0.100000000000000001", JSONObject(result.evidence.exactDecimalEvidence!!.fieldsJson).getString("filled_qty"))
        assertFalse(result.evidence.toString().contains("0.100000000000000001"))
    }

    @Test fun `unknown account preserves status behavior without inventing binding or extra GET`() = runTest {
        var requests = 0
        val client = AlpacaPaperOrderStatusReadOnlyClient(MutableCredentialsProvider(),
            AlpacaPaperOrderStatusHttpClient { _, _, _ -> requests++; PaperOrderStatusHttpResult.Success(200, body()) },
            rawEvidenceFactory = ::captureFutureOrderDecimals)
        val result = client.fetchOrderStatus(target) as AlpacaPaperOrderStatusReadOnlyClient.FetchResult.Ok
        assertNull(result.evidence.exactDecimalEvidence); assertEquals(1, requests)
    }

    @Test fun `account change across status request refuses exact binding`() = runTest {
        var binding: String? = account
        val client = AlpacaPaperOrderStatusReadOnlyClient(MutableCredentialsProvider(),
            AlpacaPaperOrderStatusHttpClient { _, _, _ -> binding = null; PaperOrderStatusHttpResult.Success(200, body()) },
            accountRefProvider = { binding }, rawEvidenceFactory = ::captureFutureOrderDecimals)
        assertNull((client.fetchOrderStatus(target) as AlpacaPaperOrderStatusReadOnlyClient.FetchResult.Ok).evidence.exactDecimalEvidence)
    }

    @Test fun `account proof is ephemeral config bound and cleared on rotation or explicit revocation`() = runTest {
        val provider = MutableCredentialsProvider(); val config = PaperCaptureConfiguration(provider)
        val first = config.read()
        assertNull(config.accountRefFor(provider.current!!))
        config.rememberAccount(first, account); assertEquals(account, config.accountRefFor(provider.current!!))
        provider.current = AlpacaCredentials("KEY1", "SEC1")
        assertNull(config.accountRefFor(provider.current!!)); config.rememberAccount(first, account)
        assertNull(config.accountRefFor(provider.current!!))
        config.rememberAccount(config.read(), account); config.clearAccountBinding()
        assertNull(config.accountRefFor(provider.current!!))
        assertNull(PaperCaptureConfiguration(provider).accountRefFor(provider.current!!))
    }

    @Test fun `numeric JSON or invalid raw decimal never becomes reconstructed source text`() {
        assertNull(captureFutureOrderDecimals(body().replace("\"filled_qty\":\"0.100000000000000001\"", "\"filled_qty\":0.1"), account))
        assertNull(captureFutureOrderDecimals(body(fill = "NaN"), account))
        assertNull(captureFutureOrderDecimals(body(fill = "2"), account))
        assertNull(captureFutureOrderDecimals(body(), "unknown"))
    }

    @Test fun `future observation and sidecars commit together preserving original decimal text`() = runTest {
        val rig = WriterRig(); val original = rig.history
        rig.write()
        assertEquals(original.lifecycleObservations, rig.history.lifecycleObservations.take(1))
        assertEquals(2, rig.dao.decimalEvidence.size)
        val fill = rig.dao.decimalEvidence.single { it.field == "filled_qty" }
        assertEquals("0.100000000000000001", fill.rawDecimal); assertEquals(fill.rawDecimal, fill.canonicalDecimal)
        assertEquals(11L, fill.observationId); assertEquals("new-fingerprint", fill.payloadFingerprint)
        assertEquals("a", fill.attemptId); assertEquals(account, fill.accountRef)
        assertTrue(rig.dao.decimalEvidence.none { it.observationId <= 10 })
        assertTrue(rig.transactionEntries > 0)
    }

    @Test fun `sidecar failure rolls back future lifecycle and projection in transaction envelope`() = runTest {
        val rig = WriterRig(); val original = rig.history; rig.failSidecar = true
        assertThrows(IllegalStateException::class.java) { runBlocking { rig.write() } }
        assertEquals(original, rig.history); assertTrue(rig.dao.decimalEvidence.isEmpty()); assertTrue(rig.observations.isEmpty())
    }

    @Test fun `mismatched original identity cannot attach decimal evidence`() = runTest {
        val rig = WriterRig(); val original = rig.history
        assertThrows(IllegalArgumentException::class.java) { runBlocking { rig.write(body(order = "other")) } }
        assertEquals(original, rig.history); assertTrue(rig.dao.decimalEvidence.isEmpty())
    }

    private inner class WriterRig {
        val dao = InMemoryEvidenceDao().apply { lifecycleHighWater = 10 }
        var history = fixture(observations = listOf("new" to "0")).history
        var observations = emptyList<PaperOrderLifecycleObservationEntity>()
        var failSidecar = false
        var transactionEntries = 0
        val database = object : PositionEvidenceDatabase {
            override val evidence = object : PaperPositionEvidenceDao by dao {
                override suspend fun insertOrderDecimalEvidence(row: PaperOrderDecimalEvidenceEntity) {
                    if (failSidecar) error("sidecar insert failure")
                    dao.insertOrderDecimalEvidence(row)
                }
            }
            override suspend fun fullCanonicalHistory() = listOf(history)
            override suspend fun <T> transaction(block: suspend () -> T): T {
                transactionEntries++
                val beforeHistory = history; val beforeRows = observations; val beforeDecimals = dao.decimalEvidence.toList()
                return try { block() } catch (failure: Throwable) {
                    history = beforeHistory; observations = beforeRows; dao.decimalEvidence.clear(); dao.decimalEvidence.addAll(beforeDecimals)
                    throw failure
                }
            }
        }
        val lifecycle = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PaperOrderReconciliationDao::class.java)) { _, method, args ->
            when (method.name) {
                "appendLifecycleObservation" -> {
                    val observation = (args[0] as PaperOrderLifecycleObservationEntity).copy(id = 11)
                    observations = observations + observation
                    val next = history.currentLifecycle!!.copy(databaseId = 11, status = observation.status, rawStatus = observation.rawStatus,
                        filledQuantity = observation.filledQuantity, payloadFingerprint = "new-fingerprint", observedAtEpochMillis = observation.observedAtEpochMillis)
                    history = history.copy(lifecycleObservations = history.lifecycleObservations + next, currentLifecycle = next)
                    Unit
                }
                "lifecycleByAttemptId" -> observations
                else -> error("Unexpected DAO method ${method.name}")
            }
        } as PaperOrderReconciliationDao
        suspend fun write(raw: String = body(order = "broker-a")) {
            val observation = PaperOrderLifecycleObservationEntity(observationKey = "future-only", submitAttemptId = "a", alpacaOrderId = "broker-a",
                status = "PARTIALLY_FILLED", rawStatus = "partially_filled", observedAtEpochMillis = 2000, terminal = false,
                filledQuantity = 0.1, filledAveragePriceUsd = null, filledAtIso = null, source = "ALPACA_PAPER_ORDER_GET", httpStatusCode = 200, submitAuditEntryId = 2)
            val updated = PaperOrderReconciliationEntity("a", 1, 2, "preview-a", "dry-a", "broker-a", "client-a", "SPY", "BUY", 1.0, "MARKET", "DAY",
                null, 1, "SUBMITTED", "EXACT", null, "PARTIALLY_FILLED", "partially_filled", 2000, false, 0.1, null, null, "ALPACA_PAPER_ORDER_GET", 200, null)
            FutureLifecycleDecimalEvidenceWriter(database, lifecycle).append(observation, updated,
                PaperOrderStatusFetchEvidence(200, exactDecimalEvidence = captureFutureOrderDecimals(raw, account)))
        }
    }
}
