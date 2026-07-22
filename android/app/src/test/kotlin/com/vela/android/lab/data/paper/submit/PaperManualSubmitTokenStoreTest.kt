package com.vela.android.lab.data.paper.submit

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperManualSubmitTokenStoreTest {
    @Test
    fun `requires exact contextual confirmation`() {
        val store = store()
        val preview = submitTestPreview()
        assertTrue(store.issue(preview, "submit paper spy buy 1") is PaperManualSubmitTokenIssue.Rejected)
        val issued = store.issue(preview, "SUBMIT PAPER SPY BUY 1")
        assertTrue(issued is PaperManualSubmitTokenIssue.Issued)
    }

    @Test
    fun `token is tied to preview and single-use`() {
        val store = store()
        val preview = submitTestPreview()
        val issued = store.issue(preview, PaperManualSubmitTokenStore.requiredText(preview))
            as PaperManualSubmitTokenIssue.Issued
        assertNotNull(store.consume(issued.confirmation.tokenId, preview))
        assertNull(store.consume(issued.confirmation.tokenId, preview))
    }

    @Test
    fun `preview change invalidates token`() {
        val store = store()
        val preview = submitTestPreview()
        val token = (store.issue(preview, "SUBMIT PAPER SPY BUY 1")
            as PaperManualSubmitTokenIssue.Issued).confirmation
        assertNull(store.consume(token.tokenId, preview.copy(quantity = 2.0)))
    }

    @Test
    fun `token expires quickly`() {
        var now = 10_000L
        val store = PaperManualSubmitTokenStore(
            clock = { Instant.ofEpochMilli(now) },
            tokenIdFactory = { "token" },
            ttlMillis = 1_000L,
        )
        val preview = submitTestPreview()
        val token = (store.issue(preview, "SUBMIT PAPER SPY BUY 1")
            as PaperManualSubmitTokenIssue.Issued).confirmation
        now = 11_001L
        assertNull(store.consume(token.tokenId, preview))
    }

    @Test
    fun `default token lifetime matches approved thirty seconds`() {
        val preview = submitTestPreview()
        val token = (store().issue(
            preview,
            PaperManualSubmitTokenStore.requiredText(preview),
        ) as PaperManualSubmitTokenIssue.Issued).confirmation

        assertEquals(30_000L, token.expiresAtEpochMillis - token.issuedAtEpochMillis)
        assertEquals(30_000L, PaperManualSubmitTokenStore.DEFAULT_TTL_MILLIS)
    }

    @Test
    fun `required text formats integral quantity without decimal`() {
        assertEquals("SUBMIT PAPER SPY BUY 1", PaperManualSubmitTokenStore.requiredText(
            submitTestPreview(),
        ))
    }

    private fun store(): PaperManualSubmitTokenStore = PaperManualSubmitTokenStore(
        clock = { Instant.ofEpochMilli(10_000L) },
        tokenIdFactory = { "token-submit-1" },
    )
}
