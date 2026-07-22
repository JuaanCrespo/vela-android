package com.vela.android.lab.data.paper

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OkHttp-backed [AlpacaHttpClient] used by the production DI graph.
 * Each `executeGet` call:
 *
 *  1. Runs [AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet]
 *     on the URL — non-allow-listed URLs raise
 *     `IllegalArgumentException` *before* any network I/O.
 *  2. Builds a `GET` request with the two Alpaca credential headers.
 *  3. Switches to `Dispatchers.IO` for the synchronous OkHttp call.
 *  4. Returns a typed [HttpResult]; **never** logs credentials.
 *
 * No other HTTP verb is implementable through this class — there is
 * no `post`, `delete`, `put`, or `patch` method on the surface.
 */
class OkHttpAlpacaHttpClient(
    private val client: OkHttpClient = defaultClient(),
) : AlpacaHttpClient {

    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): HttpResult {
        AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet(url)
        val request = Request.Builder()
            .url(url)
            .get()
            .header("APCA-API-KEY-ID", keyId)
            .header("APCA-API-SECRET-KEY", secret)
            .header("Accept", "application/json")
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        HttpResult.Success(response.code, body)
                    } else {
                        HttpResult.HttpError(response.code, body)
                    }
                }
            } catch (io: IOException) {
                HttpResult.NetworkError(io.message ?: "Network error")
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
