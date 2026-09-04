package com.vela.android.lab.data.paper.submit

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperManualOrderSubmitClientTest {
    @Test
    fun `valid request sends exactly one Paper POST body and parses order id`() = runTest {
        val http = SubmitFakeHttpClient()
        val result = client(http).submitOnce(submitTestRequest())

        assertEquals(1, http.callCount)
        assertEquals(AlpacaPaperSubmitEndpoint.ORDERS_URL, http.lastUrl)
        val body = JSONObject(http.lastBody!!)
        assertEquals("SPY", body.getString("symbol"))
        assertEquals("buy", body.getString("side"))
        assertEquals("market", body.getString("type"))
        assertEquals("1.0", body.getString("qty"))
        assertEquals("day", body.getString("time_in_force"))
        assertEquals("vela-client-submit-1", body.getString("client_order_id"))
        assertFalse(body.has("key_id"))
        assertFalse(body.has("secret"))
        assertEquals(PaperOrderSubmitStatus.SUBMITTED, result.status)
        assertEquals("paper-order-1", result.alpacaOrderId)
        assertEquals(200, result.httpStatusCode)
        assertNull(result.initialAlpacaStatus)
        assertNull(result.alpacaSubmittedAtIso)
        assertNull(result.safeErrorMessage)
    }

    @Test
    fun `submit response metadata is captured without changing one-shot semantics`() = runTest {
        val http = SubmitFakeHttpClient(
            PaperSubmitHttpResult.Success(
                201,
                """{"id":"paper-order-1","status":"accepted","submitted_at":"2026-08-07T19:30:00Z"}""",
            ),
        )

        val result = client(http).submitOnce(submitTestRequest())

        assertEquals(PaperOrderSubmitStatus.SUBMITTED, result.status)
        assertEquals(201, result.httpStatusCode)
        assertEquals("accepted", result.initialAlpacaStatus)
        assertEquals("2026-08-07T19:30:00Z", result.alpacaSubmittedAtIso)
        assertEquals(1, http.callCount)
    }

    @Test
    fun `missing or malformed optional metadata does not change successful submit result`() = runTest {
        val http = SubmitFakeHttpClient(
            PaperSubmitHttpResult.Success(
                200,
                """{"id":"paper-order-1","status":"bad status","submitted_at":"not-time"}""",
            ),
        )

        val result = client(http).submitOnce(submitTestRequest())

        assertEquals(PaperOrderSubmitStatus.SUBMITTED, result.status)
        assertEquals(200, result.httpStatusCode)
        assertNull(result.initialAlpacaStatus)
        assertNull(result.alpacaSubmittedAtIso)
        assertEquals(1, http.callCount)
    }

    @Test
    fun `provider rejection becomes sanitized REJECTED result`() = runTest {
        val http = SubmitFakeHttpClient(
            PaperSubmitHttpResult.HttpError(
                422,
                """{"message":"APCA-API-SECRET-KEY=do-not-leak invalid order"}""",
            ),
        )
        val result = client(http).submitOnce(submitTestRequest())
        assertEquals(PaperOrderSubmitStatus.REJECTED, result.status)
        assertEquals(PaperOrderSubmitError.HTTP_REJECTED, result.errorCode)
        assertEquals(422, result.httpStatusCode)
        assertNull(result.initialAlpacaStatus)
        assertNull(result.alpacaSubmittedAtIso)
        assertFalse(result.safeErrorMessage!!.contains("do-not-leak"))
        assertEquals(1, http.callCount)
    }

    @Test
    fun `network failure is safe and never retries`() = runTest {
        val http = SubmitFakeHttpClient(PaperSubmitHttpResult.NetworkError)
        val result = client(http).submitOnce(submitTestRequest())
        assertEquals(PaperOrderSubmitStatus.FAILED, result.status)
        assertEquals(PaperOrderSubmitError.NETWORK_FAILURE, result.errorCode)
        assertNull(result.httpStatusCode)
        assertEquals(1, http.callCount)
    }

    @Test
    fun `missing order id becomes parse failure`() = runTest {
        val http = SubmitFakeHttpClient(PaperSubmitHttpResult.Success(200, "{}"))
        val result = client(http).submitOnce(submitTestRequest())
        assertEquals(PaperOrderSubmitStatus.FAILED, result.status)
        assertEquals(PaperOrderSubmitError.RESPONSE_PARSE_FAILED, result.errorCode)
        assertTrue(result.alpacaOrderId == null)
        assertEquals(200, result.httpStatusCode)
    }

    private fun client(http: AlpacaPaperOrderSubmitHttpClient): PaperManualOrderSubmitClient =
        PaperManualOrderSubmitClient(http, clock = { Instant.ofEpochMilli(SUBMIT_TEST_NOW) })
}
