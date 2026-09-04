package com.vela.android.lab.data.paper.submit

import java.time.Instant
import org.json.JSONException
import org.json.JSONObject

/** One-shot serializer/parser over the narrow submit HTTP interface. No retry loop exists. */
class PaperManualOrderSubmitClient(
    private val httpClient: AlpacaPaperOrderSubmitHttpClient,
    private val clock: () -> Instant = { Instant.now() },
) {
    suspend fun submitOnce(request: PaperOrderSubmitRequest): PaperOrderSubmitResult {
        val body = JSONObject()
            .put("symbol", request.symbol)
            .put("side", request.side.name.lowercase())
            .put("type", request.type.name.lowercase())
            .put("qty", request.quantity.toString())
            .put("time_in_force", request.timeInForce.name.lowercase())
            .put("client_order_id", request.clientOrderId)
            .also { json ->
                request.limitPrice?.let { json.put("limit_price", it.toString()) }
            }
            .toString()

        val response = httpClient.executePostOrder(
            url = AlpacaPaperSubmitEndpoint.ORDERS_URL,
            bodyJson = body,
        )
        val now = clock().toEpochMilli()
        return when (response) {
            is PaperSubmitHttpResult.Success ->
                parseSuccess(request, response.statusCode, response.body, now)
            is PaperSubmitHttpResult.HttpError -> {
                val metadata = optionalSubmitMetadata(response.body)
                PaperOrderSubmitResult(
                    submitAttemptId = request.submitAttemptId,
                    previewId = request.previewId,
                    status = PaperOrderSubmitStatus.REJECTED,
                    alpacaOrderId = null,
                    clientOrderId = request.clientOrderId,
                    submittedAtEpochMillis = now,
                    errorCode = PaperOrderSubmitError.HTTP_REJECTED,
                    safeErrorMessage = safeProviderMessage(response.body, "Paper order rejected."),
                    httpStatusCode = response.statusCode,
                    initialAlpacaStatus = metadata.initialStatus,
                    alpacaSubmittedAtIso = metadata.submittedAtIso,
                )
            }
            PaperSubmitHttpResult.AuthMissing -> failed(
                request,
                PaperOrderSubmitStatus.BLOCKED,
                PaperOrderSubmitError.AUTH_MISSING,
                "Paper credentials are not configured.",
                now,
            )
            PaperSubmitHttpResult.NetworkError -> failed(
                request,
                PaperOrderSubmitStatus.FAILED,
                PaperOrderSubmitError.NETWORK_FAILURE,
                "Paper submit network request failed; no automatic retry was attempted.",
                now,
            )
        }
    }

    private fun parseSuccess(
        request: PaperOrderSubmitRequest,
        httpStatusCode: Int,
        body: String,
        now: Long,
    ): PaperOrderSubmitResult = try {
        val json = JSONObject(body)
        val id = json.optString("id", "").trim()
        val initialStatus = normalizedInitialStatus(json.optString("status", ""))
        val submittedAtIso = validInstantOrNull(json.optString("submitted_at", ""))
        if (id.isEmpty()) {
            failed(
                request,
                PaperOrderSubmitStatus.FAILED,
                PaperOrderSubmitError.RESPONSE_PARSE_FAILED,
                "Paper submit response did not include an order id.",
                now,
                httpStatusCode,
                initialStatus,
                submittedAtIso,
            )
        } else {
            PaperOrderSubmitResult(
                submitAttemptId = request.submitAttemptId,
                previewId = request.previewId,
                status = PaperOrderSubmitStatus.SUBMITTED,
                alpacaOrderId = id,
                clientOrderId = request.clientOrderId,
                submittedAtEpochMillis = now,
                errorCode = null,
                safeErrorMessage = null,
                httpStatusCode = httpStatusCode,
                initialAlpacaStatus = initialStatus,
                alpacaSubmittedAtIso = submittedAtIso,
            )
        }
    } catch (_: JSONException) {
        failed(
            request,
            PaperOrderSubmitStatus.FAILED,
            PaperOrderSubmitError.RESPONSE_PARSE_FAILED,
            "Paper submit response was invalid JSON.",
            now,
            httpStatusCode,
        )
    }

    private fun safeProviderMessage(body: String, fallback: String): String = try {
        PaperSubmitSanitizer.safeMessage(JSONObject(body).optString("message", ""), fallback)
    } catch (_: JSONException) {
        fallback
    }

    /** Optional observability never changes the already-established submit result. */
    private fun optionalSubmitMetadata(body: String): OptionalSubmitMetadata = try {
        val json = JSONObject(body)
        OptionalSubmitMetadata(
            initialStatus = normalizedInitialStatus(json.optString("status", "")),
            submittedAtIso = validInstantOrNull(json.optString("submitted_at", "")),
        )
    } catch (_: JSONException) {
        OptionalSubmitMetadata(null, null)
    }

    private fun normalizedInitialStatus(value: String): String? = value.trim().lowercase()
        .takeIf { it.matches(Regex("^[a-z_]{1,40}$")) }

    private fun validInstantOrNull(value: String): String? = value.trim()
        .takeIf(String::isNotEmpty)
        ?.takeIf { runCatching { Instant.parse(it) }.isSuccess }

    private data class OptionalSubmitMetadata(
        val initialStatus: String?,
        val submittedAtIso: String?,
    )

    private fun failed(
        request: PaperOrderSubmitRequest,
        status: PaperOrderSubmitStatus,
        error: PaperOrderSubmitError,
        message: String,
        now: Long,
        httpStatusCode: Int? = null,
        initialAlpacaStatus: String? = null,
        alpacaSubmittedAtIso: String? = null,
    ): PaperOrderSubmitResult = PaperOrderSubmitResult(
        submitAttemptId = request.submitAttemptId,
        previewId = request.previewId,
        status = status,
        alpacaOrderId = null,
        clientOrderId = request.clientOrderId,
        submittedAtEpochMillis = now,
        errorCode = error,
        safeErrorMessage = PaperSubmitSanitizer.safeMessage(message, "Paper submit failed."),
        httpStatusCode = httpStatusCode,
        initialAlpacaStatus = initialAlpacaStatus,
        alpacaSubmittedAtIso = alpacaSubmittedAtIso,
    )
}
