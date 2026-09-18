package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperPositionEvidenceCaptureCoordinatorTest {

    private val uuid = "11111111-1111-1111-1111-111111111111"
    private val accountBody = """{"id":"$uuid","cash":"1000","equity":"1000","buying_power":"2000","portfolio_value":"1000"}"""
    private val positionsBody = """[{"symbol":"SPY","side":"long","qty":"3"}]"""

    /** Records every GET the coordinator makes, and answers a configured script per endpoint. */
    private class ScriptedTransport(
        private val account: (Int) -> CaptureHttpResponse = { CaptureHttpResponse(200, """{"id":"11111111-1111-1111-1111-111111111111"}""") },
        private val positions: (Int) -> CaptureHttpResponse = { CaptureHttpResponse(200, "[]") },
    ) : PaperCaptureTransport {
        val accountRequests = AtomicInteger()
        val positionsRequests = AtomicInteger()
        val orderedRequests = mutableListOf<PaperCaptureEndpoint>()
        override suspend fun get(endpoint: PaperCaptureEndpoint, session: PaperCaptureSession): CaptureHttpResponse {
            orderedRequests += endpoint
            return when (endpoint) {
                PaperCaptureEndpoint.ACCOUNT -> account(accountRequests.incrementAndGet())
                PaperCaptureEndpoint.POSITIONS -> positions(positionsRequests.incrementAndGet())
            }
        }
    }

    private fun setup(transport: ScriptedTransport, initialCredentials: AlpacaCredentials? = AlpacaCredentials("KEY", "SEC")):
        Triple<PaperPositionEvidenceCaptureCoordinator, InMemoryEvidenceDatabase, MutableCredentialsProvider> {
        val db = InMemoryEvidenceDatabase()
        val configuration = PaperCaptureConfiguration(MutableCredentialsProvider(initialCredentials))
        val credentials = MutableCredentialsProvider(initialCredentials)
        val configWithMutable = PaperCaptureConfiguration(credentials)
        val repository = PaperBrokerPositionSnapshotRepository(db)
        val counter = AtomicInteger()
        val coordinator = PaperPositionEvidenceCaptureCoordinator(
            configuration = configWithMutable,
            transport = transport,
            repository = repository,
            wallTime = { counter.incrementAndGet().toLong() },
            monotonicTime = { counter.get().toLong() },
            newId = { "id-${counter.incrementAndGet()}" },
        )
        return Triple(coordinator, db, credentials)
    }

    @Test
    fun `success account and success positions publishes COMPLETE snapshot and issues exactly two GETs`() = runTest {
        val transport = ScriptedTransport(
            account = { CaptureHttpResponse(200, accountBody) },
            positions = { CaptureHttpResponse(200, positionsBody) },
        )
        val (coordinator, db) = setup(transport)
        val result = coordinator.captureManually()
        assertTrue(result is ManualCaptureResult.Persisted, "expected persisted, got $result")
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("COMPLETE", stored.metadata.completeness)
        assertNotNull(stored.metadata.accountRef)
        assertEquals(1, transport.accountRequests.get())
        assertEquals(1, transport.positionsRequests.get())
        assertEquals(listOf(PaperCaptureEndpoint.ACCOUNT, PaperCaptureEndpoint.POSITIONS), transport.orderedRequests)
        assertEquals(1, db.evidence.brokerSnapshots.size)
        assertEquals(1, stored.positions.size)
        assertEquals("SPY", stored.positions.single().symbol)
        assertEquals("3", stored.positions.single().qtyCanonicalDecimal)
    }

    @Test
    fun `no auth means no positions request and no COMPLETE snapshot`() = runTest {
        val transport = ScriptedTransport()
        val (coordinator, _) = setup(transport, initialCredentials = null)
        val result = coordinator.captureManually()
        assertTrue(result is ManualCaptureResult.Persisted)
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("FAILED", stored.metadata.completeness)
        assertEquals(0, transport.accountRequests.get())
        assertEquals(0, transport.positionsRequests.get())
    }

    @Test
    fun `account HTTP failure prevents positions request and prevents COMPLETE`() = runTest {
        val transport = ScriptedTransport(account = { CaptureHttpResponse(500, null, CaptureDiagnostic.HTTP_FAILURE) })
        val (coordinator, _) = setup(transport)
        val result = coordinator.captureManually()
        assertTrue(result is ManualCaptureResult.Persisted)
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("FAILED", stored.metadata.completeness)
        assertEquals(1, transport.accountRequests.get())
        assertEquals(0, transport.positionsRequests.get(), "positions must not be fetched after account failure")
    }

    @Test
    fun `account 401 recorded as AUTH_FAILURE and no positions request`() = runTest {
        val transport = ScriptedTransport(account = { CaptureHttpResponse(401, null, CaptureDiagnostic.AUTH_FAILURE) })
        val (coordinator, _) = setup(transport)
        val result = coordinator.captureManually()
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("FAILED", stored.metadata.completeness)
        assertEquals(CaptureDiagnostic.AUTH_FAILURE.name, stored.metadata.accountRequestOutcome)
        assertEquals(0, transport.positionsRequests.get())
    }

    @Test
    fun `positions HTTP failure keeps snapshot FAILED`() = runTest {
        val transport = ScriptedTransport(
            account = { CaptureHttpResponse(200, accountBody) },
            positions = { CaptureHttpResponse(503, null, CaptureDiagnostic.HTTP_FAILURE) },
        )
        val (coordinator, _) = setup(transport)
        val result = coordinator.captureManually()
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("FAILED", stored.metadata.completeness)
        assertEquals(CaptureDiagnostic.HTTP_FAILURE.name, stored.metadata.positionsRequestOutcome)
    }

    @Test
    fun `positions network failure keeps snapshot FAILED`() = runTest {
        val transport = ScriptedTransport(
            account = { CaptureHttpResponse(200, accountBody) },
            positions = { CaptureHttpResponse(null, null, CaptureDiagnostic.NETWORK_FAILURE) },
        )
        val (coordinator, _) = setup(transport)
        val result = coordinator.captureManually()
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("FAILED", stored.metadata.completeness)
    }

    @Test
    fun `positions empty body keeps snapshot FAILED with EMPTY_BODY_INVALID diagnostic`() = runTest {
        val transport = ScriptedTransport(
            account = { CaptureHttpResponse(200, accountBody) },
            positions = { CaptureHttpResponse(200, "") },
        )
        val (coordinator, _) = setup(transport)
        val result = coordinator.captureManually()
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("FAILED", stored.metadata.completeness)
        assertEquals(CaptureDiagnostic.EMPTY_BODY_INVALID.name, stored.metadata.positionsRequestOutcome)
    }

    @Test
    fun `positions empty JSON array is COMPLETE zero-portfolio`() = runTest {
        val transport = ScriptedTransport(
            account = { CaptureHttpResponse(200, accountBody) },
            positions = { CaptureHttpResponse(200, "[]") },
        )
        val (coordinator, _) = setup(transport)
        val result = coordinator.captureManually()
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("COMPLETE", stored.metadata.completeness)
        assertEquals(0, stored.positions.size)
    }

    @Test
    fun `invalid row in positions payload prevents COMPLETE`() = runTest {
        val transport = ScriptedTransport(
            account = { CaptureHttpResponse(200, accountBody) },
            positions = { CaptureHttpResponse(200, """[{"symbol":"SPY","side":"long","qty":"1"},"junk"]""") },
        )
        val (coordinator, _) = setup(transport)
        val result = coordinator.captureManually()
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("FAILED", stored.metadata.completeness)
        assertEquals(CaptureDiagnostic.INVALID_ROW.name, stored.metadata.positionsRequestOutcome)
    }

    @Test
    fun `duplicate symbol in positions payload prevents COMPLETE`() = runTest {
        val transport = ScriptedTransport(
            account = { CaptureHttpResponse(200, accountBody) },
            positions = { CaptureHttpResponse(200, """[{"symbol":"SPY","side":"long","qty":"1"},{"symbol":"SPY","side":"long","qty":"2"}]""") },
        )
        val (coordinator, _) = setup(transport)
        val result = coordinator.captureManually()
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("FAILED", stored.metadata.completeness)
        assertEquals(CaptureDiagnostic.DUPLICATE_SYMBOL.name, stored.metadata.positionsRequestOutcome)
    }

    @Test
    fun `account without stable id is ACCOUNT_REF_UNKNOWN and cannot become COMPLETE`() = runTest {
        val transport = ScriptedTransport(
            account = { CaptureHttpResponse(200, """{"cash":"1000"}""") },
            positions = { CaptureHttpResponse(200, "[]") },
        )
        val (coordinator, _) = setup(transport)
        val result = coordinator.captureManually()
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("FAILED", stored.metadata.completeness)
        assertNull(stored.metadata.accountRef)
    }

    @Test
    fun `credentials rotating mid-capture produces CONFIG_CHANGED and no COMPLETE`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val credentials = MutableCredentialsProvider(AlpacaCredentials("KEY0", "SEC0"))
        val configuration = PaperCaptureConfiguration(credentials)
        val counter = AtomicInteger()
        val transport = ScriptedTransport(
            account = {
                // Rotate credentials AFTER the account read but BEFORE positions.
                credentials.current = AlpacaCredentials("KEY1", "SEC1")
                CaptureHttpResponse(200, accountBody)
            },
            positions = { CaptureHttpResponse(200, "[]") },
        )
        val coordinator = PaperPositionEvidenceCaptureCoordinator(
            configuration = configuration,
            transport = transport,
            repository = PaperBrokerPositionSnapshotRepository(db),
            wallTime = { counter.incrementAndGet().toLong() },
            monotonicTime = { counter.get().toLong() },
            newId = { "id-${counter.incrementAndGet()}" },
        )
        val result = coordinator.captureManually()
        val stored = (result as ManualCaptureResult.Persisted).snapshot
        assertEquals("FAILED", stored.metadata.completeness)
        assertTrue(stored.metadata.diagnosticsJson.contains(CaptureDiagnostic.CONFIG_CHANGED.name))
        assertEquals(0, transport.positionsRequests.get(), "positions must be skipped when config changed")
    }

    @Test
    fun `persistence failure surfaces PersistenceFailure and does not corrupt state`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val credentials = MutableCredentialsProvider(AlpacaCredentials("KEY", "SEC"))
        val configuration = PaperCaptureConfiguration(credentials)
        val counter = AtomicInteger()
        val transport = ScriptedTransport(
            account = { CaptureHttpResponse(200, accountBody) },
            positions = { CaptureHttpResponse(200, positionsBody) },
        )
        // Fail on the persist transaction only: the coordinator makes one historyCheckpoint call
        // before it reaches persist, so we let the first outer transaction succeed.
        val calls = AtomicInteger()
        val flaky = object : PositionEvidenceDatabase by db {
            override suspend fun <T> transaction(block: suspend () -> T): T {
                if (calls.incrementAndGet() >= 2) throw RuntimeException("persistence broken")
                return block()
            }
        }
        val coordinator = PaperPositionEvidenceCaptureCoordinator(
            configuration = configuration,
            transport = transport,
            repository = PaperBrokerPositionSnapshotRepository(flaky),
            wallTime = { counter.incrementAndGet().toLong() },
            monotonicTime = { counter.get().toLong() },
            newId = { "id-${counter.incrementAndGet()}" },
        )
        val result = coordinator.captureManually()
        assertSame(ManualCaptureResult.PersistenceFailure, result)
        assertEquals(0, db.evidence.brokerSnapshots.size, "nothing should be persisted on transaction failure")
    }

    @Test
    fun `concurrent refresh is rejected as Busy`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val credentials = MutableCredentialsProvider(AlpacaCredentials("KEY", "SEC"))
        val configuration = PaperCaptureConfiguration(credentials)
        val db = InMemoryEvidenceDatabase()
        val counter = AtomicInteger()
        val transport = object : PaperCaptureTransport {
            val accountCalls = AtomicInteger()
            override suspend fun get(endpoint: PaperCaptureEndpoint, session: PaperCaptureSession): CaptureHttpResponse {
                if (endpoint == PaperCaptureEndpoint.ACCOUNT) {
                    accountCalls.incrementAndGet()
                    gate.await()
                    return CaptureHttpResponse(200, accountBody)
                }
                return CaptureHttpResponse(200, positionsBody)
            }
        }
        val coordinator = PaperPositionEvidenceCaptureCoordinator(
            configuration = configuration,
            transport = transport,
            repository = PaperBrokerPositionSnapshotRepository(db),
            wallTime = { counter.incrementAndGet().toLong() },
            monotonicTime = { counter.get().toLong() },
            newId = { "id-${counter.incrementAndGet()}" },
        )
        val first = async { coordinator.captureManually() }
        // Wait until the first refresh has entered the transport lock before starting a second.
        while (transport.accountCalls.get() == 0) { kotlinx.coroutines.yield() }
        val second = coordinator.captureManually()
        assertSame(ManualCaptureResult.Busy, second)
        gate.complete(Unit)
        val firstResult = first.await()
        assertTrue(firstResult is ManualCaptureResult.Persisted)
    }

    @Test
    fun `two sequential refreshes produce distinct manualRefreshId and both stored`() = runTest {
        val transport = ScriptedTransport(
            account = { CaptureHttpResponse(200, accountBody) },
            positions = { CaptureHttpResponse(200, positionsBody) },
        )
        val (coordinator, db) = setup(transport)
        val a = coordinator.captureManually() as ManualCaptureResult.Persisted
        val b = coordinator.captureManually() as ManualCaptureResult.Persisted
        assertNotEquals(a.snapshot.metadata.manualRefreshId, b.snapshot.metadata.manualRefreshId)
        assertNotEquals(a.snapshot.metadata.snapshotId, b.snapshot.metadata.snapshotId)
        assertEquals(2, db.evidence.brokerSnapshots.size)
        assertTrue(b.snapshot.metadata.sequence > a.snapshot.metadata.sequence)
    }

    @Test
    fun `failed refresh does not replace previous COMPLETE latest`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val credentials = MutableCredentialsProvider(AlpacaCredentials("KEY", "SEC"))
        val configuration = PaperCaptureConfiguration(credentials)
        val counter = AtomicInteger()

        var attempt = 0
        val transport = object : PaperCaptureTransport {
            override suspend fun get(endpoint: PaperCaptureEndpoint, session: PaperCaptureSession): CaptureHttpResponse {
                return if (endpoint == PaperCaptureEndpoint.ACCOUNT) {
                    attempt++
                    if (attempt == 1) CaptureHttpResponse(200, accountBody)
                    else CaptureHttpResponse(500, null, CaptureDiagnostic.HTTP_FAILURE)
                } else CaptureHttpResponse(200, positionsBody)
            }
        }
        val coordinator = PaperPositionEvidenceCaptureCoordinator(
            configuration = configuration,
            transport = transport,
            repository = PaperBrokerPositionSnapshotRepository(db),
            wallTime = { counter.incrementAndGet().toLong() },
            monotonicTime = { counter.get().toLong() },
            newId = { "id-${counter.incrementAndGet()}" },
        )
        val good = coordinator.captureManually() as ManualCaptureResult.Persisted
        val bad = coordinator.captureManually() as ManualCaptureResult.Persisted
        assertEquals("COMPLETE", good.snapshot.metadata.completeness)
        assertEquals("FAILED", bad.snapshot.metadata.completeness)
        val repository = PaperBrokerPositionSnapshotRepository(db)
        val latest = repository.latestComplete(good.snapshot.metadata.accountRef)
        assertEquals(good.snapshot.metadata.snapshotId, latest!!.metadata.snapshotId)
    }

    @Test
    fun `capture never issues any GET other than account and positions`() = runTest {
        val transport = ScriptedTransport(
            account = { CaptureHttpResponse(200, accountBody) },
            positions = { CaptureHttpResponse(200, "[]") },
        )
        val (coordinator, _) = setup(transport)
        repeat(3) { coordinator.captureManually() }
        val kinds = transport.orderedRequests.toSet()
        assertEquals(setOf(PaperCaptureEndpoint.ACCOUNT, PaperCaptureEndpoint.POSITIONS), kinds)
    }
}
