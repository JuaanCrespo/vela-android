package com.vela.android.lab.data.paper.preflight

/**
 * Local-only Phase 2.r assessment of a theoretical payload preview.
 *
 * This model contains no credential values, account id, API headers,
 * endpoint, request body, or network client. The execution-related
 * booleans are constructor guarded so `copy` cannot enable them.
 */
data class PaperExecutionReadinessSnapshot(
    val previewId: String,
    val linkedClientDryRunId: String,
    val hasValidPreview: Boolean,
    val executionEnabled: Boolean = false,
    val realLocked: Boolean,
    val liveEndpointAllowed: Boolean = false,
    val paperPostOrdersAllowed: Boolean = false,
    val autoPaperEnabled: Boolean = false,
    val foregroundServiceEnabled: Boolean = false,
    /** Boolean presence signal only; no credential value is retained. */
    val credentialsConfigured: Boolean,
    val status: PaperExecutionReadinessStatus,
    val blockingReasons: List<PaperExecutionReadinessReason>,
    val warnings: List<PaperExecutionReadinessReason>,
    val checkedAtEpochMillis: Long,
) {
    init {
        require(!executionEnabled) { "Paper execution must remain disabled." }
        require(!liveEndpointAllowed) { "The LIVE endpoint must remain disabled." }
        require(!paperPostOrdersAllowed) { "Paper POST orders must remain disabled." }
        require(!autoPaperEnabled) { "Auto Paper must remain disabled." }
        require(!foregroundServiceEnabled) { "Foreground execution must remain disabled." }
        require(PaperExecutionReadinessReason.EXECUTION_DISABLED in blockingReasons) {
            "Every readiness snapshot must retain the execution-disabled reason."
        }
        require(PaperExecutionReadinessReason.PAPER_POST_ORDERS_DISABLED in blockingReasons) {
            "Every readiness snapshot must retain the Paper POST-disabled reason."
        }
    }
}

enum class PaperExecutionReadinessStatus {
    NOT_READY,
    READY_BUT_EXECUTION_DISABLED,
    BLOCKED,
}

/** Safe local reasons; none is a server response. */
enum class PaperExecutionReadinessReason {
    INVALID_PAYLOAD_PREVIEW,
    EXECUTION_GUARD_ENABLED,
    REAL_MODE_NOT_LOCKED,
    EXECUTION_DISABLED,
    PAPER_POST_ORDERS_DISABLED,
    LIVE_ENDPOINT_DISABLED,
    AUTO_PAPER_DISABLED,
    FOREGROUND_SERVICE_DISABLED,
    CREDENTIALS_NOT_CONFIGURED,
}
