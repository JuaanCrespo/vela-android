package com.vela.android.lab.data.paper.status

import com.vela.android.lab.data.paper.submit.PaperOrderSubmitAuditRepository
import com.vela.android.lab.data.paper.submit.SubmitFakeAuditDao
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaperOrderStatusTrackerRepositoryTest {

    @Test
    fun `latest invalid submitted order never falls back to an older order`() = runTest {
        val dao = SubmitFakeAuditDao()
        dao.rows += auditRow(
            id = 1L,
            eventKey = "attempt-good:SUBMITTED",
            status = "SUBMITTED",
            orderId = VALID_ORDER_ID,
            submittedAt = 100L,
        )
        dao.rows += auditRow(
            id = 2L,
            eventKey = "attempt-failed:FAILED",
            status = "FAILED",
            orderId = null,
            submittedAt = 200L,
        )
        dao.rows += auditRow(
            id = 3L,
            eventKey = "attempt-invalid:SUBMITTED",
            status = "SUBMITTED",
            orderId = "not-a-uuid",
            submittedAt = 300L,
        )
        val tracker = PaperOrderStatusTrackerRepository(PaperOrderSubmitAuditRepository(dao))

        val restored = tracker.latestUnresolved()

        val untrackable = restored as PaperOrderTrackingRestoreResult.Untrackable
        assertEquals("attempt-invalid", untrackable.submission.submitAttemptId)
        assertEquals("SUBMITTED", untrackable.submission.auditStatus)
    }

    @Test
    fun `append-only audit id wins when host timestamp moves backwards`() = runTest {
        val dao = SubmitFakeAuditDao()
        dao.rows += auditRow(
            id = 1L,
            eventKey = "attempt-old:SUBMITTED",
            status = "SUBMITTED",
            orderId = VALID_ORDER_ID,
            submittedAt = 500L,
        )
        dao.rows += auditRow(
            id = 2L,
            eventKey = "attempt-new:SUBMITTED",
            status = "SUBMITTED",
            orderId = "not-a-uuid",
            submittedAt = 100L,
        )
        val tracker = PaperOrderStatusTrackerRepository(PaperOrderSubmitAuditRepository(dao))

        val restored = tracker.latestUnresolved()

        val untrackable = restored as PaperOrderTrackingRestoreResult.Untrackable
        assertEquals("attempt-new", untrackable.submission.submitAttemptId)
        assertEquals(100L, untrackable.submission.submittedAtEpochMillis)
    }

    @Test
    fun `safe rejected attempt is skipped but older submitted order remains tracked`() = runTest {
        val dao = SubmitFakeAuditDao()
        dao.rows += auditRow(
            id = 1L,
            eventKey = "attempt-good:SUBMITTED",
            status = "SUBMITTED",
            orderId = VALID_ORDER_ID,
            submittedAt = 100L,
        )
        dao.rows += auditRow(
            id = 2L,
            eventKey = "attempt-rejected:ATTEMPT_STARTED",
            status = "ATTEMPT_STARTED",
            orderId = null,
            submittedAt = 200L,
        )
        dao.rows += auditRow(
            id = 3L,
            eventKey = "attempt-rejected:REJECTED",
            status = "REJECTED",
            orderId = null,
            submittedAt = 201L,
        )
        val tracker = PaperOrderStatusTrackerRepository(PaperOrderSubmitAuditRepository(dao))

        val restored = tracker.latestUnresolved()

        val tracked = (restored as PaperOrderTrackingRestoreResult.Trackable).order
        assertEquals(VALID_ORDER_ID, tracked.orderId)
        assertEquals("attempt-good", tracked.submitAttemptId)
    }

    @Test
    fun `incomplete or failed latest attempt restores as untrackable`() = runTest {
        for (status in listOf("ATTEMPT_STARTED", "FAILED")) {
            val dao = SubmitFakeAuditDao()
            dao.rows += auditRow(
                id = 1L,
                eventKey = "attempt-$status:$status",
                status = status,
                orderId = null,
                submittedAt = 100L,
            )
            val tracker = PaperOrderStatusTrackerRepository(PaperOrderSubmitAuditRepository(dao))

            val restored = tracker.latestUnresolved()

            val untrackable = restored as PaperOrderTrackingRestoreResult.Untrackable
            assertEquals(status, untrackable.submission.auditStatus)
        }
    }

    private fun auditRow(
        id: Long,
        eventKey: String,
        status: String,
        orderId: String?,
        submittedAt: Long,
    ): PaperOrderSubmitAuditEntity = PaperOrderSubmitAuditEntity(
        id = id,
        eventKey = eventKey,
        submitAttemptId = eventKey.substringBefore(':'),
        previewId = "preview-$id",
        linkedClientDryRunId = "dry-$id",
        clientOrderId = "client-$id",
        symbol = "SPY",
        side = "BUY",
        quantity = 1.0,
        orderType = "MARKET",
        timeInForce = "DAY",
        limitPriceUsd = null,
        status = status,
        alpacaOrderId = orderId,
        submittedAtEpochMillis = submittedAt,
        safeErrorMessage = null,
        priceSource = "LIVE_QUOTE_MID",
        priceFreshness = "FRESH",
        marketOpen = true,
        confirmationTokenId = "token-$id",
    )

    companion object {
        private const val VALID_ORDER_ID = "4b60549d-6dab-47d8-93eb-382ed1eed108"
    }
}
