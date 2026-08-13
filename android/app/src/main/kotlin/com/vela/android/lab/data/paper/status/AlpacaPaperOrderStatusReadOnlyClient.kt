package com.vela.android.lab.data.paper.status

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider

/**
 * Credential-safe read-only lifecycle client for one previously submitted Paper order.
 * It has no mutation method and never retries automatically.
 */
class AlpacaPaperOrderStatusReadOnlyClient(
    private val credentialsProvider: AlpacaCredentialsProvider,
    private val httpClient: AlpacaPaperOrderStatusHttpClient,
    private val parser: PaperOrderStatusJsonParser = PaperOrderStatusJsonParser(),
) {

    sealed interface FetchResult {
        data class Ok(val value: PaperOrderStatusSnapshot) : FetchResult
        data object AuthMissing : FetchResult {
            override fun toString(): String = "AuthMissing"
        }
        data object InvalidOrderId : FetchResult {
            override fun toString(): String = "InvalidOrderId"
        }
        data class HttpError(val statusCode: Int) : FetchResult
        data object NetworkError : FetchResult {
            override fun toString(): String = "NetworkError"
        }
        data class ParseError(val safeMessage: String) : FetchResult
        data object ResponseIdMismatch : FetchResult {
            override fun toString(): String = "ResponseIdMismatch"
        }
    }

    suspend fun fetchOrderStatus(orderId: String): FetchResult {
        val url = try {
            AlpacaPaperOrderStatusEndpoint.urlFor(orderId)
        } catch (_: IllegalArgumentException) {
            return FetchResult.InvalidOrderId
        }
        val credentials = credentialsProvider.read() ?: return FetchResult.AuthMissing
        return when (val response = httpClient.executeGet(
            url = url,
            keyId = credentials.keyId,
            secret = credentials.secret,
        )) {
            is PaperOrderStatusHttpResult.Success -> when (val parsed = parser.parse(response.body)) {
                is PaperOrderStatusJsonParser.ParseResult.Ok -> {
                    if (parsed.value.orderId == orderId) {
                        FetchResult.Ok(parsed.value)
                    } else {
                        FetchResult.ResponseIdMismatch
                    }
                }
                is PaperOrderStatusJsonParser.ParseResult.Err ->
                    FetchResult.ParseError(parsed.safeMessage)
            }
            is PaperOrderStatusHttpResult.HttpError ->
                FetchResult.HttpError(response.statusCode)
            PaperOrderStatusHttpResult.NetworkError -> FetchResult.NetworkError
        }
    }
}
