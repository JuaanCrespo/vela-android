package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface PaperSubmitHttpResult {
    data class Success(val statusCode: Int, val body: String) : PaperSubmitHttpResult
    data class HttpError(val statusCode: Int, val body: String) : PaperSubmitHttpResult
    data object AuthMissing : PaperSubmitHttpResult
    data object NetworkError : PaperSubmitHttpResult
}

/** One-method HTTP surface. It cannot cancel, replace, close, or mutate an account. */
fun interface AlpacaPaperOrderSubmitHttpClient {
    suspend fun executePostOrder(url: String, bodyJson: String): PaperSubmitHttpResult
}

/** Guard-before-request implementation for the exact Paper orders collection. */
class OkHttpAlpacaPaperOrderSubmitHttpClient(
    private val credentialsProvider: AlpacaCredentialsProvider,
    private val client: OkHttpClient = defaultClient(),
) : AlpacaPaperOrderSubmitHttpClient {

    override suspend fun executePostOrder(
        url: String,
        bodyJson: String,
    ): PaperSubmitHttpResult {
        AlpacaPaperSubmitEndpoint.requireSafeManualPaperOrder(
            method = AlpacaPaperSubmitEndpoint.METHOD,
            url = url,
        )
        val credentials = credentialsProvider.read() ?: return PaperSubmitHttpResult.AuthMissing
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("APCA-API-KEY-ID", credentials.keyId)
            .header("APCA-API-SECRET-KEY", credentials.secret)
            .header("Accept", "application/json")
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        PaperSubmitHttpResult.Success(response.code, body)
                    } else {
                        PaperSubmitHttpResult.HttpError(response.code, body)
                    }
                }
            } catch (_: IOException) {
                PaperSubmitHttpResult.NetworkError
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
