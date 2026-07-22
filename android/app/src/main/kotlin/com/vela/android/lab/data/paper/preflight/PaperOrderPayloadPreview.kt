package com.vela.android.lab.data.paper.preflight

/**
 * Phase 2.q theoretical Paper order payload fields. This is plain
 * local data, not an HTTP body or request object.
 */
data class PaperOrderPayloadFields(
    val symbol: String,
    val side: String,
    val type: String,
    val timeInForce: String,
    val quantity: Double,
    val limitPriceUsd: Double?,
)

/**
 * Immutable local payload preview. It cannot be converted into an
 * executable request by any API in this phase.
 *
 * No credential, API key, account id, API header, live endpoint, or
 * server order id is present. The three safety markers are guarded
 * in [init], including when callers use the generated `copy` method.
 */
data class PaperOrderPayloadPreview(
    val previewId: String,
    val linkedClientDryRunId: String,
    val symbol: String,
    val side: OrderSide,
    val type: OrderType,
    val timeInForce: TimeInForce,
    val quantity: Double,
    val limitPriceUsd: Double?,
    val estimatedNotionalUsd: Double?,
    val priceSource: String?,
    val priceFreshness: String?,
    val relatedSignalState: String?,
    val generatedAtEpochMillis: Long,
    val status: PaperOrderPayloadPreviewStatus,
    val warningMessages: List<String>,
    val payloadFields: PaperOrderPayloadFields,
    val executionEnabled: Boolean = false,
    val endpointPreview: String = ENDPOINT_DISABLED,
    val httpMethodPreview: String = HTTP_METHOD_POST_DISABLED,
) {
    init {
        require(!executionEnabled) { "Payload preview execution is permanently disabled." }
        require(endpointPreview == ENDPOINT_DISABLED) {
            "Payload preview endpoint must remain DISABLED."
        }
        require(httpMethodPreview == HTTP_METHOD_POST_DISABLED) {
            "Payload preview HTTP method must remain POST_DISABLED."
        }
    }

    companion object {
        const val ENDPOINT_DISABLED: String = "DISABLED"
        const val HTTP_METHOD_POST_DISABLED: String = "POST_DISABLED"
    }
}

enum class PaperOrderPayloadPreviewStatus {
    READY_PREVIEW,
    READY_PREVIEW_WITH_WARNINGS,
}

sealed interface PaperOrderPayloadPreviewValidation {
    data class Valid(val preview: PaperOrderPayloadPreview) : PaperOrderPayloadPreviewValidation

    data class Rejected(
        val reason: PaperOrderPayloadPreviewRejection,
        val message: String,
    ) : PaperOrderPayloadPreviewValidation
}

enum class PaperOrderPayloadPreviewRejection {
    EXECUTION_NOT_DISABLED,
    EXECUTION_GUARD_ENABLED,
    DRAFT_NOT_READY,
    INVALID_DRAFT,
}
