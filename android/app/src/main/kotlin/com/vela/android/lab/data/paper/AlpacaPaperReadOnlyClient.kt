package com.vela.android.lab.data.paper

import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider

/**
 * Phase 2.k read-only Paper Trading API client. Surface is **only**:
 *
 *  - [fetchAccount] → `GET /v2/account`
 *  - [fetchClock] → `GET /v2/clock`
 *  - [fetchPositions] → `GET /v2/positions`
 *
 * There is **no** `submitOrder`, **no** `cancelOrder`, **no**
 * `replaceOrder`, **no** `closePosition`, **no** mutation method of
 * any kind. The Phase 2.k reflection contract test enforces this by
 * scanning every declared method name on this class.
 *
 * Credentials never appear in any returned [FetchResult]. The
 * client reads them from [credentialsProvider] just before issuing
 * the HTTP call and discards them afterwards.
 */
class AlpacaPaperReadOnlyClient(
    private val credentialsProvider: AlpacaCredentialsProvider,
    private val httpClient: AlpacaHttpClient,
    private val parser: PaperJsonParser = PaperJsonParser(),
) {

    sealed interface FetchResult<out T> {
        data class Ok<T>(val value: T) : FetchResult<T>
        object AuthMissing : FetchResult<Nothing> {
            override fun toString(): String = "AuthMissing"
        }
        data class HttpError(val code: Int, val message: String) : FetchResult<Nothing>
        data class ParseError(val message: String) : FetchResult<Nothing>
        data class NetworkError(val message: String) : FetchResult<Nothing>
    }

    suspend fun fetchAccount(): FetchResult<PaperAccountSnapshot> =
        executeAndParse(AlpacaPaperTradingEndpoint.ACCOUNT_URL) { parser.parseAccount(it) }

    suspend fun fetchClock(): FetchResult<PaperClockSnapshot> =
        executeAndParse(AlpacaPaperTradingEndpoint.CLOCK_URL) { parser.parseClock(it) }

    suspend fun fetchPositions(): FetchResult<List<PaperPositionSnapshot>> =
        executeAndParse(AlpacaPaperTradingEndpoint.POSITIONS_URL) { parser.parsePositions(it) }

    private suspend fun <T> executeAndParse(
        url: String,
        parse: (String) -> PaperJsonParser.ParseResult<T>,
    ): FetchResult<T> {
        val credentials = credentialsProvider.read() ?: return FetchResult.AuthMissing
        val response = httpClient.executeGet(url, credentials.keyId, credentials.secret)
        return when (response) {
            is HttpResult.Success -> when (val parsed = parse(response.body)) {
                is PaperJsonParser.ParseResult.Ok -> FetchResult.Ok(parsed.value)
                is PaperJsonParser.ParseResult.Err -> FetchResult.ParseError(parsed.message)
            }
            is HttpResult.HttpError -> FetchResult.HttpError(
                code = response.statusCode,
                message = response.body.ifBlank { "HTTP ${response.statusCode}" },
            )
            is HttpResult.NetworkError -> FetchResult.NetworkError(response.message)
        }
    }
}
