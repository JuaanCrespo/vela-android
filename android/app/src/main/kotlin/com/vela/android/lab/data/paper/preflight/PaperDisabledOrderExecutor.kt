package com.vela.android.lab.data.paper.preflight

import java.time.Instant

/** Result of the only Phase 2.r executor surface: a local rejection. */
data class DisabledExecutionResult(
    val previewId: String,
    val linkedClientDryRunId: String,
    val result: DisabledExecutionStatus = DisabledExecutionStatus.EXECUTION_DISABLED,
    val reason: String = REASON,
    val createdAtEpochMillis: Long,
) {
    init {
        require(result == DisabledExecutionStatus.EXECUTION_DISABLED) {
            "A disabled execution result cannot be changed."
        }
        require(reason == REASON) { "The disabled execution reason cannot be changed." }
    }

    companion object {
        const val REASON: String = "Execution is disabled — no order can be sent"
    }
}

enum class DisabledExecutionStatus {
    EXECUTION_DISABLED,
}

/**
 * Phase 2.r local rejection surface. It has no credentials, HTTP
 * client, endpoint, request, persistence, or account dependency.
 * Its sole method always returns [DisabledExecutionStatus.EXECUTION_DISABLED].
 */
class PaperDisabledOrderExecutor(
    private val clock: () -> Instant = { Instant.now() },
) {
    fun attemptDisabledExecution(preview: PaperOrderPayloadPreview): DisabledExecutionResult =
        DisabledExecutionResult(
            previewId = preview.previewId,
            linkedClientDryRunId = preview.linkedClientDryRunId,
            result = DisabledExecutionStatus.EXECUTION_DISABLED,
            reason = DisabledExecutionResult.REASON,
            createdAtEpochMillis = clock().toEpochMilli(),
        )
}
