package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.db.room.dao.PaperOrderSubmitAuditDao
import com.vela.android.lab.db.room.entities.PaperOrderSubmitAuditEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperOrderSubmitAuditRepositoryTest {
    @Test
    fun `records append-only start and success without sensitive fields`() = runTest {
        val dao = SubmitFakeAuditDao()
        val repository = PaperOrderSubmitAuditRepository(dao)
        val request = submitTestRequest()
        val preview = submitTestPreview()
        repository.recordAttemptStarted(request, preview, true, 9_900L)
        repository.recordResult(
            request,
            preview,
            true,
            PaperOrderSubmitResult(
                request.submitAttemptId,
                request.previewId,
                PaperOrderSubmitStatus.SUBMITTED,
                "paper-order-1",
                request.clientOrderId,
                SUBMIT_TEST_NOW,
                null,
                null,
            ),
        )
        assertEquals(listOf("ATTEMPT_STARTED", "SUBMITTED"), dao.rows.map { it.status })
        assertTrue(repository.hasAttemptForPreview(request.previewId))
        assertTrue(repository.hasClientOrderId(request.clientOrderId))
        assertNull(dao.rows.first().alpacaOrderId)
    }

    @Test
    fun `records sanitized failure terminal event`() = runTest {
        val dao = SubmitFakeAuditDao()
        val repository = PaperOrderSubmitAuditRepository(dao)
        val request = submitTestRequest()
        repository.recordResult(
            request,
            submitTestPreview(),
            true,
            PaperOrderSubmitResult(
                request.submitAttemptId,
                request.previewId,
                PaperOrderSubmitStatus.FAILED,
                null,
                request.clientOrderId,
                SUBMIT_TEST_NOW,
                PaperOrderSubmitError.NETWORK_FAILURE,
                "safe failure",
            ),
        )
        assertEquals("FAILED", dao.rows.single().status)
        assertEquals("safe failure", dao.rows.single().safeErrorMessage)
    }

    @Test
    fun `DAO surface has insert and reads only`() {
        val methodNames = PaperOrderSubmitAuditDao::class.java.declaredMethods
            .map { it.name }.toSet()
        assertEquals(
            setOf(
                "insert", "countAll", "recent", "recentBySymbol", "byAttemptId",
                "countByPreviewId", "countByClientOrderId",
            ),
            methodNames,
        )
        val forbidden = listOf("update", "delete", "clear", "cancel", "replace", "close")
        assertFalse(methodNames.any { method ->
            forbidden.any { method.contains(it, ignoreCase = true) }
        })
    }

    @Test
    fun `audit entity field names contain no credential account or header shape`() {
        val forbidden = listOf(
            "secret", "apikey", "apca", "credential", "password", "authorization",
            "header", "accountid", "rawbody",
        )
        val fields = PaperOrderSubmitAuditEntity::class.java.declaredFields.map { it.name }
        for (field in fields) {
            for (bad in forbidden) {
                assertFalse(field.contains(bad, ignoreCase = true), "$field contains $bad")
            }
        }
    }
}
