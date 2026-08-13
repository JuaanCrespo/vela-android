@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vela.android.lab.data.paper.status

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlpacaPaperOrderStatusHttpClientTest {

    private val id = "4b60549d-6dab-47d8-93eb-382ed1eed108"

    @Test
    fun `default HTTP client disables redirects SSL redirects and retry`() {
        val client = OkHttpAlpacaPaperOrderStatusHttpClient.defaultClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test
    fun `unsafe injected HTTP configuration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OkHttpAlpacaPaperOrderStatusHttpClient(OkHttpClient())
        }
    }

    @Test
    fun `request is exactly one GET with credential headers`() =
        runTest(UnconfinedTestDispatcher()) {
            var calls = 0
            var recordedRequest: Request? = null
            val body = """{
              "id":"$id","status":"new","filled_qty":"0",
              "filled_avg_price":null,"filled_at":null
            }"""
            val okHttp = safeBuilder()
                .addInterceptor { chain ->
                    calls += 1
                    recordedRequest = chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()
            val client = OkHttpAlpacaPaperOrderStatusHttpClient(okHttp)

            val result = client.executeGet(
                AlpacaPaperOrderStatusEndpoint.urlFor(id),
                "PKSAFE123",
                "top-secret-value",
            )

            assertTrue(result is PaperOrderStatusHttpResult.Success)
            assertEquals(1, calls)
            assertEquals("GET", recordedRequest?.method)
            assertEquals("PKSAFE123", recordedRequest?.header("APCA-API-KEY-ID"))
            assertEquals(
                "top-secret-value",
                recordedRequest?.header("APCA-API-SECRET-KEY"),
            )
        }

    @Test
    fun `HTTP error discards body and invalid URL reaches no interceptor`() =
        runTest(UnconfinedTestDispatcher()) {
            var calls = 0
            val okHttp = safeBuilder()
                .addInterceptor { chain ->
                    calls += 1
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body(
                            "APCA-API-SECRET-KEY=top-secret-value"
                                .toResponseBody("application/json".toMediaType()),
                        )
                        .build()
                }
                .build()
            val client = OkHttpAlpacaPaperOrderStatusHttpClient(okHttp)
            val result = client.executeGet(
                AlpacaPaperOrderStatusEndpoint.urlFor(id),
                "PKSAFE123",
                "top-secret-value",
            )

            assertEquals(PaperOrderStatusHttpResult.HttpError(403), result)
            assertFalse(result.toString().contains("top-secret-value"))
            assertEquals(1, calls)

            val invalidUrlError = runCatching {
                client.executeGet(
                    "https://api.alpaca.markets/v2/orders/$id",
                    "PKSAFE123",
                    "top-secret-value",
                )
            }.exceptionOrNull()
            assertTrue(invalidUrlError is IllegalArgumentException)
            assertEquals(1, calls)
        }

    private fun safeBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
}
