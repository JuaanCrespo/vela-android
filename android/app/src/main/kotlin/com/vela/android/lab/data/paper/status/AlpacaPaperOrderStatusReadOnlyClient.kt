package com.vela.android.lab.data.paper.status

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentials

/**
 * Credential-safe read-only lifecycle client for one previously submitted Paper order.
 * It has no mutation method and never retries automatically.
 */
class AlpacaPaperOrderStatusReadOnlyClient(
    private val credentialsProvider: AlpacaCredentialsProvider,
    private val httpClient: AlpacaPaperOrderStatusHttpClient,
    private val parser: PaperOrderStatusJsonParser = PaperOrderStatusJsonParser(),
    private val accountRefProvider: suspend (AlpacaCredentials) -> String? = { null },
    private val rawEvidenceFactory: (String, String) -> PaperOrderRawDecimalEvidence? = { _, _ -> null },
) {

    sealed interface FetchResult {
        data class Ok(
            val value: PaperOrderStatusSnapshot,
            val evidence: PaperOrderStatusFetchEvidence,
        ) : FetchResult
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
        data object ResponseIdentityMismatch : FetchResult {
            override fun toString(): String = "ResponseIdentityMismatch"
        }
    }

    suspend fun fetchOrderStatus(target: PaperOrderLifecycleLookupTarget): FetchResult {
        val url = try {
            AlpacaPaperOrderStatusEndpoint.urlFor(target.orderId)
        } catch (_: IllegalArgumentException) {
            return FetchResult.InvalidOrderId
        }
        val credentials = credentialsProvider.read() ?: return FetchResult.AuthMissing
        val accountRef = accountRefProvider(credentials)
        return when (val response = httpClient.executeGet(
            url = url,
            keyId = credentials.keyId,
            secret = credentials.secret,
        )) {
            is PaperOrderStatusHttpResult.Success -> {
                // Capture original decimal strings before the legacy display parser performs any conversion.
                val exact = if (accountRef != null && accountRefProvider(credentials) == accountRef)
                    rawEvidenceFactory(response.body, accountRef) else null
                when (
                val parsed = parser.parse(response.body, target)
            ) {
                is PaperOrderStatusJsonParser.ParseResult.Ok -> {
                    if (parsed.value.matches(target)) {
                        FetchResult.Ok(
                            value = parsed.value,
                            evidence = PaperOrderStatusFetchEvidence(response.statusCode, exactDecimalEvidence = exact),
                        )
                    } else {
                        FetchResult.ResponseIdentityMismatch
                    }
                }
                is PaperOrderStatusJsonParser.ParseResult.Err ->
                    FetchResult.ParseError(parsed.safeMessage)
                PaperOrderStatusJsonParser.ParseResult.IdentityMismatch ->
                    FetchResult.ResponseIdentityMismatch
                }
            }
            is PaperOrderStatusHttpResult.HttpError ->
                FetchResult.HttpError(response.statusCode)
            PaperOrderStatusHttpResult.NetworkError -> FetchResult.NetworkError
        }
    }

    private fun PaperOrderStatusSnapshot.matches(
        target: PaperOrderLifecycleLookupTarget,
    ): Boolean = orderId == target.orderId &&
        (target.clientOrderId == null || clientOrderId == target.clientOrderId) &&
        symbol == target.symbol &&
        side == target.side &&
        quantity == target.quantity &&
        orderType == target.orderType &&
        timeInForce == target.timeInForce
}
