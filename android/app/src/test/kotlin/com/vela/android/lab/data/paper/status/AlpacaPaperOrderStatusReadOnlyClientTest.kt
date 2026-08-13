@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.paper.status

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.NoAlpacaCredentialsProvider
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlpacaPaperOrderStatusReadOnlyClientTest {

    private val id = "4b60549d-6dab-47d8-93eb-382ed1eed108"
    private val otherId = "7d9b45d4-98fb-4f39-a9f0-22c58bdeca31"
    private val credentials = AlpacaCredentials("PKSAFE123", "top-secret-value")
    private val credentialsProvider = AlpacaCredentialsProvider { credentials }

    @Test
    fun `successful fetch uses exactly one guarded GET and returns FILLED`() =
        runTest(UnconfinedTestDispatcher()) {
            val http = RecordingHttpClient(success(id))
            val client = AlpacaPaperOrderStatusReadOnlyClient(credentialsProvider, http)

            val result = client.fetchOrderStatus(id)

            assertTrue(result is AlpacaPaperOrderStatusReadOnlyClient.FetchResult.Ok)
            val snapshot =
                (result as AlpacaPaperOrderStatusReadOnlyClient.FetchResult.Ok).value
            assertEquals(PaperOrderLifecycleStatus.FILLED, snapshot.status)
            assertEquals(1, http.callCount)
            assertEquals(listOf(AlpacaPaperOrderStatusEndpoint.urlFor(id)), http.urls)
            assertEquals("PKSAFE123", http.lastKeyId)
        }

    @Test
    fun `missing credentials performs zero HTTP calls`() =
        runTest(UnconfinedTestDispatcher()) {
            val http = RecordingHttpClient(success(id))
            val client = AlpacaPaperOrderStatusReadOnlyClient(
                NoAlpacaCredentialsProvider,
                http,
            )

            val result = client.fetchOrderStatus(id)

            assertEquals(AlpacaPaperOrderStatusReadOnlyClient.FetchResult.AuthMissing, result)
            assertEquals(0, http.callCount)
        }

    @Test
    fun `invalid id fails before credentials or HTTP can be used`() =
        runTest(UnconfinedTestDispatcher()) {
            var credentialReads = 0
            val provider = AlpacaCredentialsProvider {
                credentialReads += 1
                credentials
            }
            val http = RecordingHttpClient(success(id))
            val client = AlpacaPaperOrderStatusReadOnlyClient(provider, http)

            val result = client.fetchOrderStatus("not-a-uuid")

            assertEquals(AlpacaPaperOrderStatusReadOnlyClient.FetchResult.InvalidOrderId, result)
            assertEquals(0, credentialReads)
            assertEquals(0, http.callCount)
        }

    @Test
    fun `response order id mismatch fails closed`() = runTest(UnconfinedTestDispatcher()) {
        val http = RecordingHttpClient(success(otherId))
        val client = AlpacaPaperOrderStatusReadOnlyClient(credentialsProvider, http)

        val result = client.fetchOrderStatus(id)

        assertEquals(
            AlpacaPaperOrderStatusReadOnlyClient.FetchResult.ResponseIdMismatch,
            result,
        )
        assertEquals(1, http.callCount)
    }

    @Test
    fun `HTTP network and parser errors expose no response body or credentials`() =
        runTest(UnconfinedTestDispatcher()) {
            val results = listOf(
                AlpacaPaperOrderStatusReadOnlyClient(
                    credentialsProvider,
                    RecordingHttpClient(PaperOrderStatusHttpResult.HttpError(403)),
                ).fetchOrderStatus(id),
                AlpacaPaperOrderStatusReadOnlyClient(
                    credentialsProvider,
                    RecordingHttpClient(PaperOrderStatusHttpResult.NetworkError),
                ).fetchOrderStatus(id),
                AlpacaPaperOrderStatusReadOnlyClient(
                    credentialsProvider,
                    RecordingHttpClient(
                        PaperOrderStatusHttpResult.Success(
                            200,
                            "not-json APCA-API-SECRET-KEY=top-secret-value",
                        ),
                    ),
                ).fetchOrderStatus(id),
            )

            assertTrue(results[0] is AlpacaPaperOrderStatusReadOnlyClient.FetchResult.HttpError)
            assertEquals(
                403,
                (results[0] as AlpacaPaperOrderStatusReadOnlyClient.FetchResult.HttpError)
                    .statusCode,
            )
            assertEquals(
                AlpacaPaperOrderStatusReadOnlyClient.FetchResult.NetworkError,
                results[1],
            )
            assertTrue(results[2] is AlpacaPaperOrderStatusReadOnlyClient.FetchResult.ParseError)
            results.forEach { result ->
                assertFalse(result.toString().contains("top-secret-value"))
                assertFalse(result.toString().contains("PKSAFE123"))
            }
        }

    @Test
    fun `public surfaces remain GET-only and contain no mutation-shape method`() {
        val httpMethods = AlpacaPaperOrderStatusHttpClient::class.java.declaredMethods
            .map { it.name }
            .toSet()
        assertEquals(setOf("executeGet"), httpMethods)

        val forbidden = listOf(
            "submit", "place", "cancel", "replace", "close", "delete",
            "patch", "post", "put", "retry",
        )
        val methods = AlpacaPaperOrderStatusReadOnlyClient::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
        methods.forEach { method ->
            forbidden.forEach { fragment ->
                assertFalse(
                    method.contains(fragment, ignoreCase = true),
                    "$method must not contain $fragment",
                )
            }
        }
    }

    private fun success(orderId: String): PaperOrderStatusHttpResult.Success =
        PaperOrderStatusHttpResult.Success(
            200,
            """{
              "id":"$orderId",
              "status":"filled",
              "filled_qty":"1",
              "filled_avg_price":"773.49",
              "filled_at":"2026-08-07T19:31:02Z"
            }""",
        )
}

private class RecordingHttpClient(
    private val result: PaperOrderStatusHttpResult,
) : AlpacaPaperOrderStatusHttpClient {
    var callCount: Int = 0
        private set
    val urls: MutableList<String> = mutableListOf()
    var lastKeyId: String? = null
        private set

    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): PaperOrderStatusHttpResult {
        AlpacaPaperOrderStatusEndpoint.requireSafeGet(url)
        callCount += 1
        urls += url
        lastKeyId = keyId
        return result
    }
}
