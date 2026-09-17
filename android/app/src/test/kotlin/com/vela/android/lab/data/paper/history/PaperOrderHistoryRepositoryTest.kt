package com.vela.android.lab.data.paper.history

import com.vela.android.lab.db.room.dao.PaperOrderHistoryDao
import com.vela.android.lab.db.room.entities.PaperOrderDryRunAuditEntity
import com.vela.android.lab.db.room.entities.PaperOrderLifecycleObservationEntity
import com.vela.android.lab.db.room.entities.PaperOrderReconciliationEntity
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PaperOrderHistoryRepositoryTest {
    @Test
    fun `complete order reconstructs canonical identity lifecycle fill reset and decision`() = runTest {
        val fixture = fixture()
        val record = fixture.repository.getByAttemptId(ATTEMPT_A)!!

        assertEquals(PaperHistoryIntegrityStatus.VALID, record.integrityStatus)
        assertEquals(emptyList<PaperHistoryIntegrityDiagnostic>(), record.integrityDiagnostics)
        assertEquals(1L, record.orderSequenceId)
        assertEquals(1L, record.auditStartRowId)
        assertEquals(2L, record.auditResultRowId)
        assertEquals(ORDER_A, record.alpacaOrderId)
        assertEquals(CLIENT_A, record.clientOrderId)
        assertEquals("SPY", record.symbol)
        assertEquals("BUY", record.side)
        assertEquals(1.0, record.quantity)
        assertEquals("MARKET", record.orderType)
        assertEquals("DAY", record.timeInForce)
        assertEquals(101L, record.dryRunAuditRowId)
        assertEquals(900L, record.decisionCreatedAtEpochMillis)
        assertEquals(201, record.submitHttpStatusCode)
        assertEquals("accepted", record.initialAlpacaStatus)
        assertEquals(SUBMITTED_AT, record.alpacaSubmittedAtIso)
        assertEquals(listOf(1L, 2L), record.lifecycleObservations.map { it.databaseId })
        assertEquals("FILLED", record.currentLifecycle?.status)
        assertEquals(1.0, record.currentLifecycle?.filledQuantity)
        assertEquals(770.27, record.currentLifecycle?.filledAveragePriceUsd)
        assertEquals(FILLED_AT, record.currentLifecycle?.filledAtIso)
        assertEquals(5_000L, record.resetAcknowledgedAtEpochMillis)
        assertTrue(record.resolved)
        assertFalse(record.unresolved)
        assertFalse(record.ambiguous)
        assertEquals(1.0, record.expectedSignedPositionDelta)
    }

    @Test
    fun `legacy missing submit metadata is warning and never corruption`() = runTest {
        val fixture = fixture()
        fixture.dao.audits.replaceAll { row ->
            if (row.id == 2L) row.copy(
                submitHttpStatusCode = null,
                initialAlpacaStatus = null,
                alpacaSubmittedAtIso = null,
            ) else row
        }

        val record = fixture.repository.getByAttemptId(ATTEMPT_A)!!

        assertEquals(PaperHistoryIntegrityStatus.VALID_WITH_WARNINGS, record.integrityStatus)
        assertEquals(
            listOf(PaperHistoryIntegrityDiagnostic.LEGACY_SUBMIT_METADATA_UNKNOWN),
            record.integrityDiagnostics,
        )
        assertNull(record.submitHttpStatusCode)
        assertTrue(record.resolved)
    }

    @Test
    fun `identity audit lifecycle and filtered queries use exact durable relations`() = runTest {
        val fixture = fixture(includeSecond = true)
        val repository = fixture.repository

        assertEquals(listOf(ATTEMPT_A), repository.getByOrderId(ORDER_A).map { it.submitAttemptId })
        assertEquals(listOf(ATTEMPT_A), repository.getByClientOrderId(CLIENT_A).map { it.submitAttemptId })
        assertEquals(ATTEMPT_A, repository.getByAuditRowId(2L)?.submitAttemptId)
        assertEquals(listOf(1L, 2L), repository.getLifecycleByAttemptId(ATTEMPT_A).map { it.databaseId })
        assertEquals(setOf(ATTEMPT_A), repository.getLifecycleByOrderId(ORDER_A).keys)
        assertEquals(listOf(ATTEMPT_A, ATTEMPT_B), repository.getTerminalOrders().map { it.submitAttemptId })
        assertEquals(listOf(ATTEMPT_A, ATTEMPT_B), repository.getFilledOrders().map { it.submitAttemptId })
        assertEquals(listOf(ATTEMPT_A), repository.getBySymbol("spy").map { it.submitAttemptId })
        assertEquals(listOf(ATTEMPT_B), repository.getBySide("sell").map { it.submitAttemptId })
        assertEquals(-2.0, repository.getByAttemptId(ATTEMPT_B)?.expectedSignedPositionDelta)
        assertNull(repository.getByAttemptId("missing"))
        assertEquals(emptyList<CanonicalPaperOrderHistory>(), repository.getBySide("hold"))
    }

    @Test
    fun `latest range and all ordering remain stable through rollback and equal timestamps`() = runTest {
        val fixture = fixture(includeSecond = true)
        val secondResultIndex = fixture.dao.audits.indexOfFirst { it.submitAttemptId == ATTEMPT_B && it.id == 4L }
        fixture.dao.audits[secondResultIndex] = fixture.dao.audits[secondResultIndex].copy(
            submittedAtEpochMillis = 1_000L,
        )

        assertEquals(listOf(ATTEMPT_A, ATTEMPT_B), fixture.repository.getAll().map { it.submitAttemptId })
        assertEquals(listOf(ATTEMPT_B), fixture.repository.getLatestN(1).map { it.submitAttemptId })
        assertEquals(
            listOf(ATTEMPT_A, ATTEMPT_B),
            fixture.repository.getWithinTimeRange(1_000L, 2_001L).map { it.submitAttemptId },
        )

        fixture.dao.audits.replaceAll { row ->
            if (row.status != ATTEMPT_STARTED) row.copy(submittedAtEpochMillis = 1_500L) else row
        }
        assertEquals(
            listOf(ATTEMPT_A, ATTEMPT_B),
            fixture.repository.getWithinTimeRange(1_500L, 1_501L).map { it.submitAttemptId },
        )
    }

    @Test
    fun `projection mismatch is reported while observations remain authoritative`() = runTest {
        val fixture = fixture()
        fixture.dao.reconciliations[ATTEMPT_A] = fixture.dao.reconciliations.getValue(ATTEMPT_A).copy(
            latestLifecycleStatus = "NEW",
            terminal = false,
            filledQuantity = 0.0,
            filledAveragePriceUsd = null,
            filledAtIso = null,
        )

        val record = fixture.repository.getByAttemptId(ATTEMPT_A)!!

        assertEquals("FILLED", record.currentLifecycle?.status)
        assertEquals(770.27, record.currentLifecycle?.filledAveragePriceUsd)
        assertEquals(PaperHistoryIntegrityStatus.INCONSISTENT, record.integrityStatus)
        assertTrue(PaperHistoryIntegrityDiagnostic.PROJECTION_MISMATCH in record.integrityDiagnostics)
    }

    @Test
    fun `terminal regression and changed terminal fill remain append-only and inconsistent`() = runTest {
        val fixture = fixture()
        fixture.dao.lifecycle += lifecycle(
            id = 3L,
            status = "NEW",
            rawStatus = "new",
            terminal = false,
            filledQuantity = 1.0,
            filledAveragePriceUsd = null,
            filledAtIso = null,
            observedAt = 3_000L,
        )
        fixture.dao.lifecycle += lifecycle(
            id = 4L,
            status = "FILLED",
            rawStatus = "filled",
            terminal = true,
            filledQuantity = 1.0,
            filledAveragePriceUsd = 771.0,
            filledAtIso = "2026-08-07T19:32:00Z",
            observedAt = 4_000L,
        )
        fixture.dao.reconciliations[ATTEMPT_A] = fixture.dao.reconciliations.getValue(ATTEMPT_A).copy(
            latestLifecycleStatus = "FILLED",
            latestLifecycleRawStatus = "filled",
            latestLifecycleObservedAtEpochMillis = 4_000L,
            terminal = true,
            filledQuantity = 1.0,
            filledAveragePriceUsd = 771.0,
            filledAtIso = "2026-08-07T19:32:00Z",
        )

        val record = fixture.repository.getByAttemptId(ATTEMPT_A)!!

        assertEquals(4, record.lifecycleObservations.size)
        assertTrue(PaperHistoryIntegrityDiagnostic.LIFECYCLE_CONTRADICTION in record.integrityDiagnostics)
        assertEquals(PaperHistoryIntegrityStatus.INCONSISTENT, record.integrityStatus)
    }

    @Test
    fun `partial regression and repeated payload are explicit without erasing observations`() = runTest {
        val fixture = fixture(lifecycleStatus = "NEW")
        fixture.dao.lifecycle += lifecycle(
            id = 3L,
            status = "PARTIALLY_FILLED",
            rawStatus = "partially_filled",
            terminal = false,
            filledQuantity = 0.5,
            observedAt = 3_000L,
        )
        fixture.dao.lifecycle += lifecycle(
            id = 4L,
            status = "NEW",
            rawStatus = "new",
            terminal = false,
            filledQuantity = 0.5,
            observedAt = 4_000L,
        )
        fixture.dao.lifecycle += lifecycle(
            id = 5L,
            status = "NEW",
            rawStatus = "new",
            terminal = false,
            filledQuantity = 0.5,
            observedAt = 5_000L,
        )
        fixture.dao.reconciliations[ATTEMPT_A] = projectionFor(fixture.dao.lifecycle.last())

        val record = fixture.repository.getByAttemptId(ATTEMPT_A)!!

        assertEquals(5, record.lifecycleObservations.size)
        assertTrue(PaperHistoryIntegrityDiagnostic.PARTIALLY_FILLED_REGRESSION in record.integrityDiagnostics)
        assertTrue(PaperHistoryIntegrityDiagnostic.REPEATED_LIFECYCLE_PAYLOAD in record.integrityDiagnostics)
        assertFalse(record.lifecycleObservations[3].samePayloadAsPrevious)
        assertTrue(record.lifecycleObservations[4].samePayloadAsPrevious)
        assertNotEquals(record.lifecycleObservations[2].payloadFingerprint, record.lifecycleObservations[3].payloadFingerprint)
        assertEquals(record.lifecycleObservations[3].payloadFingerprint, record.lifecycleObservations[4].payloadFingerprint)
    }

    @Test
    fun `duplicate order identity returns every attempt and marks each inconsistent`() = runTest {
        val fixture = fixture(includeSecond = true)
        val secondResult = fixture.dao.audits.indexOfFirst { it.submitAttemptId == ATTEMPT_B && it.id == 4L }
        fixture.dao.audits[secondResult] = fixture.dao.audits[secondResult].copy(alpacaOrderId = ORDER_A)
        fixture.dao.reconciliations[ATTEMPT_B] = fixture.dao.reconciliations.getValue(ATTEMPT_B).copy(
            alpacaOrderId = ORDER_A,
        )
        fixture.dao.lifecycle.replaceAll { row ->
            if (row.submitAttemptId == ATTEMPT_B) row.copy(alpacaOrderId = ORDER_A) else row
        }

        val records = fixture.repository.getByOrderId(ORDER_A)

        assertEquals(2, records.size)
        assertTrue(records.all { it.ambiguous })
        assertTrue(records.all {
            PaperHistoryIntegrityDiagnostic.DUPLICATE_IDENTITY in it.integrityDiagnostics
        })
    }

    @Test
    fun `canonical history DAO and repository expose no mutation surface`() {
        val forbidden = listOf(
            "insert", "update", "delete", "clear", "execute", "acknowledge",
            "reset", "cancel", "replace", "close",
        )
        val daoMethods = PaperOrderHistoryDao::class.java.declaredMethods.map { it.name }
        val repositoryMethods = PaperOrderHistoryRepository::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name }

        assertFalse(daoMethods.any { method ->
            forbidden.any { token -> method.contains(token, ignoreCase = true) }
        })
        assertFalse(repositoryMethods.any { method ->
            forbidden.any { token -> method.contains(token, ignoreCase = true) }
        })
    }

    @ParameterizedTest
    @ValueSource(strings = ["FILLED", "CANCELED", "EXPIRED", "REJECTED"])
    fun `each terminal status rejects regression without repairing evidence`(terminalStatus: String) = runTest {
        val fixture = fixture()
        val terminal = fixture.dao.lifecycle.last().copy(
            status = terminalStatus,
            rawStatus = terminalStatus.lowercase(),
        )
        val regressed = terminal.copy(id = 3L, status = "NEW", rawStatus = "new", terminal = false)
        fixture.dao.lifecycle[1] = terminal
        fixture.dao.lifecycle += regressed
        fixture.dao.reconciliations[ATTEMPT_A] = projectionFor(regressed)
        val before = fixture.dao.lifecycle.toList()

        val record = fixture.repository.getByAttemptId(ATTEMPT_A)!!

        assertEquals(listOf(PaperHistoryIntegrityDiagnostic.LIFECYCLE_CONTRADICTION), record.integrityDiagnostics)
        assertEquals(PaperHistoryIntegrityStatus.INCONSISTENT, record.integrityStatus)
        assertEquals(listOf(1L, 2L, 3L), record.lifecycleObservations.map { it.databaseId })
        assertEquals(before, fixture.dao.lifecycle)
        assertEquals("NEW", record.currentLifecycle?.status)
        assertTrue(record.ambiguous)
        assertFalse(record.resolved)
    }

    @ParameterizedTest
    @ValueSource(strings = ["quantity", "price", "filledAt", "terminalStatus"])
    fun `each changed terminal field is independently inconsistent`(field: String) = runTest {
        val fixture = fixture()
        val terminal = fixture.dao.lifecycle.last().copy(id = 3L)
        val changed = when (field) {
            "quantity" -> terminal.copy(filledQuantity = 2.0)
            "price" -> terminal.copy(filledAveragePriceUsd = 771.0)
            "filledAt" -> terminal.copy(filledAtIso = "2026-08-07T19:32:00Z")
            else -> terminal.copy(status = "CANCELED", rawStatus = "canceled")
        }
        fixture.dao.lifecycle += changed
        fixture.dao.reconciliations[ATTEMPT_A] = projectionFor(changed)

        val record = fixture.repository.getByAttemptId(ATTEMPT_A)!!

        assertEquals(listOf(PaperHistoryIntegrityDiagnostic.LIFECYCLE_CONTRADICTION), record.integrityDiagnostics)
        assertEquals(PaperHistoryIntegrityStatus.INCONSISTENT, record.integrityStatus)
        assertEquals(3, record.lifecycleObservations.size)
        assertEquals(changed.filledQuantity, record.currentLifecycle?.filledQuantity)
        assertEquals(changed.filledAveragePriceUsd, record.currentLifecycle?.filledAveragePriceUsd)
        assertEquals(changed.filledAtIso, record.currentLifecycle?.filledAtIso)
    }

    @Test
    fun `decreasing nonterminal filled quantity is independently inconsistent`() = runTest {
        val fixture = fixture(lifecycleStatus = "NEW")
        val partial = fixture.dao.lifecycle.last().copy(
            status = "PARTIALLY_FILLED", rawStatus = "partially_filled", filledQuantity = 0.75,
        )
        val decreasing = partial.copy(id = 3L, filledQuantity = 0.25)
        fixture.dao.lifecycle[1] = partial
        fixture.dao.lifecycle += decreasing
        fixture.dao.reconciliations[ATTEMPT_A] = projectionFor(decreasing)

        val record = fixture.repository.getByAttemptId(ATTEMPT_A)!!

        assertEquals(listOf(PaperHistoryIntegrityDiagnostic.LIFECYCLE_CONTRADICTION), record.integrityDiagnostics)
        assertEquals(PaperHistoryIntegrityStatus.INCONSISTENT, record.integrityStatus)
        assertEquals(listOf(0.0, 0.75, 0.25), record.lifecycleObservations.map { it.filledQuantity })
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "terminal", "quantity", "price", "filledAt"])
    fun `each projection field mismatch is detected in isolation without repair`(field: String) = runTest {
        val fixture = fixture()
        val projection = fixture.dao.reconciliations.getValue(ATTEMPT_A)
        val changed = when (field) {
            "status" -> projection.copy(latestLifecycleStatus = "NEW")
            "terminal" -> projection.copy(terminal = false)
            "quantity" -> projection.copy(filledQuantity = 0.5)
            "price" -> projection.copy(filledAveragePriceUsd = 771.0)
            else -> projection.copy(filledAtIso = "2026-08-07T19:32:00Z")
        }
        fixture.dao.reconciliations[ATTEMPT_A] = changed
        val before = fixture.dao.lifecycle.toList()

        val record = fixture.repository.getByAttemptId(ATTEMPT_A)!!

        assertEquals(listOf(PaperHistoryIntegrityDiagnostic.PROJECTION_MISMATCH), record.integrityDiagnostics)
        assertEquals(PaperHistoryIntegrityStatus.INCONSISTENT, record.integrityStatus)
        assertEquals("FILLED", record.currentLifecycle?.status)
        assertEquals(1.0, record.currentLifecycle?.filledQuantity)
        assertEquals(770.27, record.currentLifecycle?.filledAveragePriceUsd)
        assertEquals(FILLED_AT, record.currentLifecycle?.filledAtIso)
        assertEquals(changed, fixture.dao.reconciliations[ATTEMPT_A])
        assertEquals(before, fixture.dao.lifecycle)
    }

    @ParameterizedTest
    @ValueSource(longs = [1_100L, 100L])
    fun `lifecycle ingestion ids survive equal timestamps and clock rollback`(latestTimestamp: Long) = runTest {
        val fixture = fixture()
        val latest = fixture.dao.lifecycle.last().copy(observedAtEpochMillis = latestTimestamp)
        fixture.dao.lifecycle[1] = latest
        fixture.dao.lifecycle.reverse()
        fixture.dao.audits.reverse()
        fixture.dao.reconciliations[ATTEMPT_A] = projectionFor(latest)

        val record = fixture.repository.getByAttemptId(ATTEMPT_A)!!

        assertEquals(listOf(1L, 2L), record.lifecycleObservations.map { it.databaseId })
        assertEquals(1L, record.auditStartRowId)
        assertEquals(2L, record.auditResultRowId)
        assertEquals(latestTimestamp, record.currentLifecycle?.observedAtEpochMillis)
        assertEquals("FILLED", record.currentLifecycle?.status)
        assertEquals(PaperHistoryIntegrityStatus.VALID, record.integrityStatus)
    }

    @Test
    fun `new repository reconstructs from copied durable records without writes or session memory`() = runTest {
        val original = fixture(includeSecond = true)
        val expected = original.repository.getAll()
        val restored = FakeHistoryDao().apply {
            audits += original.dao.audits.map { it.copy() }
            lifecycle += original.dao.lifecycle.map { it.copy() }
            dryRuns += original.dao.dryRuns.map { it.copy() }
            reconciliations.putAll(original.dao.reconciliations.mapValues { it.value.copy() })
        }
        original.dao.audits.clear()
        original.dao.lifecycle.clear()
        original.dao.dryRuns.clear()
        original.dao.reconciliations.clear()
        val before = listOf(restored.audits.toList(), restored.lifecycle.toList(), restored.dryRuns.toList())
        val projectionsBefore = restored.reconciliations.toMap()
        val recreated = PaperOrderHistoryRepository(restored)

        assertEquals(expected, recreated.getAll())
        assertEquals(expected.first(), recreated.getByAttemptId(ATTEMPT_A))
        recreated.getByOrderId(ORDER_A)
        recreated.getByClientOrderId(CLIENT_A)
        recreated.getByAuditRowId(2L)
        recreated.getLifecycleByAttemptId(ATTEMPT_A)
        recreated.getLifecycleByOrderId(ORDER_A)
        recreated.getTerminalOrders()
        recreated.getFilledOrders()
        recreated.getBySymbol("SPY")
        recreated.getBySide("BUY")
        recreated.getWithinTimeRange(0L, 10_000L)
        recreated.getLatestN(2)
        assertEquals(before, listOf(restored.audits.toList(), restored.lifecycle.toList(), restored.dryRuns.toList()))
        assertEquals(projectionsBefore, restored.reconciliations)
    }

    private fun fixture(
        includeSecond: Boolean = false,
        lifecycleStatus: String = "FILLED",
    ): HistoryFixture {
        val dao = FakeHistoryDao()
        dao.audits += listOf(auditStart(), auditResult())
        dao.dryRuns += dryRun()
        dao.lifecycle += lifecycle(
            id = 1L,
            status = "SUBMITTED",
            rawStatus = "submitted",
            terminal = false,
            filledQuantity = 0.0,
            observedAt = 1_100L,
            source = LOCAL_SUBMIT_AUDIT,
        )
        val currentA = if (lifecycleStatus == "FILLED") {
            lifecycle(
                id = 2L,
                status = "FILLED",
                rawStatus = "filled",
                terminal = true,
                filledQuantity = 1.0,
                filledAveragePriceUsd = 770.27,
                filledAtIso = FILLED_AT,
                observedAt = 2_000L,
            )
        } else {
            lifecycle(
                id = 2L,
                status = "NEW",
                rawStatus = "new",
                terminal = false,
                filledQuantity = 0.0,
                observedAt = 2_000L,
            )
        }
        dao.lifecycle += currentA
        dao.reconciliations[ATTEMPT_A] = projectionFor(currentA).copy(
            resetAcknowledgedAtEpochMillis = if (currentA.terminal) 5_000L else null,
        )
        if (includeSecond) addSecondOrder(dao)
        return HistoryFixture(dao, PaperOrderHistoryRepository(dao))
    }

    private fun addSecondOrder(dao: FakeHistoryDao) {
        dao.audits += auditStart(
            id = 3L,
            attemptId = ATTEMPT_B,
            clientOrderId = CLIENT_B,
            symbol = "QQQ",
            side = "SELL",
            quantity = 2.0,
            dryRunId = "dry-b",
            previewId = "preview-b",
        )
        dao.audits += auditResult(
            id = 4L,
            attemptId = ATTEMPT_B,
            clientOrderId = CLIENT_B,
            orderId = ORDER_B,
            symbol = "QQQ",
            side = "SELL",
            quantity = 2.0,
            dryRunId = "dry-b",
            previewId = "preview-b",
            timestamp = 2_000L,
        )
        dao.dryRuns += dryRun(
            id = 102L,
            clientDryRunId = "dry-b",
            symbol = "QQQ",
            side = "SELL",
            quantity = 2.0,
        )
        dao.lifecycle += lifecycle(
            id = 3L,
            attemptId = ATTEMPT_B,
            orderId = ORDER_B,
            submitAuditId = 4L,
            status = "SUBMITTED",
            rawStatus = "submitted",
            terminal = false,
            filledQuantity = 0.0,
            observedAt = 2_100L,
            source = LOCAL_SUBMIT_AUDIT,
        )
        val filled = lifecycle(
            id = 4L,
            attemptId = ATTEMPT_B,
            orderId = ORDER_B,
            submitAuditId = 4L,
            status = "FILLED",
            rawStatus = "filled",
            terminal = true,
            filledQuantity = 2.0,
            filledAveragePriceUsd = 400.0,
            filledAtIso = FILLED_AT,
            observedAt = 3_000L,
        )
        dao.lifecycle += filled
        dao.reconciliations[ATTEMPT_B] = projectionFor(
            observation = filled,
            attemptId = ATTEMPT_B,
            startAuditId = 3L,
            resultAuditId = 4L,
            previewId = "preview-b",
            dryRunId = "dry-b",
            orderId = ORDER_B,
            clientOrderId = CLIENT_B,
            symbol = "QQQ",
            side = "SELL",
            quantity = 2.0,
        ).copy(resetAcknowledgedAtEpochMillis = 5_100L)
    }

    private fun auditStart(
        id: Long = 1L,
        attemptId: String = ATTEMPT_A,
        clientOrderId: String = CLIENT_A,
        symbol: String = "SPY",
        side: String = "BUY",
        quantity: Double = 1.0,
        dryRunId: String = "dry-a",
        previewId: String = "preview-a",
    ): PaperOrderSubmitAuditEntity = auditResult(
        id, attemptId, clientOrderId, null, symbol, side, quantity, dryRunId, previewId, 1_000L,
    ).copy(
        eventKey = "$attemptId:$ATTEMPT_STARTED",
        status = ATTEMPT_STARTED,
        alpacaOrderId = null,
        submitHttpStatusCode = null,
        initialAlpacaStatus = null,
        alpacaSubmittedAtIso = null,
    )

    private fun auditResult(
        id: Long = 2L,
        attemptId: String = ATTEMPT_A,
        clientOrderId: String = CLIENT_A,
        orderId: String? = ORDER_A,
        symbol: String = "SPY",
        side: String = "BUY",
        quantity: Double = 1.0,
        dryRunId: String = "dry-a",
        previewId: String = "preview-a",
        timestamp: Long = 2_000L,
    ): PaperOrderSubmitAuditEntity = PaperOrderSubmitAuditEntity(
        id = id,
        eventKey = "$attemptId:SUBMITTED",
        submitAttemptId = attemptId,
        previewId = previewId,
        linkedClientDryRunId = dryRunId,
        clientOrderId = clientOrderId,
        symbol = symbol,
        side = side,
        quantity = quantity,
        orderType = "MARKET",
        timeInForce = "DAY",
        limitPriceUsd = null,
        status = "SUBMITTED",
        alpacaOrderId = orderId,
        submittedAtEpochMillis = timestamp,
        safeErrorMessage = null,
        priceSource = "LIVE_QUOTE_MID",
        priceFreshness = "FRESH",
        marketOpen = true,
        confirmationTokenId = "token-$attemptId",
        submitHttpStatusCode = 201,
        initialAlpacaStatus = "accepted",
        alpacaSubmittedAtIso = SUBMITTED_AT,
    )

    private fun dryRun(
        id: Long = 101L,
        clientDryRunId: String = "dry-a",
        symbol: String = "SPY",
        side: String = "BUY",
        quantity: Double = 1.0,
    ): PaperOrderDryRunAuditEntity = PaperOrderDryRunAuditEntity(
        id = id,
        clientDryRunId = clientDryRunId,
        createdAtEpochMillis = 900L,
        symbol = symbol,
        side = side,
        orderType = "MARKET",
        timeInForce = "DAY",
        quantity = quantity,
        limitPriceUsd = null,
        status = "ALLOWED_DRY_RUN",
        estimatedNotionalUsd = 770.27 * quantity,
        buyingPowerAfterUsd = 10_000.0,
        allocationPercentAfter = 1.0,
        latestPriceUsedUsd = 770.27,
        priceSource = "LIVE_QUOTE_MID",
        priceFreshness = "FRESH",
        priceAgeMillis = 20L,
        latestSignalState = "NEUTRAL",
        marketOpen = true,
        blockReasonsSummary = "",
        warningsSummary = "",
        source = "MANUAL_DRY_RUN",
    )

    private fun lifecycle(
        id: Long,
        attemptId: String = ATTEMPT_A,
        orderId: String = ORDER_A,
        submitAuditId: Long = 2L,
        status: String,
        rawStatus: String,
        terminal: Boolean,
        filledQuantity: Double,
        filledAveragePriceUsd: Double? = null,
        filledAtIso: String? = null,
        observedAt: Long,
        source: String = ALPACA_GET,
    ): PaperOrderLifecycleObservationEntity = PaperOrderLifecycleObservationEntity(
        id = id,
        observationKey = "observation-$attemptId-$id",
        submitAttemptId = attemptId,
        alpacaOrderId = orderId,
        status = status,
        rawStatus = rawStatus,
        observedAtEpochMillis = observedAt,
        terminal = terminal,
        filledQuantity = filledQuantity,
        filledAveragePriceUsd = filledAveragePriceUsd,
        filledAtIso = filledAtIso,
        source = source,
        httpStatusCode = if (source == ALPACA_GET) 200 else null,
        submitAuditEntryId = submitAuditId,
    )

    private fun projectionFor(
        observation: PaperOrderLifecycleObservationEntity,
        attemptId: String = ATTEMPT_A,
        startAuditId: Long = 1L,
        resultAuditId: Long = 2L,
        previewId: String = "preview-a",
        dryRunId: String = "dry-a",
        orderId: String = ORDER_A,
        clientOrderId: String = CLIENT_A,
        symbol: String = "SPY",
        side: String = "BUY",
        quantity: Double = 1.0,
    ): PaperOrderReconciliationEntity = PaperOrderReconciliationEntity(
        submitAttemptId = attemptId,
        attemptStartedAuditEntryId = startAuditId,
        submitResultAuditEntryId = resultAuditId,
        previewId = previewId,
        linkedClientDryRunId = dryRunId,
        alpacaOrderId = orderId,
        clientOrderId = clientOrderId,
        symbol = symbol,
        side = side,
        quantity = quantity,
        orderType = "MARKET",
        timeInForce = "DAY",
        limitPriceUsd = null,
        submittedAtEpochMillis = if (attemptId == ATTEMPT_A) 2_000L else 2_000L,
        localSubmitResult = "SUBMITTED",
        mappingStatus = "EXACT",
        mappingDiagnostic = null,
        latestLifecycleStatus = observation.status,
        latestLifecycleRawStatus = observation.rawStatus,
        latestLifecycleObservedAtEpochMillis = observation.observedAtEpochMillis,
        terminal = observation.terminal,
        filledQuantity = observation.filledQuantity,
        filledAveragePriceUsd = observation.filledAveragePriceUsd,
        filledAtIso = observation.filledAtIso,
        lifecycleSource = observation.source,
        lifecycleHttpStatusCode = observation.httpStatusCode,
        resetAcknowledgedAtEpochMillis = null,
    )

    private data class HistoryFixture(
        val dao: FakeHistoryDao,
        val repository: PaperOrderHistoryRepository,
    )

    private class FakeHistoryDao : PaperOrderHistoryDao {
        val audits = mutableListOf<PaperOrderSubmitAuditEntity>()
        val reconciliations = linkedMapOf<String, PaperOrderReconciliationEntity>()
        val dryRuns = mutableListOf<PaperOrderDryRunAuditEntity>()
        val lifecycle = mutableListOf<PaperOrderLifecycleObservationEntity>()

        override suspend fun allAuditAttemptIds(): List<String> = audits.groupBy {
            it.submitAttemptId
        }.entries.sortedWith(
            compareBy<Map.Entry<String, List<PaperOrderSubmitAuditEntity>>> {
                it.value.minOf(PaperOrderSubmitAuditEntity::id)
            }.thenBy { it.key },
        ).map { it.key }

        override suspend fun allReconciliationAttemptIds(): List<String> =
            reconciliations.keys.sorted()

        override suspend fun attemptIdsByOrderId(orderId: String): List<String> =
            orderedAuditAttempts(audits.filter { it.alpacaOrderId == orderId })

        override suspend fun attemptIdsByClientOrderId(clientOrderId: String): List<String> =
            orderedAuditAttempts(audits.filter { it.clientOrderId == clientOrderId })

        override suspend fun auditById(auditRowId: Long): PaperOrderSubmitAuditEntity? =
            audits.singleOrNull { it.id == auditRowId }

        override suspend fun auditsByAttemptId(
            attemptId: String,
        ): List<PaperOrderSubmitAuditEntity> = audits.filter {
            it.submitAttemptId == attemptId
        }.sortedBy(PaperOrderSubmitAuditEntity::id)

        override suspend fun reconciliationByAttemptId(
            attemptId: String,
        ): PaperOrderReconciliationEntity? = reconciliations[attemptId]

        override suspend fun dryRunByClientId(
            clientDryRunId: String,
        ): PaperOrderDryRunAuditEntity? = dryRuns.singleOrNull {
            it.clientDryRunId == clientDryRunId
        }

        override suspend fun lifecycleByAttemptId(
            attemptId: String,
        ): List<PaperOrderLifecycleObservationEntity> = lifecycle.filter {
            it.submitAttemptId == attemptId
        }.sortedBy(PaperOrderLifecycleObservationEntity::id)

        override suspend fun terminalCandidateAttemptIds(): List<String> =
            orderedLifecycleAttempts(lifecycle.filter(PaperOrderLifecycleObservationEntity::terminal))

        override suspend fun filledCandidateAttemptIds(): List<String> =
            orderedLifecycleAttempts(lifecycle.filter { it.status == "FILLED" })

        override suspend fun attemptIdsBySymbol(symbol: String): List<String> =
            orderedAuditAttempts(audits.filter { it.symbol == symbol })

        override suspend fun attemptIdsBySide(side: String): List<String> =
            orderedAuditAttempts(audits.filter { it.side == side })

        override suspend fun attemptIdsWithinSubmitResultTimeRange(
            startInclusive: Long,
            endExclusive: Long,
        ): List<String> = audits.filter {
            it.status != ATTEMPT_STARTED &&
                it.submittedAtEpochMillis >= startInclusive &&
                it.submittedAtEpochMillis < endExclusive
        }.groupBy(PaperOrderSubmitAuditEntity::submitAttemptId)
            .entries.sortedWith(
                compareBy<Map.Entry<String, List<PaperOrderSubmitAuditEntity>>> {
                    it.value.maxOf(PaperOrderSubmitAuditEntity::id)
                }.thenBy { it.key },
            ).map { it.key }

        override suspend fun latestAttemptIds(limit: Int): List<String> {
            val grouped = audits.groupBy(PaperOrderSubmitAuditEntity::submitAttemptId)
            return grouped.keys.sortedWith { left, right ->
                val leftRows = grouped.getValue(left)
                val rightRows = grouped.getValue(right)
                val leftResult = leftRows.filter { it.status != ATTEMPT_STARTED }
                    .maxOfOrNull(PaperOrderSubmitAuditEntity::id) ?: 0L
                val rightResult = rightRows.filter { it.status != ATTEMPT_STARTED }
                    .maxOfOrNull(PaperOrderSubmitAuditEntity::id) ?: 0L
                when {
                    leftResult != rightResult -> rightResult.compareTo(leftResult)
                    leftRows.minOf(PaperOrderSubmitAuditEntity::id) !=
                        rightRows.minOf(PaperOrderSubmitAuditEntity::id) ->
                        rightRows.minOf(PaperOrderSubmitAuditEntity::id)
                            .compareTo(leftRows.minOf(PaperOrderSubmitAuditEntity::id))
                    else -> right.compareTo(left)
                }
            }.take(limit)
        }

        private fun orderedAuditAttempts(
            rows: List<PaperOrderSubmitAuditEntity>,
        ): List<String> = rows.groupBy(PaperOrderSubmitAuditEntity::submitAttemptId)
            .entries.sortedWith(
                compareBy<Map.Entry<String, List<PaperOrderSubmitAuditEntity>>> {
                    it.value.minOf(PaperOrderSubmitAuditEntity::id)
                }.thenBy { it.key },
            ).map { it.key }

        private fun orderedLifecycleAttempts(
            rows: List<PaperOrderLifecycleObservationEntity>,
        ): List<String> = rows.groupBy(PaperOrderLifecycleObservationEntity::submitAttemptId)
            .entries.sortedWith(
                compareBy<Map.Entry<String, List<PaperOrderLifecycleObservationEntity>>> {
                    it.value.minOf(PaperOrderLifecycleObservationEntity::id)
                }.thenBy { it.key },
            ).map { it.key }
    }

    private companion object {
        const val ATTEMPT_STARTED = "ATTEMPT_STARTED"
        const val ATTEMPT_A = "attempt-a"
        const val ATTEMPT_B = "attempt-b"
        const val ORDER_A = "3f06e628-ed14-451f-a985-6ff590018243"
        const val ORDER_B = "a4b60549-6dab-47d8-93eb-382ed1eed108"
        const val CLIENT_A = "client-a"
        const val CLIENT_B = "client-b"
        const val LOCAL_SUBMIT_AUDIT = "LOCAL_SUBMIT_AUDIT"
        const val ALPACA_GET = "ALPACA_PAPER_ORDER_GET"
        const val SUBMITTED_AT = "2026-08-07T19:30:00Z"
        const val FILLED_AT = "2026-08-07T19:31:00Z"
    }
}
