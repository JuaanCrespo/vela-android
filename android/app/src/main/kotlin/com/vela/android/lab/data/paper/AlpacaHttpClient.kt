package com.vela.android.lab.data.paper

/**
 * Phase 2.k HTTP boundary for the Paper Trading API.
 *
 * The interface deliberately exposes **only** a GET method. There
 * is no `post`, `put`, `patch`, or `delete` — Phase 2.k's safety
 * contract is that no mutation request can be constructed via this
 * boundary by any caller.
 *
 * Implementations must call
 * [AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet] on the
 * URL before issuing the request.
 */
interface AlpacaHttpClient {

    /**
     * Issue a single GET. The implementation:
     *  - validates [url] via the endpoint guard,
     *  - attaches `APCA-API-KEY-ID: [keyId]` + `APCA-API-SECRET-KEY: [secret]`,
     *  - returns the parsed [HttpResult].
     *
     * Credentials are **never** logged. The body string of an HTTP
     * error response is returned verbatim so the caller can surface
     * an Alpaca-formatted error message without re-issuing the call.
     */
    suspend fun executeGet(url: String, keyId: String, secret: String): HttpResult
}

/** Outcome of an [AlpacaHttpClient.executeGet] call. */
sealed interface HttpResult {
    data class Success(val statusCode: Int, val body: String) : HttpResult
    data class HttpError(val statusCode: Int, val body: String) : HttpResult
    data class NetworkError(val message: String) : HttpResult
}
