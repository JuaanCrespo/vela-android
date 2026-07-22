package com.vela.android.lab.data.paper.submit

import com.vela.android.lab.data.paper.preflight.OrderSide
import com.vela.android.lab.data.paper.preflight.OrderType
import com.vela.android.lab.data.paper.preflight.TimeInForce

/** Immutable, credential-free request authorized for one Paper attempt. */
data class PaperOrderSubmitRequest(
    val submitAttemptId: String,
    val previewId: String,
    val linkedClientDryRunId: String,
    val symbol: String,
    val side: OrderSide,
    val type: OrderType,
    val timeInForce: TimeInForce,
    val quantity: Double,
    val limitPrice: Double?,
    val clientOrderId: String,
    val generatedAtEpochMillis: Long,
    val confirmationTokenId: String,
) {
    init {
        require(submitAttemptId.isNotBlank()) { "Submit attempt id is required." }
        require(previewId.isNotBlank()) { "Preview id is required." }
        require(linkedClientDryRunId.isNotBlank()) { "Dry-run id is required." }
        require(symbol.isNotBlank()) { "Symbol is required." }
        require(quantity.isFinite() && quantity > 0.0) { "Quantity must be positive and finite." }
        require(type == OrderType.MARKET || type == OrderType.LIMIT) {
            "Only MARKET and LIMIT are supported."
        }
        require(timeInForce == TimeInForce.DAY) { "Only DAY time-in-force is supported." }
        require(type != OrderType.LIMIT ||
            (limitPrice != null && limitPrice.isFinite() && limitPrice > 0.0)) {
            "LIMIT requires a positive finite limit price."
        }
        require(type != OrderType.MARKET || limitPrice == null) {
            "MARKET must not carry a limit price."
        }
        require(clientOrderId.isNotBlank()) { "Client order id is required." }
        require(confirmationTokenId.isNotBlank()) { "Confirmation token id is required." }
    }
}

enum class PaperOrderSubmitStatus {
    SUBMITTED,
    REJECTED,
    FAILED,
    BLOCKED,
}

enum class PaperOrderSubmitError {
    HUMAN_APPROVAL_MISSING,
    FEATURE_DISABLED,
    EMERGENCY_DISABLED,
    REAL_NOT_LOCKED,
    LIVE_NOT_DISABLED,
    AUTO_PAPER_NOT_DISABLED,
    CREDENTIALS_MISSING,
    ACCOUNT_BLOCKED,
    TRADING_BLOCKED,
    ACCOUNT_STALE,
    CLOCK_STALE,
    MARKET_CLOSED,
    PRICE_NOT_FRESH,
    PRICE_DRIFT_EXCEEDED,
    PREFLIGHT_BLOCKED,
    WARNING_NOT_ACCEPTED,
    READINESS_MISSING,
    REVIEW_ROW_MISSING,
    PREVIEW_MISMATCH,
    CONFIRMATION_MISSING,
    CONFIRMATION_EXPIRED,
    CONFIRMATION_MISMATCH,
    DUPLICATE_PREVIEW,
    DUPLICATE_CLIENT_ORDER_ID,
    SUBMIT_ALREADY_IN_FLIGHT,
    AUDIT_WRITE_FAILED,
    AUTH_MISSING,
    HTTP_REJECTED,
    NETWORK_FAILURE,
    RESPONSE_PARSE_FAILED,
    TERMINAL_AUDIT_FAILED,
}

/** Sanitized result safe for UI and append-only audit storage. */
data class PaperOrderSubmitResult(
    val submitAttemptId: String,
    val previewId: String,
    val status: PaperOrderSubmitStatus,
    val alpacaOrderId: String?,
    val clientOrderId: String,
    val submittedAtEpochMillis: Long,
    val errorCode: PaperOrderSubmitError?,
    val safeErrorMessage: String?,
) {
    init {
        require(submitAttemptId.isNotBlank()) { "Submit attempt id is required." }
        require(previewId.isNotBlank()) { "Preview id is required." }
        require(clientOrderId.isNotBlank()) { "Client order id is required." }
        require(status != PaperOrderSubmitStatus.SUBMITTED || !alpacaOrderId.isNullOrBlank()) {
            "A submitted result requires a Paper order id."
        }
        require(status == PaperOrderSubmitStatus.SUBMITTED || errorCode != null) {
            "A non-submitted result requires a safe error code."
        }
    }
}

internal object PaperSubmitSanitizer {
    private val sensitiveShape = Regex(
        "(?i)(APCA-API-(?:KEY-ID|SECRET-KEY)|authorization|bearer|secret|api[_-]?key)" +
            "\\s*[:=]\\s*[^,;\\s]+",
    )

    fun safeMessage(value: String?, fallback: String): String {
        val source = value?.trim().orEmpty().ifBlank { fallback }
        return sensitiveShape.replace(source, "[redacted]").take(240)
    }
}
