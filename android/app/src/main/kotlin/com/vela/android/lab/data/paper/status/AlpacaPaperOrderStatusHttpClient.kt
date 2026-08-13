package com.vela.android.lab.data.paper.status

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface PaperOrderStatusHttpResult {
    data class Success(val statusCode: Int, val body: String) : PaperOrderStatusHttpResult
    data class HttpError(val statusCode: Int) : PaperOrderStatusHttpResult
    data object NetworkError : PaperOrderStatusHttpResult
}

/** One-method GET-only HTTP surface for an exact Paper order lifecycle URL. */
fun interface AlpacaPaperOrderStatusHttpClient {
    suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): PaperOrderStatusHttpResult
}

class OkHttpAlpacaPaperOrderStatusHttpClient(
    private val client: OkHttpClient = defaultClient(),
) : AlpacaPaperOrderStatusHttpClient {

    init {
        require(!client.followRedirects) { "Paper status HTTP redirects must be disabled." }
        require(!client.followSslRedirects) { "Paper status HTTPS redirects must be disabled." }
        require(!client.retryOnConnectionFailure) {
            "Paper status automatic connection retry must be disabled."
        }
    }

    override suspend fun executeGet(
        url: String,
        keyId: String,
        secret: String,
    ): PaperOrderStatusHttpResult {
        AlpacaPaperOrderStatusEndpoint.requireSafeGet(url)
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
                    if (response.isSuccessful) {
                        PaperOrderStatusHttpResult.Success(
                            statusCode = response.code,
                            body = response.body?.string().orEmpty(),
                        )
                    } else {
                        PaperOrderStatusHttpResult.HttpError(response.code)
                    }
                }
            } catch (_: IOException) {
                PaperOrderStatusHttpResult.NetworkError
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
