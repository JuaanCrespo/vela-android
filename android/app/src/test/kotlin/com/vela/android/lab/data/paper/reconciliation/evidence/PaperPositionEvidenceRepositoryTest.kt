package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.paper.reconciliation.domain.AnchorCoverageCut
import com.vela.android.lab.data.paper.reconciliation.domain.AnchorInvalidationReason
import com.vela.android.lab.data.paper.reconciliation.domain.AnchorStatus
import com.vela.android.lab.data.paper.reconciliation.domain.BrokerPositionSide
import com.vela.android.lab.data.paper.reconciliation.domain.CutAssurance
import com.vela.android.lab.data.paper.reconciliation.domain.DecimalProvenance
import com.vela.android.lab.data.paper.reconciliation.domain.DecimalQuantity
import com.vela.android.lab.data.paper.reconciliation.domain.PositionAnchor
import com.vela.android.lab.data.paper.reconciliation.domain.QuantityEvidence
import com.vela.android.lab.data.paper.reconciliation.domain.SnapshotFreshness
import com.vela.android.lab.db.room.entities.PaperBrokerSnapshotEntity
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperPositionEvidenceRepositoryTest {

    private val accountRef = "paper-v1:" + "a".repeat(64)
    private val session = "11111111-1111-1111-1111-111111111111"
    private val configRef = "$session:1"

    private fun completeMetadata(id: String, refresh: String = UUID.randomUUID().toString(),
        started: Long = 100L, receivedCount: Int = 1): PaperBrokerSnapshotEntity =
        PaperBrokerSnapshotEntity(
            snapshotId = id, sequence = 0, manualRefreshId = refresh, accountRef = accountRef,
            source = POSITION_CAPTURE_SOURCE, parserVersion = POSITION_CAPTURE_PARSER_V1,
            configRef = configRef, sessionRef = session,
            startedAtEpochMillis = started, accountCompletedAtEpochMillis = started + 1,
            positionsCompletedAtEpochMillis = started + 2, completedAtEpochMillis = started + 3,
            startedMonotonicNanos = 0, completedMonotonicNanos = 100,
            accountRequestOutcome = CaptureDiagnostic.SUCCESS_COMPLETE.name,
            positionsRequestOutcome = CaptureDiagnostic.SUCCESS_COMPLETE.name,
            accountHttpStatus = 200, positionsHttpStatus = 200,
            positionsReceivedCount = receivedCount, positionsValidatedCount = receivedCount,
            completeness = "COMPLETE", diagnosticsJson = "[]",
            persistedAtEpochMillis = started + 3, observedAccountJson = null,
        )

    private fun spy(qty: String = "5"): CapturedPosition = CapturedPosition(
        symbol = "SPY", side = BrokerPositionSide.LONG, qtyRawDecimal = qty,
        qtyCanonicalDecimal = DecimalQuantity.parse(qty).toString(),
    )

    private fun failedMetadata(id: String, refresh: String = UUID.randomUUID().toString(), started: Long = 200L):
        PaperBrokerSnapshotEntity = completeMetadata(id, refresh, started).copy(
        completeness = "FAILED",
        accountRequestOutcome = CaptureDiagnostic.HTTP_FAILURE.name,
        positionsRequestOutcome = CaptureDiagnostic.NOT_REQUESTED.name,
        diagnosticsJson = """["HTTP_FAILURE","NOT_REQUESTED"]""",
        accountHttpStatus = 500, positionsHttpStatus = null,
        accountCompletedAtEpochMillis = null, positionsCompletedAtEpochMillis = null,
        positionsReceivedCount = 0, positionsValidatedCount = 0,
    )

    private suspend fun persistComplete(
        db: InMemoryEvidenceDatabase,
        id: String = "s-1",
        refresh: String = UUID.randomUUID().toString(),
        started: Long = 100L,
        rows: List<CapturedPosition> = listOf(spy()),
    ): StoredBrokerSnapshot {
        val repo = PaperBrokerPositionSnapshotRepository(db)
        val checkpoint = repo.historyCheckpoint()
        val metadata = completeMetadata(id, refresh, started, receivedCount = rows.size).copy(localHistoryDigest = checkpoint.digest)
        return repo.persist(metadata, rows)
    }

    @Test
    fun `persist COMPLETE stores metadata and rows and returns anchor-eligible snapshot`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val stored = persistComplete(db)
        assertEquals("s-1", stored.metadata.snapshotId)
        assertEquals(1, stored.positions.size)
        assertTrue(stored.anchorEligible)
        assertEquals(1L, stored.metadata.sequence)
        assertEquals(stored.metadata, db.evidence.brokerSnapshots["s-1"])
    }

    @Test
    fun `persist COMPLETE with duplicate captured symbol is rejected before insertion`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val repository = PaperBrokerPositionSnapshotRepository(db)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.persist(completeMetadata("s-1", receivedCount = 2), listOf(spy(), spy()))
            }
        }
        assertTrue(db.evidence.brokerSnapshots.isEmpty())
        assertTrue(db.evidence.brokerPositions.isEmpty())
    }

    @Test
    fun `persist COMPLETE with FAILED rows-nonempty combo is rejected`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val repository = PaperBrokerPositionSnapshotRepository(db)
        val failed = failedMetadata("s-fail")
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.persist(failed, listOf(spy())) }
        }
    }

    @Test
    fun `persist COMPLETE with FAILED diagnostics-empty combo is rejected`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val repository = PaperBrokerPositionSnapshotRepository(db)
        val bad = failedMetadata("s-fail").copy(diagnosticsJson = "[]")
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.persist(bad, emptyList()) }
        }
    }

    @Test
    fun `persist COMPLETE with empty rows is allowed as zero portfolio`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val repository = PaperBrokerPositionSnapshotRepository(db)
        val stored = repository.persist(completeMetadata("s-empty", receivedCount = 0), emptyList())
        assertEquals("COMPLETE", stored.metadata.completeness)
        assertEquals(0, stored.positions.size)
    }

    @Test
    fun `persist COMPLETE without account ref is refused`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val repository = PaperBrokerPositionSnapshotRepository(db)
        val noRef = completeMetadata("s-no-ref").copy(accountRef = null)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.persist(noRef, listOf(spy())) }
        }
    }

    @Test
    fun `latest complete is deterministic by sequence and ignores failed attempts`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val repository = PaperBrokerPositionSnapshotRepository(db)
        val first = persistComplete(db, "s-1")
        val failed = repository.persist(failedMetadata("s-2"), emptyList())
        val second = persistComplete(db, "s-3", started = 300L, rows = listOf(spy(qty = "6")))
        // sequence must be strictly increasing across ALL persists (including failed).
        assertTrue(failed.metadata.sequence > first.metadata.sequence)
        assertTrue(second.metadata.sequence > failed.metadata.sequence)
        val latest = repository.latestComplete(accountRef)
        assertEquals(second.metadata.snapshotId, latest!!.metadata.snapshotId)
    }

    @Test
    fun `duplicate manualRefreshId is refused`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val a = persistComplete(db, "s-a", refresh = "same")
        assertNotNull(a)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                persistComplete(db, "s-b", refresh = "same", started = 500L)
            }
        }
    }

    @Test
    fun `stored raw decimal round-trips distinct from canonical`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val stored = persistComplete(db, rows = listOf(spy(qty = "1.0000")))
        assertEquals("1.0000", stored.positions.single().qtyRawDecimal)
        assertEquals("1", stored.positions.single().qtyCanonicalDecimal)
    }

    // ---- Anchor + events + supersede ----

    private suspend fun makeSnapshot(db: InMemoryEvidenceDatabase, id: String = "s-1"): StoredBrokerSnapshot =
        persistComplete(db, id)

    private fun anchorFor(snapshot: StoredBrokerSnapshot, anchorId: String = "anchor-1",
        createdAt: Long = 500L): PositionAnchor {
        val meta = snapshot.metadata
        return PositionAnchor(
            anchorId = anchorId, symbol = "SPY", accountRef = accountRef,
            baselineQty = QuantityEvidence(DecimalQuantity.parse("5"), DecimalProvenance.EXACT_DECIMAL),
            cut = AnchorCoverageCut(meta.orderSequenceInclusive, meta.lifecycleSequenceInclusive, CutAssurance.CONFIRMED),
            cursors = emptyList(), status = AnchorStatus.ACTIVE, createdAtEpochMillis = createdAt,
            invalidatedAtEpochMillis = null, invalidationReason = null,
        )
    }

    @Test
    fun `create anchor persists metadata cursors and CREATED event`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val snapshot = makeSnapshot(db)
        val anchorRepo = PaperPositionAnchorRepository(db)
        val stored = anchorRepo.createAnchor(anchorFor(snapshot), snapshot.metadata.snapshotId, CutAssurance.CONFIRMED)
        assertEquals("anchor-1", stored.metadata.anchorId)
        assertEquals("ACTIVE", stored.metadata.status)
        val events = anchorRepo.events("anchor-1")
        assertEquals(1, events.size)
        assertEquals("CREATED", events.single().type)
        assertEquals(1L, events.single().version)
    }

    @Test
    fun `second active anchor for same symbol+account is refused`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val snapshot = makeSnapshot(db)
        val anchorRepo = PaperPositionAnchorRepository(db)
        anchorRepo.createAnchor(anchorFor(snapshot, "a-1"), snapshot.metadata.snapshotId, CutAssurance.CONFIRMED)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                anchorRepo.createAnchor(anchorFor(snapshot, "a-2"), snapshot.metadata.snapshotId, CutAssurance.CONFIRMED)
            }
        }
    }

    @Test
    fun `invalidate anchor transitions to INVALIDATED and appends event append-only`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val snapshot = makeSnapshot(db)
        val anchorRepo = PaperPositionAnchorRepository(db)
        anchorRepo.createAnchor(anchorFor(snapshot), snapshot.metadata.snapshotId, CutAssurance.CONFIRMED)
        val invalidated = anchorRepo.invalidateAnchor("anchor-1", 800L, AnchorInvalidationReason.MANUAL)
        assertEquals("INVALIDATED", invalidated.metadata.status)
        assertNull(invalidated.metadata.activeKey)
        assertEquals(800L, invalidated.metadata.invalidatedAtEpochMillis)
        val events = anchorRepo.events("anchor-1")
        assertEquals(listOf("CREATED", "INVALIDATED"), events.map { it.type })
        assertEquals(listOf(1L, 2L), events.map { it.version })
        // A second invalidate is refused because the anchor is not ACTIVE any more.
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                anchorRepo.invalidateAnchor("anchor-1", 900L, AnchorInvalidationReason.MANUAL)
            }
        }
    }

    @Test
    fun `supersede anchor creates replacement and appends SUPERSEDED event`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val snapshot = makeSnapshot(db)
        val anchorRepo = PaperPositionAnchorRepository(db)
        anchorRepo.createAnchor(anchorFor(snapshot, "old"), snapshot.metadata.snapshotId, CutAssurance.CONFIRMED)
        val replacement = anchorFor(snapshot, "new", createdAt = 900L)
        val newStored = anchorRepo.supersedeAnchor("old", replacement, snapshot.metadata.snapshotId, CutAssurance.CONFIRMED)
        assertEquals("new", newStored.metadata.anchorId)
        assertEquals("ACTIVE", newStored.metadata.status)
        // Old anchor now shows two events, ends up SUPERSEDED; history preserves both anchors.
        val oldEvents = anchorRepo.events("old")
        assertEquals(listOf("CREATED", "SUPERSEDED"), oldEvents.map { it.type })
        val history = anchorRepo.getAnchorHistory("SPY", accountRef)
        assertEquals(listOf("old", "new"), history.map { it.metadata.anchorId })
    }

    // ---- Reports + replay ----

    @Test
    fun `report create persists rows and can be re-read without running today's engine`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val snapshot = makeSnapshot(db)
        val anchorRepo = PaperPositionAnchorRepository(db)
        anchorRepo.createAnchor(anchorFor(snapshot), snapshot.metadata.snapshotId, CutAssurance.CONFIRMED)
        val reportRepo = PaperPositionReconciliationReportRepository(db)
        val stored = reportRepo.createReport("report-1", snapshot.metadata.snapshotId, listOf("anchor-1"),
            CutAssurance.CONFIRMED, SnapshotFreshness.FRESH, CutAssurance.CONFIRMED, createdAt = 1_000L)
        assertEquals("report-1", stored.metadata.reportId)
        assertEquals(POSITION_ENGINE_V1, stored.metadata.engineVersion)
        assertEquals(POSITION_POLICY_V1, stored.metadata.policyVersion)
        val reread = reportRepo.get("report-1")
        assertEquals(stored, reread)
    }

    @Test
    fun `report immutability - second create returns a distinct report id`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val snapshot = makeSnapshot(db)
        val anchorRepo = PaperPositionAnchorRepository(db)
        anchorRepo.createAnchor(anchorFor(snapshot), snapshot.metadata.snapshotId, CutAssurance.CONFIRMED)
        val reportRepo = PaperPositionReconciliationReportRepository(db)
        val first = reportRepo.createReport("r-1", snapshot.metadata.snapshotId, listOf("anchor-1"),
            CutAssurance.CONFIRMED, SnapshotFreshness.FRESH, CutAssurance.CONFIRMED, 1_000L)
        val second = reportRepo.createReport("r-2", snapshot.metadata.snapshotId, listOf("anchor-1"),
            CutAssurance.CONFIRMED, SnapshotFreshness.FRESH, CutAssurance.CONFIRMED, 2_000L)
        assertNotEquals(first.metadata.reportId, second.metadata.reportId)
        // Both remain retrievable independently.
        assertNotNull(reportRepo.get("r-1"))
        assertNotNull(reportRepo.get("r-2"))
    }

    @Test
    fun `report create refuses duplicate report id`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val snapshot = makeSnapshot(db)
        val anchorRepo = PaperPositionAnchorRepository(db)
        anchorRepo.createAnchor(anchorFor(snapshot), snapshot.metadata.snapshotId, CutAssurance.CONFIRMED)
        val reportRepo = PaperPositionReconciliationReportRepository(db)
        reportRepo.createReport("r-1", snapshot.metadata.snapshotId, listOf("anchor-1"),
            CutAssurance.CONFIRMED, SnapshotFreshness.FRESH, CutAssurance.CONFIRMED, 1_000L)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                reportRepo.createReport("r-1", snapshot.metadata.snapshotId, listOf("anchor-1"),
                    CutAssurance.CONFIRMED, SnapshotFreshness.FRESH, CutAssurance.CONFIRMED, 2_000L)
            }
        }
    }

    @Test
    fun `verifyReplay reproduces the original report exactly even after anchor is invalidated`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val snapshot = makeSnapshot(db)
        val anchorRepo = PaperPositionAnchorRepository(db)
        anchorRepo.createAnchor(anchorFor(snapshot), snapshot.metadata.snapshotId, CutAssurance.CONFIRMED)
        val reportRepo = PaperPositionReconciliationReportRepository(db)
        reportRepo.createReport("r-1", snapshot.metadata.snapshotId, listOf("anchor-1"),
            CutAssurance.CONFIRMED, SnapshotFreshness.FRESH, CutAssurance.CONFIRMED, 1_000L)
        // Later status change of the anchor MUST NOT break replay.
        anchorRepo.invalidateAnchor("anchor-1", 1_500L, AnchorInvalidationReason.MANUAL)
        assertTrue(reportRepo.verifyReplay("r-1"))
    }

    @Test
    fun `snapshot history is ordered by sequence DESC`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val repository = PaperBrokerPositionSnapshotRepository(db)
        val a = persistComplete(db, "s-1")
        val b = persistComplete(db, "s-2", started = 200L, rows = listOf(spy(qty = "6")))
        val history = repository.history()
        assertEquals(listOf(b.metadata.snapshotId, a.metadata.snapshotId), history.map { it.snapshotId })
    }

    @Test
    fun `snapshot positions are ordered by rowIndex ascending`() = runTest {
        val db = InMemoryEvidenceDatabase()
        val repository = PaperBrokerPositionSnapshotRepository(db)
        val rows = listOf(
            CapturedPosition("SPY", BrokerPositionSide.LONG, "1", "1"),
            CapturedPosition("AAPL", BrokerPositionSide.LONG, "2", "2"),
        )
        val stored = persistComplete(db, "s-1", rows = rows)
        // Deterministic sort by symbol on persist, rowIndex ascending on read.
        assertEquals(listOf("AAPL", "SPY"), stored.positions.map { it.symbol })
        val reread = repository.get("s-1")!!
        assertEquals(listOf("AAPL", "SPY"), reread.positions.map { it.symbol })
    }
}
