@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.paper

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.NoAlpacaCredentialsProvider
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class AlpacaPaperReadOnlyClientTest {

    private val testCreds = AlpacaCredentials("PKABCDEF1234", "topsecretvalue")
    private val creds: AlpacaCredentialsProvider = AlpacaCredentialsProvider { testCreds }

    @Test
    fun `fetchAccount returns Ok with parsed snapshot`() = runTest(UnconfinedTestDispatcher()) {
        val http = StubHttpClient.success(
            url = AlpacaPaperTradingEndpoint.ACCOUNT_URL,
            body = """{"cash":"5","buying_power":"10","equity":"15","portfolio_value":"20","status":"ACTIVE","currency":"USD"}""",
        )
        val client = AlpacaPaperReadOnlyClient(creds, http)
        val r = client.fetchAccount()
        assertTrue(r is AlpacaPaperReadOnlyClient.FetchResult.Ok)
        val snap = (r as AlpacaPaperReadOnlyClient.FetchResult.Ok).value
        assertEquals(15.0, snap.equityUsd)
    }

    @Test
    fun `fetchClock returns Ok with parsed snapshot`() = runTest(UnconfinedTestDispatcher()) {
        val http = StubHttpClient.success(
            url = AlpacaPaperTradingEndpoint.CLOCK_URL,
            body = """{"is_open":true,"next_close":"2026-06-13T20:00:00Z"}""",
        )
        val client = AlpacaPaperReadOnlyClient(creds, http)
        val r = client.fetchClock()
        assertTrue(r is AlpacaPaperReadOnlyClient.FetchResult.Ok)
        val snap = (r as AlpacaPaperReadOnlyClient.FetchResult.Ok).value
        assertTrue(snap.isOpen)
        assertEquals("2026-06-13T20:00:00Z", snap.nextCloseIso)
    }

    @Test
    fun `fetchPositions returns Ok empty list when response is empty array`() =
        runTest(UnconfinedTestDispatcher()) {
            val http = StubHttpClient.success(
                url = AlpacaPaperTradingEndpoint.POSITIONS_URL,
                body = "[]",
            )
            val client = AlpacaPaperReadOnlyClient(creds, http)
            val r = client.fetchPositions()
            assertTrue(r is AlpacaPaperReadOnlyClient.FetchResult.Ok)
            assertEquals(0, (r as AlpacaPaperReadOnlyClient.FetchResult.Ok).value.size)
        }

    @Test
    fun `missing credentials returns AuthMissing and does not issue request`() =
        runTest(UnconfinedTestDispatcher()) {
            val http = StubHttpClient.success(AlpacaPaperTradingEndpoint.ACCOUNT_URL, "{}")
            val client = AlpacaPaperReadOnlyClient(NoAlpacaCredentialsProvider, http)
            val r = client.fetchAccount()
            assertTrue(r is AlpacaPaperReadOnlyClient.FetchResult.AuthMissing)
            assertEquals(0, http.callCount)
        }

    @Test
    fun `HTTP 403 returns HttpError without crashing`() = runTest(UnconfinedTestDispatcher()) {
        val http = StubHttpClient.httpError(
            AlpacaPaperTradingEndpoint.ACCOUNT_URL, 403, """{"message":"forbidden"}""",
        )
        val client = AlpacaPaperReadOnlyClient(creds, http)
        val r = client.fetchAccount()
        assertTrue(r is AlpacaPaperReadOnlyClient.FetchResult.HttpError)
        assertEquals(403, (r as AlpacaPaperReadOnlyClient.FetchResult.HttpError).code)
    }

    @Test
    fun `network error surfaces as NetworkError`() = runTest(UnconfinedTestDispatcher()) {
        val http = StubHttpClient.networkError(AlpacaPaperTradingEndpoint.ACCOUNT_URL, "no route")
        val client = AlpacaPaperReadOnlyClient(creds, http)
        val r = client.fetchAccount()
        assertTrue(r is AlpacaPaperReadOnlyClient.FetchResult.NetworkError)
        assertEquals("no route", (r as AlpacaPaperReadOnlyClient.FetchResult.NetworkError).message)
    }

    @Test
    fun `parse error surfaces as ParseError`() = runTest(UnconfinedTestDispatcher()) {
        val http = StubHttpClient.success(AlpacaPaperTradingEndpoint.ACCOUNT_URL, "not json")
        val client = AlpacaPaperReadOnlyClient(creds, http)
        val r = client.fetchAccount()
        assertTrue(r is AlpacaPaperReadOnlyClient.FetchResult.ParseError)
    }

    @Test
    fun `the client only calls executeGet against the three allowed URLs`() =
        runTest(UnconfinedTestDispatcher()) {
            val http = MultiStubHttpClient(
                mapOf(
                    AlpacaPaperTradingEndpoint.ACCOUNT_URL to """{"status":"ACTIVE"}""",
                    AlpacaPaperTradingEndpoint.CLOCK_URL to """{"is_open":false}""",
                    AlpacaPaperTradingEndpoint.POSITIONS_URL to "[]",
                ),
            )
            val client = AlpacaPaperReadOnlyClient(creds, http)
            client.fetchAccount()
            client.fetchClock()
            client.fetchPositions()
            assertEquals(
                listOf(
                    AlpacaPaperTradingEndpoint.ACCOUNT_URL,
                    AlpacaPaperTradingEndpoint.CLOCK_URL,
                    AlpacaPaperTradingEndpoint.POSITIONS_URL,
                ),
                http.urls,
            )
            // All three sat inside the allow-list.
            for (url in http.urls) {
                assertTrue(AlpacaPaperTradingEndpoint.isSafePaperReadOnlyGet(url))
            }
        }

    @Test
    fun `credentials are not echoed in any returned FetchResult`() =
        runTest(UnconfinedTestDispatcher()) {
            val http = StubHttpClient.success(
                AlpacaPaperTradingEndpoint.ACCOUNT_URL,
                """{"status":"ACTIVE","cash":"5"}""",
            )
            val client = AlpacaPaperReadOnlyClient(creds, http)
            val r = client.fetchAccount().toString()
            assertFalse(r.contains("topsecretvalue"))
            assertFalse(r.contains("PKABCDEF1234"))
        }

    @TestFactory
    fun `AlpacaPaperReadOnlyClient declares no order trading or mutation methods`(): List<DynamicTest> {
        val forbidden = listOf(
            "submitorder", "placeorder", "place_order", "buyorder", "sellorder",
            "withdraw", "deposit", "trading", "executeorder", "executetrade",
            "cancelorder", "replaceorder", "updateaccount", "openposition",
            "closeposition", "deletepositions", "setbalance", "transferfund",
            // HTTP verbs that would imply mutation surface:
            "post", "put", "patch", "delete",
        )
        val methods = AlpacaPaperReadOnlyClient::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        return methods.map { name ->
            DynamicTest.dynamicTest("$name has no forbidden substring") {
                val lower = name.lowercase()
                for (bad in forbidden) {
                    assertFalse(
                        lower.contains(bad),
                        "client method '$name' contains forbidden substring '$bad'",
                    )
                }
            }
        }
    }

    @Test
    fun `surface contains exactly the three documented fetch entry-points`() {
        val expected = setOf("fetchAccount", "fetchClock", "fetchPositions")
        val fetchMethods = AlpacaPaperReadOnlyClient::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
            .filter { it.startsWith("fetch") }
            .toSet()
        // Every `fetch*` method must be one of the three documented
        // entry-points; extras would suggest a new (potentially
        // mutation-shaped) surface was added.
        assertTrue(
            fetchMethods.containsAll(expected),
            "Missing expected fetch methods: $expected, got: $fetchMethods",
        )
        for (name in fetchMethods) {
            assertTrue(
                name in expected,
                "Unexpected fetch method '$name' on AlpacaPaperReadOnlyClient",
            )
        }
    }

    @Test
    fun `AlpacaHttpClient interface exposes only executeGet`() {
        val methods = AlpacaHttpClient::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(setOf("executeGet"), methods)
    }
}

// --- Test doubles ----------------------------------------------------

private class StubHttpClient private constructor(
    private val expectUrl: String,
    private val result: HttpResult,
) : AlpacaHttpClient {

    var callCount: Int = 0; private set
    var lastKeyId: String? = null; private set

    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): HttpResult {
        // Mimic the production guard so test paths exercise the
        // allow-list too.
        AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
        callCount += 1
        lastKeyId = keyId
        check(url == expectUrl) { "Stub expected $expectUrl but got $url" }
        return result
    }

    companion object {
        fun success(url: String, body: String): StubHttpClient =
            StubHttpClient(url, HttpResult.Success(200, body))
        fun httpError(url: String, code: Int, body: String): StubHttpClient =
            StubHttpClient(url, HttpResult.HttpError(code, body))
        fun networkError(url: String, message: String): StubHttpClient =
            StubHttpClient(url, HttpResult.NetworkError(message))
    }
}

private class MultiStubHttpClient(
    private val responses: Map<String, String>,
) : AlpacaHttpClient {
    val urls: MutableList<String> = mutableListOf()
    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): HttpResult {
        AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
        urls += url
        val body = responses[url] ?: return HttpResult.HttpError(404, "no stub")
        return HttpResult.Success(200, body)
    }
}
