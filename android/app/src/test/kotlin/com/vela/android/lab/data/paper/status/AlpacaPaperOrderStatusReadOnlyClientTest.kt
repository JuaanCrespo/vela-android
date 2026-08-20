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
    private val credentials = AlpacaCredentials("PKSAFE123", "top-secret-value")
    private val credentialsProvider = AlpacaCredentialsProvider { credentials }

    @Test
    fun successfulFetchUsesOneGuardedGetAndReturnsCompleteIdentityEvidence() =
        runTest(UnconfinedTestDispatcher()) {
            val http = RecordingStatusHttpClient(success(VALID_TARGET))
            val client = AlpacaPaperOrderStatusReadOnlyClient(credentialsProvider, http)

            val result = client.fetchOrderStatus(VALID_TARGET)
                as AlpacaPaperOrderStatusReadOnlyClient.FetchResult.Ok

            assertEquals(PaperOrderLifecycleStatus.FILLED, result.value.status)
            assertEquals("client-1", result.value.clientOrderId)
            assertEquals(200, result.evidence.httpStatusCode)
            assertEquals(PaperOrderStatusFetchEvidence.SOURCE, result.evidence.source)
            assertEquals(1, http.callCount)
            assertEquals(
                listOf(AlpacaPaperOrderStatusEndpoint.urlFor(VALID_TARGET.orderId)),
                http.urls,
            )
        }

    @Test
    fun missingCredentialsAndInvalidUuidPerformZeroHttpCalls() =
        runTest(UnconfinedTestDispatcher()) {
            val missingHttp = RecordingStatusHttpClient(success(VALID_TARGET))
            val missing = AlpacaPaperOrderStatusReadOnlyClient(
                NoAlpacaCredentialsProvider,
                missingHttp,
            ).fetchOrderStatus(VALID_TARGET)
            assertEquals(AlpacaPaperOrderStatusReadOnlyClient.FetchResult.AuthMissing, missing)
            assertEquals(0, missingHttp.callCount)

            var credentialReads = 0
            val invalidHttp = RecordingStatusHttpClient(success(VALID_TARGET))
            val invalid = AlpacaPaperOrderStatusReadOnlyClient(
                AlpacaCredentialsProvider {
                    credentialReads += 1
                    credentials
                },
                invalidHttp,
            ).fetchOrderStatus(VALID_TARGET.copy(orderId = "not-a-uuid"))
            assertEquals(AlpacaPaperOrderStatusReadOnlyClient.FetchResult.InvalidOrderId, invalid)
            assertEquals(0, credentialReads)
            assertEquals(0, invalidHttp.callCount)
        }

    @Test
    fun anyResponseIdentityMismatchFailsBeforeOk() = runTest(UnconfinedTestDispatcher()) {
        val mismatches = listOf(
            VALID_TARGET.copy(orderId = OTHER_ORDER_ID),
            VALID_TARGET.copy(clientOrderId = "another-client"),
            VALID_TARGET.copy(symbol = "QQQ"),
            VALID_TARGET.copy(side = "SELL"),
            VALID_TARGET.copy(quantity = 2.0),
            VALID_TARGET.copy(orderType = "LIMIT"),
        )
        mismatches.forEach { responseIdentity ->
            val http = RecordingStatusHttpClient(success(responseIdentity))
            val result = AlpacaPaperOrderStatusReadOnlyClient(
                credentialsProvider,
                http,
            ).fetchOrderStatus(VALID_TARGET)
            assertEquals(
                AlpacaPaperOrderStatusReadOnlyClient.FetchResult.ResponseIdentityMismatch,
                result,
            )
            assertEquals(1, http.callCount)
        }
    }

    @Test
    fun errorsExposeNoResponseBodyOrCredentials() = runTest(UnconfinedTestDispatcher()) {
        val results = listOf(
            AlpacaPaperOrderStatusReadOnlyClient(
                credentialsProvider,
                RecordingStatusHttpClient(PaperOrderStatusHttpResult.HttpError(403)),
            ).fetchOrderStatus(VALID_TARGET),
            AlpacaPaperOrderStatusReadOnlyClient(
                credentialsProvider,
                RecordingStatusHttpClient(PaperOrderStatusHttpResult.NetworkError),
            ).fetchOrderStatus(VALID_TARGET),
            AlpacaPaperOrderStatusReadOnlyClient(
                credentialsProvider,
                RecordingStatusHttpClient(
                    PaperOrderStatusHttpResult.Success(
                        200,
                        "not-json APCA-API-SECRET-KEY=top-secret-value",
                    ),
                ),
            ).fetchOrderStatus(VALID_TARGET),
        )
        assertTrue(results[0] is AlpacaPaperOrderStatusReadOnlyClient.FetchResult.HttpError)
        assertEquals(AlpacaPaperOrderStatusReadOnlyClient.FetchResult.NetworkError, results[1])
        assertTrue(results[2] is AlpacaPaperOrderStatusReadOnlyClient.FetchResult.ParseError)
        results.forEach { result ->
            assertFalse(result.toString().contains("top-secret-value"))
            assertFalse(result.toString().contains("PKSAFE123"))
        }
    }

    @Test
    fun publicTransportSurfaceRemainsGetOnly() {
        assertEquals(
            setOf("executeGet"),
            AlpacaPaperOrderStatusHttpClient::class.java.declaredMethods.map { it.name }.toSet(),
        )
        val forbidden = listOf("submit", "cancel", "replace", "close", "delete", "patch", "post")
        AlpacaPaperOrderStatusReadOnlyClient::class.java.declaredMethods
            .map { it.name }
            .filterNot { it.contains('$') }
            .forEach { method ->
                forbidden.forEach { fragment ->
                    assertFalse(method.contains(fragment, ignoreCase = true))
                }
            }
    }

    private fun success(identity: PaperOrderLifecycleLookupTarget) =
        PaperOrderStatusHttpResult.Success(
            200,
            """
            {
              "id":"${identity.orderId}",
              "client_order_id":"${identity.clientOrderId}",
              "symbol":"${identity.symbol}",
              "side":"${identity.side.lowercase()}",
              "qty":"${identity.quantity}",
              "type":"${identity.orderType.lowercase()}",
              "time_in_force":"${identity.timeInForce.lowercase()}",
              "status":"filled",
              "filled_qty":"1",
              "filled_avg_price":"773.49",
              "filled_at":"2026-08-07T19:31:02Z"
            }
            """.trimIndent(),
        )

    companion object {
        private const val ORDER_ID = "4b60549d-6dab-47d8-93eb-382ed1eed108"
        private const val OTHER_ORDER_ID = "7d9b45d4-98fb-4f39-a9f0-22c58bdeca31"
        private val VALID_TARGET = PaperOrderLifecycleLookupTarget(
            submitAttemptId = "attempt-1",
            submitResultAuditEntryId = 2L,
            orderId = ORDER_ID,
            clientOrderId = "client-1",
            symbol = "SPY",
            side = "BUY",
            quantity = 1.0,
            orderType = "MARKET",
            timeInForce = "DAY",
        )
    }
}

private class RecordingStatusHttpClient(
    private val result: PaperOrderStatusHttpResult,
) : AlpacaPaperOrderStatusHttpClient {
    var callCount: Int = 0
        private set
    val urls = mutableListOf<String>()

    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): PaperOrderStatusHttpResult {
        AlpacaPaperOrderStatusEndpoint.requireSafeGet(url)
        callCount += 1
        urls += url
        return result
    }
}
