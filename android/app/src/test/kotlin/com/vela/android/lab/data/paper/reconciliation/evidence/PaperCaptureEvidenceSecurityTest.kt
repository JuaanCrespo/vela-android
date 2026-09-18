package com.vela.android.lab.data.paper.reconciliation.evidence

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.paper.AlpacaPaperTradingEndpoint
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperCaptureEvidenceSecurityTest {

    private val creds = AlpacaCredentials("KEY", "SEC")
    private val session = PaperCaptureSession(creds, "00000000-0000-0000-0000-000000000000:1", "00000000-0000-0000-0000-000000000000")

    @Test
    fun `capture endpoints are exactly the two paper read-only URLs`() {
        assertEquals(
            setOf(AlpacaPaperTradingEndpoint.ACCOUNT_URL, AlpacaPaperTradingEndpoint.POSITIONS_URL),
            PaperCaptureEndpoint.values().map { it.url }.toSet(),
        )
    }

    @Test
    fun `every capture URL passes the paper allowlist`() {
        PaperCaptureEndpoint.values().forEach {
            AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(it.url)
        }
    }

    @Test
    fun `capture URLs never target the LIVE host`() {
        PaperCaptureEndpoint.values().forEach {
            assertTrue(it.url.startsWith("https://paper-api.alpaca.markets/v2/"))
            assertFalse(it.url.contains("api.alpaca.markets/v2/") && !it.url.contains("paper-api"))
        }
    }

    @Test
    fun `capture URLs never contain mutation-shape fragments`() {
        val mutations = listOf("/orders", "/account/configurations", "/positions/{", "/account/activities")
        PaperCaptureEndpoint.values().forEach { endpoint ->
            mutations.forEach { fragment ->
                assertFalse(endpoint.url.contains(fragment), "$endpoint contains $fragment")
            }
        }
    }

    @Test
    fun `default OkHttp client disables retries and redirects and blocks proxy auth`() {
        val client = PaperPositionEvidenceHttpTransport.strictClient()
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        // Authenticator and proxyAuthenticator must both be no-auth (Authenticator.NONE).
        assertEquals(okhttp3.Authenticator.NONE, client.authenticator)
        assertEquals(okhttp3.Authenticator.NONE, client.proxyAuthenticator)
    }

    @Test
    fun `transport rejects a call without credentials as AUTH_FAILURE without touching the network`() = runTest {
        val touched = mutableListOf<Request>()
        val calls = Call.Factory { request ->
            touched += request
            error("must not be called")
        }
        val transport = PaperPositionEvidenceHttpTransport(testCalls = calls)
        val noCreds = PaperCaptureSession(null, session.configRef, session.sessionRef)
        val response = transport.get(PaperCaptureEndpoint.ACCOUNT, noCreds)
        assertEquals(CaptureDiagnostic.AUTH_FAILURE, response.failure)
        assertTrue(touched.isEmpty(), "no network call must happen when credentials are absent")
    }

    @Test
    fun `transport maps 401 and 403 to AUTH_FAILURE without exposing body`() = runTest {
        listOf(401, 403).forEach { code ->
            val calls = fakeCalls(code, """{"message":"bad key"}""")
            val transport = PaperPositionEvidenceHttpTransport(testCalls = calls)
            val response = transport.get(PaperCaptureEndpoint.ACCOUNT, session)
            assertEquals(CaptureDiagnostic.AUTH_FAILURE, response.failure)
            assertEquals(null, response.body)
        }
    }

    @Test
    fun `transport maps 5xx to HTTP_FAILURE without exposing body`() = runTest {
        val calls = fakeCalls(503, "server down")
        val transport = PaperPositionEvidenceHttpTransport(testCalls = calls)
        val response = transport.get(PaperCaptureEndpoint.POSITIONS, session)
        assertEquals(CaptureDiagnostic.HTTP_FAILURE, response.failure)
        assertEquals(null, response.body)
    }

    @Test
    fun `transport 200 exposes the body but keeps toString redacted`() = runTest {
        val calls = fakeCalls(200, "[]")
        val transport = PaperPositionEvidenceHttpTransport(testCalls = calls)
        val response = transport.get(PaperCaptureEndpoint.POSITIONS, session)
        assertEquals("[]", response.body)
        assertFalse(response.toString().contains("["), "toString must redact body")
        assertTrue(response.toString().contains("REDACTED"))
    }

    @Test
    fun `transport treats IOException as NETWORK_FAILURE`() = runTest {
        val calls = Call.Factory { request ->
            val call = object : Call {
                override fun cancel() {}
                override fun clone(): Call = this
                override fun enqueue(responseCallback: okhttp3.Callback) {
                    responseCallback.onFailure(this, IOException("boom"))
                }
                override fun execute(): Response = throw IOException("boom")
                override fun isCanceled(): Boolean = false
                override fun isExecuted(): Boolean = false
                override fun request(): Request = request
                override fun timeout(): okio.Timeout = okio.Timeout.NONE
            }
            call
        }
        val transport = PaperPositionEvidenceHttpTransport(testCalls = calls)
        val response = transport.get(PaperCaptureEndpoint.ACCOUNT, session)
        assertEquals(CaptureDiagnostic.NETWORK_FAILURE, response.failure)
    }

    @Test
    fun `session toString does not leak credentials`() {
        val text = session.toString()
        assertFalse(text.contains(creds.keyId), "keyId leaked in $text")
        assertFalse(text.contains(creds.secret), "secret leaked in $text")
    }

    @Test
    fun `capture http response toString redacts body regardless of content`() {
        val body = "secret-account-body"
        val response = CaptureHttpResponse(200, body)
        assertFalse(response.toString().contains(body))
    }

    private fun fakeCalls(code: Int, body: String): Call.Factory = Call.Factory { request ->
        val response = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message(if (code in 200..299) "OK" else "ERR")
            .body(body.toResponseBody("application/json".toMediaType())).build()
        object : Call {
            var executed = false
            override fun cancel() {}
            override fun clone(): Call = this
            override fun enqueue(responseCallback: okhttp3.Callback) { responseCallback.onResponse(this, response) }
            override fun execute(): Response { executed = true; return response }
            override fun isCanceled(): Boolean = false
            override fun isExecuted(): Boolean = executed
            override fun request(): Request = request
            override fun timeout(): okio.Timeout = okio.Timeout.NONE
        }
    }
}
