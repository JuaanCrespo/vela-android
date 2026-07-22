package com.vela.android.lab.data.paper.preflight

/**
 * Phase 2.p local-only representation of an order request that could
 * be reviewed, but can never be executed by this application.
 *
 * There is deliberately no endpoint, credential, account id, API
 * key, server order id, or network client on this model. The only
 * execution-shaped field is [executionEnabled], and the constructor
 * rejects any attempt to set it to `true`.
 */
data class PaperOrderRequestDraft(
    val clientDryRunId: String,
    val symbol: String,
    val side: OrderSide,
    val type: OrderType,
    val timeInForce: TimeInForce,
    val quantity: Double,
    val limitPriceUsd: Double?,
    val estimatedNotionalUsd: Double?,
    val priceSource: String?,
    val priceFreshness: String?,
    val priceAgeMillis: Long?,
    val relatedSignalState: String?,
    val createdAtEpochMillis: Long,
    val status: PaperOrderRequestDraftStatus,
    /** Human-readable references to preflight warnings. */
    val warningMessages: List<String>,
    val executionEnabled: Boolean = false,
) {
    init {
        require(!executionEnabled) {
            "Paper order draft execution is permanently disabled in Phase 2.p."
        }
    }
}

/** A draft is local-only, with or without retained preflight warnings. */
enum class PaperOrderRequestDraftStatus {
    READY_LOCAL,
    READY_LOCAL_WITH_WARNINGS,
}

/** Typed result of [PaperOrderRequestDraftBuilder.build]. */
sealed interface PaperOrderRequestDraftValidation {
    data class Valid(val draft: PaperOrderRequestDraft) : PaperOrderRequestDraftValidation

    data class Rejected(
        val reason: PaperOrderRequestDraftRejection,
        val message: String,
    ) : PaperOrderRequestDraftValidation
}

/** Safe, local validation reasons. None represents a server response. */
enum class PaperOrderRequestDraftRejection {
    NO_PREFLIGHT_APPROVAL,
    BLOCKED_PREFLIGHT,
    NON_MANUAL_SOURCE,
    EXECUTION_GUARD_ENABLED,
    INVALID_DRAFT_INPUT,
}
