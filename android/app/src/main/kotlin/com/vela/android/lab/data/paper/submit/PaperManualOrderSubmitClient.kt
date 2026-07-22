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
            is PaperSubmitHttpResult.Success -> parseSuccess(request, response.body, now)
            is PaperSubmitHttpResult.HttpError -> PaperOrderSubmitResult(
                submitAttemptId = request.submitAttemptId,
                previewId = request.previewId,
                status = PaperOrderSubmitStatus.REJECTED,
                alpacaOrderId = null,
                clientOrderId = request.clientOrderId,
                submittedAtEpochMillis = now,
                errorCode = PaperOrderSubmitError.HTTP_REJECTED,
                safeErrorMessage = safeProviderMessage(response.body, "Paper order rejected."),
            )
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
        body: String,
        now: Long,
    ): PaperOrderSubmitResult = try {
        val id = JSONObject(body).optString("id", "").trim()
        if (id.isEmpty()) {
            failed(
                request,
                PaperOrderSubmitStatus.FAILED,
                PaperOrderSubmitError.RESPONSE_PARSE_FAILED,
                "Paper submit response did not include an order id.",
                now,
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
            )
        }
    } catch (_: JSONException) {
        failed(
            request,
            PaperOrderSubmitStatus.FAILED,
            PaperOrderSubmitError.RESPONSE_PARSE_FAILED,
            "Paper submit response was invalid JSON.",
            now,
        )
    }

    private fun safeProviderMessage(body: String, fallback: String): String = try {
        PaperSubmitSanitizer.safeMessage(JSONObject(body).optString("message", ""), fallback)
    } catch (_: JSONException) {
        fallback
    }

    private fun failed(
        request: PaperOrderSubmitRequest,
        status: PaperOrderSubmitStatus,
        error: PaperOrderSubmitError,
        message: String,
        now: Long,
    ): PaperOrderSubmitResult = PaperOrderSubmitResult(
        submitAttemptId = request.submitAttemptId,
        previewId = request.previewId,
        status = status,
        alpacaOrderId = null,
        clientOrderId = request.clientOrderId,
        submittedAtEpochMillis = now,
        errorCode = error,
        safeErrorMessage = PaperSubmitSanitizer.safeMessage(message, "Paper submit failed."),
    )
}
